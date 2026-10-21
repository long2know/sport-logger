package com.long2know.sportlogger.connector.endurain;

import com.long2know.sportlogger.security.EndurainApiKey;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;

public final class EndurainHttpsTransport {
    public static final long DEFAULT_MAX_TCX_BYTES = 200L * 1024L * 1024L;
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 1024 * 1024;

    private static final int COPY_BUFFER_BYTES = 16 * 1024;
    private static final String JSON_MEDIA_TYPE = "application/json";
    private static final String TCX_MEDIA_TYPE = "application/vnd.garmin.tcx+xml";

    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final long maxTcxBytes;
    private final int maxResponseBytes;
    private final EndurainRetryPolicy retryPolicy;
    private final Clock clock;
    private final SSLSocketFactory testSocketFactory;

    public EndurainHttpsTransport() {
        this(
                Duration.ofSeconds(15),
                Duration.ofSeconds(30),
                DEFAULT_MAX_TCX_BYTES,
                DEFAULT_MAX_RESPONSE_BYTES,
                EndurainRetryPolicy.defaults(),
                Clock.systemUTC(),
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
                maxTcxBytes,
                maxResponseBytes,
                EndurainRetryPolicy.defaults(),
                Clock.systemUTC(),
                null);
    }

    EndurainHttpsTransport(
            Duration connectTimeout,
            Duration readTimeout,
            long maxTcxBytes,
            int maxResponseBytes,
            EndurainRetryPolicy retryPolicy,
            Clock clock,
            SSLSocketFactory testSocketFactory) {
        connectTimeoutMillis = timeoutMillis(connectTimeout, "connectTimeout");
        readTimeoutMillis = timeoutMillis(readTimeout, "readTimeout");
        if (maxTcxBytes < 1 || maxTcxBytes > DEFAULT_MAX_TCX_BYTES) {
            throw new IllegalArgumentException(
                    "Maximum TCX size is outside the supported bound");
        }
        if (maxResponseBytes < 1
                || maxResponseBytes > DEFAULT_MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException(
                    "Maximum response size is outside the supported bound");
        }
        this.maxTcxBytes = maxTcxBytes;
        this.maxResponseBytes = maxResponseBytes;
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.testSocketFactory = testSocketFactory;
    }

    public EndurainProbeResult probe(EndurainEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        HttpsURLConnection connection = null;
        try {
            connection = open(endpoint.aboutUri());
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", JSON_MEDIA_TYPE);
            connection.connect();
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_OK) {
                if (!isJson(connection.getContentType())) {
                    return EndurainProbeResult.of(
                            EndurainProbeResult.State.INCOMPATIBLE_RESPONSE,
                            status);
                }
                byte[] response = readBounded(
                        connection.getInputStream(),
                        connection.getContentLengthLong());
                EndurainResponseParser.About about =
                        EndurainResponseParser.parseAbout(response);
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
                                connection.getHeaderField("Retry-After"),
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
        } catch (SSLException error) {
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
            if (connection != null) {
                connection.disconnect();
            }
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

        HttpsURLConnection connection = null;
        boolean transmissionMayHaveStarted = false;
        try {
            connection = open(config.endpoint().uploadUri());
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", JSON_MEDIA_TYPE);
            connection.setRequestProperty(
                    "Content-Type",
                    "multipart/form-data; boundary=" + boundary);
            connection.setRequestProperty("Connection", "close");
            apiKey.applyTo(connection);
            connection.setFixedLengthStreamingMode(contentLength);
            connection.connect();

            transmissionMayHaveStarted = true;
            MessageDigest transmittedDigest = sha256();
            long transmittedBytes = 0;
            try (OutputStream output = connection.getOutputStream()) {
                output.write(preamble);
                byte[] buffer = new byte[COPY_BUFFER_BYTES];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    transmittedDigest.update(buffer, 0, read);
                    transmittedBytes += read;
                    if (transmittedBytes > artifact.byteLength()) {
                        throw new IOException("TCX artifact changed during upload");
                    }
                }
                output.write(epilogue);
                output.flush();
            }
            if (transmittedBytes != artifact.byteLength()
                    || !MessageDigest.isEqual(
                            transmittedDigest.digest(),
                            decodeSha256(artifact.sha256()))) {
                return EndurainUploadResult.failure(
                        EndurainUploadResult.State.UNKNOWN_DELIVERY,
                        EndurainUploadResult.Reason.POST_TRANSMISSION_NETWORK_FAILURE,
                        null);
            }

            return classifyResponse(config, connection, connection.getResponseCode());
        } catch (SSLException error) {
            if (transmissionMayHaveStarted) {
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
            if (transmissionMayHaveStarted) {
                EndurainUploadResult response = classifyResponseIfAvailable(
                        config,
                        connection);
                if (response != null) {
                    return response;
                }
                return EndurainUploadResult.failure(
                        EndurainUploadResult.State.UNKNOWN_DELIVERY,
                        EndurainUploadResult.Reason.POST_TRANSMISSION_NETWORK_FAILURE,
                        null);
            }
            return EndurainUploadResult.failure(
                    EndurainUploadResult.State.RETRYABLE,
                    EndurainUploadResult.Reason.PRE_TRANSMISSION_NETWORK_FAILURE,
                    null);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private EndurainUploadResult classifyResponseIfAvailable(
            EndurainConnectionConfig config,
            HttpsURLConnection connection) {
        if (connection == null) {
            return null;
        }
        try {
            return classifyResponse(config, connection, connection.getResponseCode());
        } catch (IOException error) {
            return null;
        }
    }

    private EndurainUploadResult classifyResponse(
            EndurainConnectionConfig config,
            HttpsURLConnection connection,
            int status) {
        if (status == HttpURLConnection.HTTP_CREATED) {
            return parseCreatedResponse(config, connection, status);
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
            return EndurainUploadResult.rateLimited(
                    status,
                    retryPolicy.rateLimitDelay(
                            connection.getHeaderField("Retry-After"),
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
            HttpsURLConnection connection,
            int status) {
        try {
            if (!isJson(connection.getContentType())) {
                return EndurainUploadResult.failure(
                        EndurainUploadResult.State.UNKNOWN_DELIVERY,
                        EndurainUploadResult.Reason.MALFORMED_SUCCESS_RESPONSE,
                        status);
            }
            byte[] body = readBounded(
                    connection.getInputStream(),
                    connection.getContentLengthLong());
            List<Long> ids = EndurainResponseParser.parseActivityIds(body);
            List<EndurainRemoteActivityId> remoteActivities =
                    new ArrayList<>(ids.size());
            for (long id : ids) {
                remoteActivities.add(config.remoteActivity(id));
            }
            return EndurainUploadResult.delivered(remoteActivities);
        } catch (IOException
                | EndurainResponseParser.ParseException
                | ResponseTooLargeException error) {
            return EndurainUploadResult.failure(
                    EndurainUploadResult.State.UNKNOWN_DELIVERY,
                    EndurainUploadResult.Reason.MALFORMED_SUCCESS_RESPONSE,
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

    private HttpsURLConnection open(URI uri) throws IOException {
        URL url = uri.toURL();
        java.net.URLConnection opened = url.openConnection();
        if (!(opened instanceof HttpsURLConnection)) {
            throw new IOException("Endpoint did not create an HTTPS connection");
        }
        HttpsURLConnection connection = (HttpsURLConnection) opened;
        connection.setConnectTimeout(connectTimeoutMillis);
        connection.setReadTimeout(readTimeoutMillis);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        if (testSocketFactory != null) {
            connection.setSSLSocketFactory(testSocketFactory);
        }
        return connection;
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
        try (InputStream response = input) {
            int read;
            while ((read = response.read(buffer)) != -1) {
                total += read;
                if (total > maxResponseBytes) {
                    throw new ResponseTooLargeException();
                }
                output.write(buffer, 0, read);
            }
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

    private static final class ResponseTooLargeException extends Exception {
    }
}
