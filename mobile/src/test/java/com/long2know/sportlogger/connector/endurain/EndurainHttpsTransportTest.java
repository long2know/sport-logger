package com.long2know.sportlogger.connector.endurain;

import com.long2know.sportlogger.security.EndurainApiKey;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class EndurainHttpsTransportTest {
    private static final Instant NOW = Instant.parse("2026-10-21T04:00:00Z");

    private static File workDirectory;
    private static RuntimeTlsMaterial tls;
    private static EndurainTlsFixture fixture;
    private static TcxUploadArtifact artifact;

    @BeforeClass
    public static void setUpClass() throws Exception {
        workDirectory = new File(
                "build/test-data/endurain-https-" + System.nanoTime());
        tls = RuntimeTlsMaterial.create(new File(workDirectory, "tls"));
        fixture = new EndurainTlsFixture(tls);
        artifact = createArtifact(new File(workDirectory, "activity.tcx"));
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
        if (fixture != null) {
            fixture.close();
        }
        if (tls != null) {
            tls.close();
        }
        deleteRecursively(workDirectory);
    }

    @Test
    public void probeUsesCanonicalAboutEndpointWithoutSendingCredential() {
        int before = fixture.aboutRequests();

        EndurainProbeResult result = transport(1024).probe(endpoint());

        assertEquals(EndurainProbeResult.State.AVAILABLE, result.state());
        assertEquals("v0.19.0", result.apiVersion());
        assertTrue(result.isCanonicalV0190());
        assertEquals(before + 1, fixture.aboutRequests());
    }

    @Test
    public void createdListDeliversEveryScopedRemoteIntegerId() {
        fixture.mode(EndurainTlsFixture.Mode.SUCCESS_MULTIPLE);

        EndurainUploadResult result = upload(transport(1024), config());

        assertEquals(EndurainUploadResult.State.DELIVERED, result.state());
        assertEquals(2, result.remoteActivities().size());
        assertEquals(41, result.remoteActivities().get(0).activityId());
        assertEquals(42, result.remoteActivities().get(1).activityId());
        assertEquals(
                fixture.baseUrl(),
                result.remoteActivities().get(0).normalizedBaseUrl());
        assertEquals(
                "connection-test",
                result.remoteActivities().get(0).connectionIdentity());
        assertTrue(fixture.uploadHadApiKey());
        assertTrue(fixture.multipartWasFaithful());
    }

    @Test
    public void wrongScopeAndInvalidTcxAreDefinitiveFailures() {
        fixture.mode(EndurainTlsFixture.Mode.AUTH_FAILURE);
        EndurainUploadResult auth = upload(transport(1024), config());
        assertEquals(
                EndurainUploadResult.State.AUTHENTICATION_REQUIRED,
                auth.state());
        assertFalse(auth.mayRetryAutomatically());

        fixture.mode(EndurainTlsFixture.Mode.INVALID_TCX);
        EndurainUploadResult invalid = upload(transport(1024), config());
        assertEquals(
                EndurainUploadResult.State.VALIDATION_REJECTED,
                invalid.state());
        assertFalse(invalid.mayRetryAutomatically());
    }

    @Test
    public void rateLimitHonorsRetryAfterWithoutAutomaticRetry() {
        fixture.mode(EndurainTlsFixture.Mode.RATE_LIMITED);
        int before = fixture.uploadRequests();

        EndurainUploadResult result = upload(transport(1024), config());

        assertEquals(EndurainUploadResult.State.RATE_LIMITED, result.state());
        assertEquals(Duration.ofSeconds(120), result.retryAfter());
        assertEquals(before + 1, fixture.uploadRequests());
        assertTrue(result.mayRetryAutomatically());
    }

    @Test
    public void connectionFailureBeforeTransmissionIsRetryable() throws Exception {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }
        EndurainConnectionConfig unavailable = new EndurainConnectionConfig(
                EndurainEndpoint.parse("https://localhost:" + unusedPort),
                "connection-unavailable");

        EndurainUploadResult result = upload(transport(1024), unavailable);

        assertEquals(EndurainUploadResult.State.RETRYABLE, result.state());
        assertEquals(
                EndurainUploadResult.Reason.PRE_TRANSMISSION_NETWORK_FAILURE,
                result.reason());
        assertTrue(result.mayRetryAutomatically());
    }

    @Test
    public void droppedResponseAfterBodyBecomesUnknownDelivery() {
        fixture.mode(EndurainTlsFixture.Mode.DROP_AFTER_TRANSMISSION);

        EndurainUploadResult result = upload(transport(1024), config());

        assertEquals(
                EndurainUploadResult.State.UNKNOWN_DELIVERY,
                result.state());
        assertTrue(result.requiresManualReconciliation());
        assertFalse(result.mayRetryAutomatically());
        assertTrue(fixture.multipartWasFaithful());
    }

    @Test
    public void hostAndSchemeRedirectsAreRejectedWithoutForwardingCredential() {
        fixture.mode(EndurainTlsFixture.Mode.REDIRECT_HOST);
        EndurainUploadResult host = upload(transport(1024), config());
        assertEquals(EndurainUploadResult.Reason.REDIRECT_REJECTED, host.reason());
        assertFalse(fixture.redirectTargetHit());
        assertFalse(fixture.redirectTargetHadApiKey());

        fixture.mode(EndurainTlsFixture.Mode.REDIRECT_SCHEME);
        EndurainUploadResult scheme = upload(transport(1024), config());
        assertEquals(EndurainUploadResult.Reason.REDIRECT_REJECTED, scheme.reason());
        assertFalse(fixture.redirectTargetHit());
        assertFalse(fixture.redirectTargetHadApiKey());
    }

    @Test
    public void invalidEmptyAndOversizedCreatedBodiesNeverReportDelivered() {
        fixture.mode(EndurainTlsFixture.Mode.EMPTY_CREATED_LIST);
        assertMalformedCreated(upload(transport(1024), config()));

        fixture.mode(EndurainTlsFixture.Mode.MALFORMED_CREATED_LIST);
        assertMalformedCreated(upload(transport(1024), config()));

        fixture.mode(EndurainTlsFixture.Mode.OVERSIZED_CREATED_LIST);
        assertMalformedCreated(upload(transport(128), config()));
    }

    @Test
    public void serverFailureAfterTransmissionIsUnknownNotBlindlyRetryable() {
        fixture.mode(EndurainTlsFixture.Mode.SERVER_FAILURE);

        EndurainUploadResult result = upload(transport(1024), config());

        assertEquals(
                EndurainUploadResult.State.UNKNOWN_DELIVERY,
                result.state());
        assertEquals(503, result.httpStatus().intValue());
        assertFalse(result.mayRetryAutomatically());
    }

    @Test
    public void defaultTrustRejectsFixtureAndPinnedCaStillVerifiesHostname() {
        EndurainProbeResult platformTrust =
                new EndurainHttpsTransport(
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(3),
                        EndurainHttpsTransport.DEFAULT_MAX_TCX_BYTES,
                        1024)
                        .probe(endpoint());
        assertEquals(
                EndurainProbeResult.State.TLS_VALIDATION_FAILED,
                platformTrust.state());

        EndurainProbeResult hostnameMismatch =
                transport(1024).probe(
                        EndurainEndpoint.parse(fixture.hostMismatchBaseUrl()));
        assertEquals(
                EndurainProbeResult.State.TLS_VALIDATION_FAILED,
                hostnameMismatch.state());
    }

    @Test
    public void localLengthOrHashMismatchIsRejectedBeforeNetworkIo()
            throws Exception {
        int before = fixture.uploadRequests();
        TcxUploadArtifact invalid = new TcxUploadArtifact(
                "artifact-invalid",
                1,
                artifact.file(),
                "activity.tcx",
                artifact.byteLength(),
                repeat('0', 64));
        EndurainApiKey key = EndurainApiKey.from("fixture-key".toCharArray());
        EndurainUploadResult result;
        try {
            result = transport(1024).upload(config(), invalid, key);
        } finally {
            key.close();
        }

        assertEquals(
                EndurainUploadResult.State.VALIDATION_REJECTED,
                result.state());
        assertEquals(before, fixture.uploadRequests());
        assertNull(result.httpStatus());
    }

    private static void assertMalformedCreated(EndurainUploadResult result) {
        assertEquals(
                EndurainUploadResult.State.UNKNOWN_DELIVERY,
                result.state());
        assertEquals(
                EndurainUploadResult.Reason.MALFORMED_SUCCESS_RESPONSE,
                result.reason());
        assertTrue(result.remoteActivities().isEmpty());
        assertTrue(result.requiresManualReconciliation());
    }

    private static EndurainEndpoint endpoint() {
        return EndurainEndpoint.parse(fixture.baseUrl());
    }

    private static EndurainConnectionConfig config() {
        return new EndurainConnectionConfig(endpoint(), "connection-test");
    }

    private static EndurainHttpsTransport transport(int maxResponseBytes) {
        return new EndurainHttpsTransport(
                Duration.ofSeconds(3),
                Duration.ofSeconds(3),
                EndurainHttpsTransport.DEFAULT_MAX_TCX_BYTES,
                maxResponseBytes,
                EndurainRetryPolicy.defaults(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                tls.pinnedCaSocketFactory());
    }

    private static EndurainUploadResult upload(
            EndurainHttpsTransport transport,
            EndurainConnectionConfig config) {
        EndurainApiKey key = EndurainApiKey.from("fixture-key".toCharArray());
        try {
            return transport.upload(config, artifact, key);
        } finally {
            key.close();
        }
    }

    private static TcxUploadArtifact createArtifact(File file) throws Exception {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Could not create artifact directory");
        }
        byte[] bytes = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<TrainingCenterDatabase xmlns=\"http://www.garmin.com/"
                + "xmlschemas/TrainingCenterDatabase/v2\">"
                + "<Activities><Activity Sport=\"Running\">"
                + "<Id>2026-10-21T04:00:00Z</Id>"
                + "</Activity></Activities></TrainingCenterDatabase>")
                .getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
        return new TcxUploadArtifact(
                "artifact-test",
                7,
                file,
                "activity.tcx",
                bytes.length,
                hex(MessageDigest.getInstance("SHA-256").digest(bytes)));
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

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            result.append(value);
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
            throw new IllegalStateException("Could not delete test file");
        }
    }
}
