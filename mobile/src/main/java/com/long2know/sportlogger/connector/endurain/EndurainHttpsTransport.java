package com.long2know.sportlogger.connector.endurain;

import com.long2know.sportlogger.security.EndurainApiKey;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import javax.net.SocketFactory;

import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;

public final class EndurainHttpsTransport {
    public static final long DEFAULT_MAX_TCX_BYTES = 200L * 1024L * 1024L;
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 1024 * 1024;

    private static final int COPY_BUFFER_BYTES = 16 * 1024;
    private static final String JSON_MEDIA_TYPE = "application/json";
    private static final String TCX_MEDIA_TYPE = "application/vnd.garmin.tcx+xml";
    private static final ScheduledThreadPoolExecutor DEADLINE_EXECUTOR =
            createDeadlineExecutor();

    private final int writeTimeoutMillis;
    private final int overallTimeoutMillis;
    private final long maxTcxBytes;
    private final int maxResponseBytes;
    private final EndurainRetryPolicy retryPolicy;
    private final Clock clock;
    private final OkHttpClient client;
    private final TrackingSocketFactory rawSocketFactory;

    public EndurainHttpsTransport() {
        this(
                Duration.ofSeconds(15),
                Duration.ofSeconds(30),
                Duration.ofSeconds(30),
                Duration.ofSeconds(75),
                DEFAULT_MAX_TCX_BYTES,
                DEFAULT_MAX_RESPONSE_BYTES,
                EndurainRetryPolicy.defaults(),
                Clock.systemUTC(),
                null,
                null);
    }

    public EndurainHttpsTransport(
            Duration connectTimeout,
            Duration readTimeout,
            long maxTcxBytes,
            int maxResponseBytes) {
        this(
                connectTimeout,
                readTimeout,
                readTimeout,
                derivedOverallTimeout(connectTimeout, readTimeout),
                maxTcxBytes,
                maxResponseBytes,
                EndurainRetryPolicy.defaults(),
                Clock.systemUTC(),
                null,
                null);
    }

    EndurainHttpsTransport(
            Duration connectTimeout,
            Duration readTimeout,
            long maxTcxBytes,
            int maxResponseBytes,
            EndurainRetryPolicy retryPolicy,
            Clock clock,
            SSLSocketFactory testSocketFactory,
            X509TrustManager testTrustManager) {
        this(
                connectTimeout,
                readTimeout,
                readTimeout,
                derivedOverallTimeout(connectTimeout, readTimeout),
                maxTcxBytes,
                maxResponseBytes,
                retryPolicy,
                clock,
                testSocketFactory,
                testTrustManager);
    }

    EndurainHttpsTransport(
            Duration connectTimeout,
            Duration readTimeout,
            Duration writeTimeout,
            Duration overallTimeout,
            long maxTcxBytes,
            int maxResponseBytes,
            EndurainRetryPolicy retryPolicy,
            Clock clock,
            SSLSocketFactory testSocketFactory,
            X509TrustManager testTrustManager) {
        int connectTimeoutMillis = timeoutMillis(connectTimeout, "connectTimeout");
        int readTimeoutMillis = timeoutMillis(readTimeout, "readTimeout");
        writeTimeoutMillis = timeoutMillis(writeTimeout, "writeTimeout");
        overallTimeoutMillis = timeoutMillis(overallTimeout, "overallTimeout");
        if (maxTcxBytes < 1 || maxTcxBytes > DEFAULT_MAX_TCX_BYTES) {
            throw new IllegalArgumentException(
                    "Maximum TCX size is outside the supported bound");
        }
        if (maxResponseBytes < 1
                || maxResponseBytes > DEFAULT_MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException(
                    "Maximum response size is outside the supported bound");
        }
        if ((testSocketFactory == null) != (testTrustManager == null)) {
            throw new IllegalArgumentException(
                    "Test TLS socket factory and trust manager must be supplied together");
        }
        this.maxTcxBytes = maxTcxBytes;
        this.maxResponseBytes = maxResponseBytes;
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");

        rawSocketFactory = new TrackingSocketFactory(SocketFactory.getDefault());
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectionPool(new ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
                .socketFactory(rawSocketFactory);
        if (testSocketFactory != null) {
            builder.sslSocketFactory(testSocketFactory, testTrustManager);
        }
        client = builder.build();
    }

    public EndurainProbeResult probe(EndurainEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        Request request = new Request.Builder()
                .url(endpoint.aboutUri().toString())
                .get()
                .header("Accept", JSON_MEDIA_TYPE)
                .header("Connection", "close")
                .build();
        DeadlineController deadline =
                rawSocketFactory.begin(overallTimeoutMillis);
        try {
            try (Response response = client.newCall(request).execute()) {
                int status = response.code();
                if (status == HttpURLConnection.HTTP_OK) {
                    if (!isJson(response.header("Content-Type"))) {
                        return EndurainProbeResult.of(
                                EndurainProbeResult.State.INCOMPATIBLE_RESPONSE,
                                status);
                    }
                    ResponseBody body = response.body();
                    if (body == null) {
                        return EndurainProbeResult.of(
                                EndurainProbeResult.State.INCOMPATIBLE_RESPONSE,
                                status);
                    }
                    byte[] responseBytes = readBounded(
                            body.byteStream(),
                            body.contentLength());
                    EndurainResponseParser.About about =
                            EndurainResponseParser.parseAbout(responseBytes);
                    if (!"Endurain API".equals(about.name())) {
                        return EndurainProbeResult.of(
                                EndurainProbeResult.State.INCOMPATIBLE_RESPONSE,
                                status);
                    }
                    return EndurainProbeResult.available(about.version());
                }
                if (isRedirect(status)) {
                    return EndurainProbeResult.of(
                            EndurainProbeResult.State.REDIRECT_REJECTED,
                            status);
                }
                if (status == 429) {
                    return EndurainProbeResult.rateLimited(
                            status,
                            retryPolicy.rateLimitDelay(
                                    response.header("Retry-After"),
                                    clock.instant()));
                }
                if (status >= 500 || status == 408 || status == 425) {
                    return EndurainProbeResult.of(
                            EndurainProbeResult.State.RETRYABLE,
                            status);
                }
                return EndurainProbeResult.of(
                        EndurainProbeResult.State.PERMANENT_FAILURE,
                        status);
            }
        } catch (SSLException error) {
            if (deadline.expired()) {
                return EndurainProbeResult.of(
                        EndurainProbeResult.State.RETRYABLE,
                        null);
            }
            return EndurainProbeResult.of(
                    EndurainProbeResult.State.TLS_VALIDATION_FAILED,
                    null);
        } catch (EndurainResponseParser.ParseException | ResponseTooLargeException error) {
            return EndurainProbeResult.of(
                    EndurainProbeResult.State.INCOMPATIBLE_RESPONSE,
                    HttpURLConnection.HTTP_OK);
        } catch (IOException error) {
            return EndurainProbeResult.of(
                    EndurainProbeResult.State.RETRYABLE,
                    null);
        } finally {
            rawSocketFactory.end(deadline);
        }
    }

    public EndurainUploadResult upload(
            EndurainConnectionConfig config,
            TcxUploadArtifact artifact,
            EndurainApiKey apiKey) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(apiKey, "apiKey");

        try (FileInputStream input = new FileInputStream(artifact.file())) {
            if (!verifyArtifact(input, artifact)) {
                return EndurainUploadResult.failure(
                        EndurainUploadResult.State.VALIDATION_REJECTED,
                        EndurainUploadResult.Reason.INVALID_LOCAL_ARTIFACT,
                        null);
            }
            input.getChannel().position(0);
            return uploadVerified(config, artifact, apiKey, input);
        } catch (IOException | SecurityException error) {
            return EndurainUploadResult.failure(
                    EndurainUploadResult.State.VALIDATION_REJECTED,
                    EndurainUploadResult.Reason.INVALID_LOCAL_ARTIFACT,
                    null);
        }
    }

    private EndurainUploadResult uploadVerified(
            EndurainConnectionConfig config,
            TcxUploadArtifact artifact,
            EndurainApiKey apiKey,
            FileInputStream input) {
        String boundary = "sportlogger-" + UUID.randomUUID().toString().replace("-", "");
        byte[] preamble = multipartPreamble(boundary, artifact.uploadFilename());
        byte[] epilogue = ("\r\n--" + boundary + "--\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        long contentLength;
        try {
            contentLength = Math.addExact(
                    Math.addExact((long) preamble.length, artifact.byteLength()),
                    (long) epilogue.length);
        } catch (ArithmeticException error) {
            return EndurainUploadResult.failure(
                    EndurainUploadResult.State.VALIDATION_REJECTED,
                    EndurainUploadResult.Reason.INVALID_LOCAL_ARTIFACT,
                    null);
        }

        AtomicReference<TransmissionPhase> phase =
                new AtomicReference<>(TransmissionPhase.PRE_TRANSMISSION);
        DeadlineController deadline =
                rawSocketFactory.begin(overallTimeoutMillis);
        try {
            RequestBody body = new StreamingMultipartBody(
                    boundary,
                    preamble,
                    epilogue,
                    contentLength,
                    artifact,
                    input,
                    phase,
                    deadline,
                    writeTimeoutMillis);
            Request.Builder request = new Request.Builder()
                    .url(config.endpoint().uploadUri().toString())
                    .post(body)
                    .header("Accept", JSON_MEDIA_TYPE)
                    .header("Connection", "close");
            apiKey.applyTo(request);

            try (Response response = client.newCall(request.build()).execute()) {
                return classifyResponse(config, response, deadline);
            }
        } catch (SSLException error) {
            if (deadline.expired()) {
                return classifyNetworkFailure(error, phase.get(), deadline);
            }
            if (phase.get() != TransmissionPhase.PRE_TRANSMISSION) {
                return EndurainUploadResult.failure(
                        EndurainUploadResult.State.UNKNOWN_DELIVERY,
                        EndurainUploadResult.Reason.POST_TRANSMISSION_NETWORK_FAILURE,
                        null);
            }
            return EndurainUploadResult.failure(
                    EndurainUploadResult.State.PERMANENT_FAILURE,
                    EndurainUploadResult.Reason.TLS_VALIDATION_FAILED,
                    null);
        } catch (IOException error) {
            return classifyNetworkFailure(error, phase.get(), deadline);
        } finally {
            rawSocketFactory.end(deadline);
        }
    }

    private EndurainUploadResult classifyNetworkFailure(
            IOException error,
            TransmissionPhase phase,
            DeadlineController deadline) {
        DeadlineExpiration expiration = deadline.expiration();
        boolean timedOut = expiration != DeadlineExpiration.NONE
                || isTimeout(error);
        if (phase == TransmissionPhase.PRE_TRANSMISSION) {
            return EndurainUploadResult.failure(
                    EndurainUploadResult.State.RETRYABLE,
                    timedOut
                            ? EndurainUploadResult.Reason.PRE_TRANSMISSION_TIMEOUT
                            : EndurainUploadResult.Reason.PRE_TRANSMISSION_NETWORK_FAILURE,
                    null);
        }
        EndurainUploadResult.Reason reason;
        if (expiration == DeadlineExpiration.WRITE) {
            reason = EndurainUploadResult.Reason.POST_TRANSMISSION_WRITE_TIMEOUT;
        } else if (timedOut) {
            reason = EndurainUploadResult.Reason.POST_TRANSMISSION_TIMEOUT;
        } else {
            reason = EndurainUploadResult.Reason.POST_TRANSMISSION_NETWORK_FAILURE;
        }
        return EndurainUploadResult.failure(
                EndurainUploadResult.State.UNKNOWN_DELIVERY,
                reason,
                null);
    }

    private EndurainUploadResult classifyResponse(
            EndurainConnectionConfig config,
            Response response,
            DeadlineController deadline) {
        int status = response.code();
        if (status == HttpURLConnection.HTTP_CREATED) {
            return parseCreatedResponse(config, response, status, deadline);
        }
        if (status == HttpURLConnection.HTTP_UNAUTHORIZED
                || status == HttpURLConnection.HTTP_FORBIDDEN) {
            return EndurainUploadResult.failure(
                    EndurainUploadResult.State.AUTHENTICATION_REQUIRED,
                    EndurainUploadResult.Reason.AUTHENTICATION_REJECTED,
                    status);
        }
        if (status == HttpURLConnection.HTTP_BAD_REQUEST
                || status == HttpURLConnection.HTTP_ENTITY_TOO_LARGE
                || status == HttpURLConnection.HTTP_UNSUPPORTED_TYPE
                || status == 422) {
            return EndurainUploadResult.failure(
                    EndurainUploadResult.State.VALIDATION_REJECTED,
                    EndurainUploadResult.Reason.SERVER_VALIDATION_REJECTED,
                    status);
        }
        if (status == 429) {
            return EndurainUploadResult.rateLimitedAfterTransmission(
                    status,
                    retryPolicy.rateLimitDelay(
                            response.header("Retry-After"),
                            clock.instant()));
        }
        if (isRedirect(status)) {
            return EndurainUploadResult.failure(
                    EndurainUploadResult.State.PERMANENT_FAILURE,
                    EndurainUploadResult.Reason.REDIRECT_REJECTED,
                    status);
        }
        if (status == HttpURLConnection.HTTP_NOT_FOUND
                || status == HttpURLConnection.HTTP_BAD_METHOD) {
            return EndurainUploadResult.failure(
                    EndurainUploadResult.State.PERMANENT_FAILURE,
                    EndurainUploadResult.Reason.ENDPOINT_NOT_SUPPORTED,
                    status);
        }
        if (status >= 500
                || status == 408
                || status == 425
                || (status >= 200 && status < 300)) {
            return EndurainUploadResult.failure(
                    EndurainUploadResult.State.UNKNOWN_DELIVERY,
                    EndurainUploadResult.Reason.UNCERTAIN_SERVER_FAILURE,
                    status);
        }
        return EndurainUploadResult.failure(
                EndurainUploadResult.State.PERMANENT_FAILURE,
                EndurainUploadResult.Reason.PERMANENT_SERVER_REJECTION,
                status);
    }

    private EndurainUploadResult parseCreatedResponse(
            EndurainConnectionConfig config,
            Response response,
            int status,
            DeadlineController deadline) {
        try {
            if (!isJson(response.header("Content-Type"))) {
                return EndurainUploadResult.failure(
                        EndurainUploadResult.State.UNKNOWN_DELIVERY,
                        EndurainUploadResult.Reason.MALFORMED_SUCCESS_RESPONSE,
                        status);
            }
            ResponseBody body = response.body();
            if (body == null) {
                return EndurainUploadResult.failure(
                        EndurainUploadResult.State.UNKNOWN_DELIVERY,
                        EndurainUploadResult.Reason.MALFORMED_SUCCESS_RESPONSE,
                        status);
            }
            byte[] responseBytes = readBounded(
                    body.byteStream(),
                    body.contentLength());
            List<Long> ids = EndurainResponseParser.parseActivityIds(responseBytes);
            List<EndurainRemoteActivityId> remoteActivities =
                    new ArrayList<>(ids.size());
            for (long id : ids) {
                remoteActivities.add(config.remoteActivity(id));
            }
            return EndurainUploadResult.delivered(remoteActivities);
        } catch (IOException
                | EndurainResponseParser.ParseException
                | ResponseTooLargeException error) {
            DeadlineExpiration expiration = deadline.expiration();
            return EndurainUploadResult.failure(
                    EndurainUploadResult.State.UNKNOWN_DELIVERY,
                    expiration == DeadlineExpiration.WRITE
                            ? EndurainUploadResult.Reason.POST_TRANSMISSION_WRITE_TIMEOUT
                            : expiration == DeadlineExpiration.OVERALL
                            || isTimeout(error)
                            ? EndurainUploadResult.Reason.POST_TRANSMISSION_TIMEOUT
                            : EndurainUploadResult.Reason.MALFORMED_SUCCESS_RESPONSE,
                    status);
        }
    }

    private boolean verifyArtifact(
            FileInputStream input,
            TcxUploadArtifact artifact) throws IOException {
        if (!artifact.file().isFile()
                || artifact.byteLength() > maxTcxBytes
                || artifact.file().length() != artifact.byteLength()) {
            return false;
        }
        MessageDigest digest = sha256();
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long length = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            length += read;
            if (length > maxTcxBytes || length > artifact.byteLength()) {
                return false;
            }
            digest.update(buffer, 0, read);
        }
        return length == artifact.byteLength()
                && MessageDigest.isEqual(
                        digest.digest(),
                        decodeSha256(artifact.sha256()));
    }

    private static byte[] multipartPreamble(String boundary, String filename) {
        String value = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\""
                + filename
                + "\"\r\n"
                + "Content-Type: "
                + TCX_MEDIA_TYPE
                + "\r\n\r\n";
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private byte[] readBounded(InputStream input, long contentLength)
            throws IOException, ResponseTooLargeException {
        if (contentLength > maxResponseBytes) {
            throw new ResponseTooLargeException();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                contentLength > 0
                        ? (int) Math.min(contentLength, maxResponseBytes)
                        : Math.min(4096, maxResponseBytes));
        byte[] buffer = new byte[Math.min(COPY_BUFFER_BYTES, maxResponseBytes)];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxResponseBytes) {
                throw new ResponseTooLargeException();
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        int parameter = contentType.indexOf(';');
        String mediaType = parameter >= 0
                ? contentType.substring(0, parameter)
                : contentType;
        return JSON_MEDIA_TYPE.equalsIgnoreCase(mediaType.trim());
    }

    private static boolean isRedirect(int status) {
        return status >= 300 && status < 400;
    }

    private static boolean isTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof InterruptedIOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static byte[] decodeSha256(String value) {
        byte[] decoded = new byte[value.length() / 2];
        for (int index = 0; index < decoded.length; index++) {
            int offset = index * 2;
            decoded[index] = (byte) Integer.parseInt(
                    value.substring(offset, offset + 2),
                    16);
        }
        return decoded;
    }

    private static int timeoutMillis(Duration timeout, String name) {
        Objects.requireNonNull(timeout, name);
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        long millis = timeout.toMillis();
        if (millis < 1 || millis > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " is outside the supported range");
        }
        return (int) millis;
    }

    private static Duration derivedOverallTimeout(
            Duration connectTimeout,
            Duration readTimeout) {
        int connectMillis = timeoutMillis(connectTimeout, "connectTimeout");
        int readMillis = timeoutMillis(readTimeout, "readTimeout");
        long combined = (long) connectMillis + (2L * readMillis);
        return Duration.ofMillis(Math.min(combined, Integer.MAX_VALUE));
    }

    static int pendingDeadlineTasksForTests() {
        DEADLINE_EXECUTOR.purge();
        return DEADLINE_EXECUTOR.getQueue().size();
    }

    private static ScheduledThreadPoolExecutor createDeadlineExecutor() {
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "endurain-https-deadline");
            thread.setDaemon(true);
            return thread;
        };
        ScheduledThreadPoolExecutor executor =
                new ScheduledThreadPoolExecutor(1, threadFactory);
        executor.setRemoveOnCancelPolicy(true);
        executor.setKeepAliveTime(1, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private enum TransmissionPhase {
        PRE_TRANSMISSION,
        WRITE_STARTED,
        WRITE_COMPLETE
    }

    private enum DeadlineExpiration {
        NONE,
        WRITE,
        OVERALL
    }

    private static final class TrackingSocketFactory extends SocketFactory {
        private final SocketFactory delegate;
        private final ThreadLocal<DeadlineController> activeDeadline =
                new ThreadLocal<>();

        private TrackingSocketFactory(SocketFactory delegate) {
            this.delegate = delegate;
        }

        private DeadlineController begin(int overallTimeoutMillis) {
            if (activeDeadline.get() != null) {
                throw new IllegalStateException(
                        "Nested Endurain network operations are unsupported");
            }
            DeadlineController deadline =
                    new DeadlineController(overallTimeoutMillis);
            activeDeadline.set(deadline);
            return deadline;
        }

        private void end(DeadlineController deadline) {
            try {
                deadline.close();
            } finally {
                activeDeadline.remove();
            }
        }

        @Override
        public Socket createSocket() throws IOException {
            return track(delegate.createSocket());
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return track(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(
                String host,
                int port,
                InetAddress localHost,
                int localPort) throws IOException {
            return track(delegate.createSocket(
                    host,
                    port,
                    localHost,
                    localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port)
                throws IOException {
            return track(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(
                InetAddress address,
                int port,
                InetAddress localAddress,
                int localPort) throws IOException {
            return track(delegate.createSocket(
                    address,
                    port,
                    localAddress,
                    localPort));
        }

        private Socket track(Socket socket) {
            DeadlineController deadline = activeDeadline.get();
            if (deadline != null) {
                deadline.register(socket);
            }
            return socket;
        }
    }

    private static final class DeadlineController implements AutoCloseable {
        private final AtomicReference<DeadlineExpiration> expiration =
                new AtomicReference<>(DeadlineExpiration.NONE);
        private final AtomicReference<Socket> rawSocket = new AtomicReference<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final ScheduledFuture<?> overallDeadline;
        private volatile ScheduledFuture<?> writeDeadline;

        private DeadlineController(int overallTimeoutMillis) {
            overallDeadline = schedule(
                    DeadlineExpiration.OVERALL,
                    overallTimeoutMillis);
        }

        private void register(Socket socket) {
            Socket previous = rawSocket.getAndSet(socket);
            if (previous != null && previous != socket) {
                closeSocket(previous);
            }
            if (closed.get() || expired()) {
                closeSocket(socket);
            }
        }

        private void startWrite(int writeTimeoutMillis) {
            writeDeadline = schedule(
                    DeadlineExpiration.WRITE,
                    writeTimeoutMillis);
        }

        private void finishWrite() {
            cancel(writeDeadline);
            writeDeadline = null;
        }

        private boolean expired() {
            return expiration() != DeadlineExpiration.NONE;
        }

        private DeadlineExpiration expiration() {
            return expiration.get();
        }

        private ScheduledFuture<?> schedule(
                DeadlineExpiration value,
                int timeoutMillis) {
            return DEADLINE_EXECUTOR.schedule(
                    () -> expire(value),
                    timeoutMillis,
                    TimeUnit.MILLISECONDS);
        }

        private void expire(DeadlineExpiration value) {
            if (closed.get()
                    || !expiration.compareAndSet(DeadlineExpiration.NONE, value)) {
                return;
            }
            closeSocket(rawSocket.get());
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            cancel(writeDeadline);
            cancel(overallDeadline);
            rawSocket.set(null);
        }

        private static void cancel(ScheduledFuture<?> future) {
            if (future != null) {
                future.cancel(false);
            }
        }

        private static void closeSocket(Socket socket) {
            if (socket == null) {
                return;
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class StreamingMultipartBody extends RequestBody {
        private final MediaType contentType;
        private final byte[] preamble;
        private final byte[] epilogue;
        private final long contentLength;
        private final TcxUploadArtifact artifact;
        private final FileInputStream input;
        private final AtomicReference<TransmissionPhase> phase;
        private final DeadlineController deadline;
        private final int writeTimeoutMillis;

        private StreamingMultipartBody(
                String boundary,
                byte[] preamble,
                byte[] epilogue,
                long contentLength,
                TcxUploadArtifact artifact,
                FileInputStream input,
                AtomicReference<TransmissionPhase> phase,
                DeadlineController deadline,
                int writeTimeoutMillis) {
            this.contentType = MediaType.get(
                    "multipart/form-data; boundary=" + boundary);
            this.preamble = preamble;
            this.epilogue = epilogue;
            this.contentLength = contentLength;
            this.artifact = artifact;
            this.input = input;
            this.phase = phase;
            this.deadline = deadline;
            this.writeTimeoutMillis = writeTimeoutMillis;
        }

        @Override
        public MediaType contentType() {
            return contentType;
        }

        @Override
        public long contentLength() {
            return contentLength;
        }

        @Override
        public boolean isOneShot() {
            return true;
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            phase.set(TransmissionPhase.WRITE_STARTED);
            deadline.startWrite(writeTimeoutMillis);
            sink.write(preamble);
            MessageDigest transmittedDigest = EndurainHttpsTransport.sha256();
            long transmittedBytes = 0;
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            int read;
            while ((read = input.read(buffer)) != -1) {
                sink.write(buffer, 0, read);
                transmittedDigest.update(buffer, 0, read);
                transmittedBytes += read;
                if (transmittedBytes > artifact.byteLength()) {
                    throw new IOException("TCX artifact changed during upload");
                }
            }
            sink.write(epilogue);
            if (transmittedBytes != artifact.byteLength()
                    || !MessageDigest.isEqual(
                            transmittedDigest.digest(),
                            decodeSha256(artifact.sha256()))) {
                throw new IOException("TCX artifact changed during upload");
            }
            phase.set(TransmissionPhase.WRITE_COMPLETE);
            deadline.finishWrite();
        }
    }

    private static final class ResponseTooLargeException extends Exception {
    }
}
