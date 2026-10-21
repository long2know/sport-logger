package com.long2know.sportlogger.connector.endurain;

import com.long2know.sportlogger.security.EndurainApiKey;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.EventListener;
import okhttp3.Protocol;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class EndurainHttpsTransportAlpnTest {
    private static final Instant NOW = Instant.parse("2026-10-21T04:00:00Z");

    private File workDirectory;
    private RuntimeTlsMaterial tls;
    private MockWebServer server;
    private TcxUploadArtifact artifact;

    @Before
    public void setUp() throws Exception {
        workDirectory = new File(
                "build/test-data/endurain-alpn-" + System.nanoTime());
        tls = RuntimeTlsMaterial.create(new File(workDirectory, "tls"));
        server = new MockWebServer();
        server.useHttps(tls.serverContext().getSocketFactory(), false);
        server.setProtocols(Arrays.asList(
                Protocol.HTTP_2,
                Protocol.HTTP_1_1));
        server.start();
        artifact = createArtifact(new File(workDirectory, "activity.tcx"));
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
        if (tls != null) {
            tls.close();
        }
        deleteRecursively(workDirectory);
    }

    @Test
    public void alpnServerNegotiatesHttp11AndUsesConfiguredWriteTimeout()
            throws Exception {
        ProtocolCapture protocols = new ProtocolCapture();
        server.enqueue(aboutResponse());
        EndurainHttpsTransport transport = transport(
                Duration.ofSeconds(5),
                Duration.ofMillis(1234),
                protocols);

        EndurainProbeResult result = transport.probe(endpoint());

        assertEquals(EndurainProbeResult.State.AVAILABLE, result.state());
        assertEquals(1234, transport.okhttpWriteTimeoutMillisForTests());
        assertEquals(
                Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1),
                server.protocols());
        protocols.assertOnlyHttp11(1);
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request);
        assertNotNull(request.getHandshake());
        assertEquals("/api/v1/about", request.getPath());
    }

    @Test(timeout = 12000)
    public void concurrentDifferentDeadlinesRemainCallIsolated()
            throws Exception {
        CountDownLatch slowRequest = new CountDownLatch(1);
        CountDownLatch fastRequest = new CountDownLatch(1);
        CountDownLatch releaseFastResponse = new CountDownLatch(1);
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request)
                    throws InterruptedException {
                String apiKey = request.getHeader("X-API-Key");
                if ("slow-fixture-key".equals(apiKey)) {
                    slowRequest.countDown();
                    return createdResponse(41)
                            .throttleBody(
                                    1,
                                    150,
                                    TimeUnit.MILLISECONDS);
                }
                if ("fast-fixture-key".equals(apiKey)) {
                    fastRequest.countDown();
                    if (!releaseFastResponse.await(
                            5,
                            TimeUnit.SECONDS)) {
                        return jsonResponse(
                                503,
                                "{\"detail\":\"fixture timeout\"}");
                    }
                    return createdResponse(42);
                }
                return jsonResponse(404, "{\"detail\":\"not found\"}");
            }
        });

        ProtocolCapture protocols = new ProtocolCapture();
        EndurainHttpsTransport transport = transport(
                Duration.ofMillis(1800),
                Duration.ofSeconds(5),
                protocols);
        ScheduledExecutorService calls =
                Executors.newScheduledThreadPool(2);
        AtomicLong slowStarted = new AtomicLong();
        AtomicLong fastStarted = new AtomicLong();
        Future<EndurainUploadResult> slow = null;
        Future<EndurainUploadResult> fast = null;
        EndurainUploadResult slowResult;
        EndurainUploadResult fastResult;
        long slowElapsedMillis;
        try {
            slow = calls.submit(() -> {
                slowStarted.set(System.nanoTime());
                return upload(transport, "slow-fixture-key");
            });
            assertTrue(slowRequest.await(2, TimeUnit.SECONDS));
            fast = calls.schedule(() -> {
                fastStarted.set(System.nanoTime());
                return upload(transport, "fast-fixture-key");
            }, 600, TimeUnit.MILLISECONDS);
            assertTrue(fastRequest.await(2, TimeUnit.SECONDS));

            slowResult = slow.get(4, TimeUnit.SECONDS);
            slowElapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - slowStarted.get());
            releaseFastResponse.countDown();
            fastResult = fast.get(2, TimeUnit.SECONDS);
        } finally {
            releaseFastResponse.countDown();
            if (slow != null && !slow.isDone()) {
                slow.cancel(true);
            }
            if (fast != null && !fast.isDone()) {
                fast.cancel(true);
            }
            calls.shutdownNow();
            assertTrue(calls.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertTrue(
                TimeUnit.NANOSECONDS.toMillis(
                        fastStarted.get() - slowStarted.get()) >= 500);
        assertTrue(
                "Slow upload exceeded its bounded deadline",
                slowElapsedMillis < 3000);
        assertEquals(
                EndurainUploadResult.State.UNKNOWN_DELIVERY,
                slowResult.state());
        assertEquals(
                EndurainUploadResult.Reason.POST_TRANSMISSION_TIMEOUT,
                slowResult.reason());
        assertEquals(
                EndurainUploadResult.State.DELIVERED,
                fastResult.state());
        assertEquals(
                42,
                fastResult.remoteActivities().get(0).activityId());
        protocols.assertOnlyHttp11(2);
        assertEquals(0, EndurainHttpsTransport.pendingDeadlineTasksForTests());
    }

    private EndurainHttpsTransport transport(
            Duration overallTimeout,
            Duration writeTimeout,
            EventListener eventListener) {
        return new EndurainHttpsTransport(
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                writeTimeout,
                overallTimeout,
                EndurainHttpsTransport.DEFAULT_MAX_TCX_BYTES,
                1024,
                EndurainRetryPolicy.defaults(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                tls.pinnedCaSocketFactory(),
                tls.pinnedCaTrustManager(),
                eventListener);
    }

    private EndurainEndpoint endpoint() {
        return EndurainEndpoint.parse(server.url("/").toString());
    }

    private EndurainConnectionConfig config() {
        return new EndurainConnectionConfig(
                endpoint(),
                "connection-test");
    }

    private EndurainUploadResult upload(
            EndurainHttpsTransport transport,
            String apiKeyValue) {
        EndurainApiKey apiKey =
                EndurainApiKey.from(apiKeyValue.toCharArray());
        try {
            return transport.upload(config(), artifact, apiKey);
        } finally {
            apiKey.close();
        }
    }

    private static MockResponse aboutResponse() {
        return jsonResponse(
                200,
                "{\"name\":\"Endurain API\",\"version\":\"v0.19.0\","
                        + "\"license\":{\"name\":\"AGPL\","
                        + "\"identifier\":\"AGPL-3.0-or-later\","
                        + "\"url\":\"https://spdx.org/licenses/"
                        + "AGPL-3.0-or-later.html\"}}");
    }

    private static MockResponse createdResponse(long activityId) {
        return jsonResponse(
                201,
                "[{\"id\":" + activityId
                        + ",\"name\":\"Run\",\"distance\":1000,"
                        + "\"activity_type\":1}]");
    }

    private static MockResponse jsonResponse(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader(
                        "Content-Type",
                        "application/json; charset=utf-8")
                .setBody(body);
    }

    private static TcxUploadArtifact createArtifact(File file)
            throws Exception {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException(
                    "Could not create artifact directory");
        }
        byte[] bytes = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<TrainingCenterDatabase xmlns=\"http://www.garmin.com/"
                + "xmlschemas/TrainingCenterDatabase/v2\">"
                + "<Activities><Activity Sport=\"Running\">"
                + "<Id>2026-10-21T04:00:00Z</Id>"
                + "</Activity></Activities></TrainingCenterDatabase>")
                .getBytes(StandardCharsets.UTF_8);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
            digest.update(bytes);
        }
        return new TcxUploadArtifact(
                "artifact-alpn",
                1,
                file,
                "activity.tcx",
                bytes.length,
                hex(digest.digest()));
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(
                    java.util.Locale.ROOT,
                    "%02x",
                    value));
        }
        return result.toString();
    }

    private static void deleteRecursively(File file) throws Exception {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!file.delete()) {
            throw new IllegalStateException(
                    "Could not delete test file");
        }
    }

    private static final class ProtocolCapture extends EventListener {
        private final List<Protocol> values =
                new CopyOnWriteArrayList<>();

        @Override
        public void connectionAcquired(
                Call call,
                Connection connection) {
            values.add(connection.protocol());
        }

        private void assertOnlyHttp11(int minimumConnections) {
            assertTrue(values.size() >= minimumConnections);
            for (Protocol protocol : values) {
                assertEquals(Protocol.HTTP_1_1, protocol);
            }
        }
    }
}
