package com.long2know.sportlogger.connector.endurain;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

final class EndurainTlsFixture implements AutoCloseable {
    enum Mode {
        SUCCESS_ONE,
        SUCCESS_MULTIPLE,
        AUTH_FAILURE,
        INVALID_TCX,
        RATE_LIMITED,
        DROP_AFTER_TRANSMISSION,
        REDIRECT_HOST,
        REDIRECT_SCHEME,
        EMPTY_CREATED_LIST,
        MALFORMED_CREATED_LIST,
        OVERSIZED_CREATED_LIST,
        SERVER_FAILURE
    }

    private static final int MAX_HEADER_LINE_BYTES = 8192;
    private static final int MAX_REQUEST_BODY_BYTES = 1024 * 1024;

    private final SSLServerSocket server;
    private final ExecutorService executor;
    private final AtomicReference<Mode> mode =
            new AtomicReference<>(Mode.SUCCESS_ONE);
    private final AtomicInteger uploadRequests = new AtomicInteger();
    private final AtomicInteger aboutRequests = new AtomicInteger();
    private final AtomicBoolean redirectTargetHit = new AtomicBoolean();
    private final AtomicBoolean redirectTargetHadApiKey = new AtomicBoolean();
    private final AtomicBoolean uploadHadApiKey = new AtomicBoolean();
    private final AtomicBoolean multipartWasFaithful = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    EndurainTlsFixture(RuntimeTlsMaterial material) throws Exception {
        server = (SSLServerSocket) material.serverContext()
                .getServerSocketFactory()
                .createServerSocket();
        server.bind(new InetSocketAddress(
                InetAddress.getByName("127.0.0.1"),
                0));
        executor = Executors.newCachedThreadPool();
        executor.execute(this::acceptLoop);
    }

    String baseUrl() {
        return "https://localhost:" + server.getLocalPort();
    }

    String hostMismatchBaseUrl() {
        return "https://127.0.0.1:" + server.getLocalPort();
    }

    void mode(Mode value) {
        assertHealthy();
        mode.set(value);
        redirectTargetHit.set(false);
        redirectTargetHadApiKey.set(false);
        uploadHadApiKey.set(false);
        multipartWasFaithful.set(false);
    }

    int uploadRequests() {
        assertHealthy();
        return uploadRequests.get();
    }

    int aboutRequests() {
        assertHealthy();
        return aboutRequests.get();
    }

    boolean redirectTargetHit() {
        assertHealthy();
        return redirectTargetHit.get();
    }

    boolean redirectTargetHadApiKey() {
        assertHealthy();
        return redirectTargetHadApiKey.get();
    }

    boolean uploadHadApiKey() {
        assertHealthy();
        return uploadHadApiKey.get();
    }

    boolean multipartWasFaithful() {
        assertHealthy();
        return multipartWasFaithful.get();
    }

    @Override
    public void close() throws IOException {
        closed.set(true);
        server.close();
        executor.shutdownNow();
        assertHealthy();
    }

    private void acceptLoop() {
        while (!closed.get()) {
            try {
                SSLSocket socket = (SSLSocket) server.accept();
                executor.execute(() -> handle(socket));
            } catch (SocketException error) {
                if (!closed.get()) {
                    failure.compareAndSet(null, error);
                }
                return;
            } catch (IOException error) {
                if (!closed.get()) {
                    failure.compareAndSet(null, error);
                }
                return;
            }
        }
    }

    private void handle(SSLSocket socket) {
        try (SSLSocket connection = socket) {
            connection.setSoTimeout(5000);
            connection.startHandshake();
            Request request = readRequest(connection.getInputStream());
            dispatch(request, connection.getOutputStream());
        } catch (SSLException | EOFException | SocketException expected) {
        } catch (IOException error) {
            if (!closed.get()) {
                failure.compareAndSet(null, error);
            }
        }
    }

    private void dispatch(Request request, OutputStream output) throws IOException {
        if ("/api/v1/about".equals(request.path)) {
            aboutRequests.incrementAndGet();
            boolean keyPresent = request.headers.containsKey("x-api-key");
            if (!"GET".equals(request.method) || keyPresent) {
                sendJson(output, 400, "{\"detail\":\"bad probe\"}");
                return;
            }
            sendJson(
                    output,
                    200,
                    "{\"name\":\"Endurain API\",\"version\":\"v0.19.0\","
                            + "\"license\":{\"name\":\"AGPL\","
                            + "\"identifier\":\"AGPL-3.0-or-later\","
                            + "\"url\":\"https://spdx.org/licenses/AGPL-3.0-or-later.html\"}}");
            return;
        }
        if ("/redirect-target".equals(request.path)) {
            redirectTargetHit.set(true);
            redirectTargetHadApiKey.set(
                    request.headers.containsKey("x-api-key"));
            sendJson(output, 200, "{\"unexpected\":true}");
            return;
        }
        if (!"/api/v1/activities/create/upload".equals(request.path)) {
            sendJson(output, 404, "{\"detail\":\"not found\"}");
            return;
        }

        uploadRequests.incrementAndGet();
        uploadHadApiKey.set(request.headers.containsKey("x-api-key"));
        String contentType = request.headers.get("content-type");
        String bodyText = new String(request.body, StandardCharsets.UTF_8);
        multipartWasFaithful.set(
                "POST".equals(request.method)
                        && contentType != null
                        && contentType.startsWith("multipart/form-data; boundary=")
                        && bodyText.contains("name=\"file\"")
                        && bodyText.contains("filename=\"activity.tcx\"")
                        && bodyText.contains("<TrainingCenterDatabase"));

        switch (mode.get()) {
            case SUCCESS_ONE:
                sendJson(
                        output,
                        201,
                        "[{\"id\":41,\"name\":\"Run\",\"distance\":1000,"
                                + "\"activity_type\":1}]");
                break;
            case SUCCESS_MULTIPLE:
                sendJson(
                        output,
                        201,
                        "[{\"id\":41,\"name\":\"Run\",\"distance\":1000,"
                                + "\"activity_type\":1},"
                                + "{\"id\":42,\"name\":\"Ride\",\"distance\":2000,"
                                + "\"activity_type\":4}]");
                break;
            case AUTH_FAILURE:
                sendJson(output, 401, "{\"detail\":\"invalid scope\"}");
                break;
            case INVALID_TCX:
                sendJson(output, 400, "{\"detail\":\"invalid TCX\"}");
                break;
            case RATE_LIMITED:
                send(
                        output,
                        429,
                        Map.of("Retry-After", "120"),
                        "{\"detail\":\"rate limit\"}");
                break;
            case DROP_AFTER_TRANSMISSION:
                break;
            case REDIRECT_HOST:
                send(
                        output,
                        307,
                        Map.of(
                                "Location",
                                hostMismatchBaseUrl() + "/redirect-target"),
                        "");
                break;
            case REDIRECT_SCHEME:
                send(
                        output,
                        307,
                        Map.of(
                                "Location",
                                "http://localhost:"
                                        + server.getLocalPort()
                                        + "/redirect-target"),
                        "");
                break;
            case EMPTY_CREATED_LIST:
                sendJson(output, 201, "[]");
                break;
            case MALFORMED_CREATED_LIST:
                sendJson(output, 201, "[{\"id\":\"not-an-integer\"}]");
                break;
            case OVERSIZED_CREATED_LIST:
                sendJson(
                        output,
                        201,
                        "[{\"id\":41,\"padding\":\""
                                + repeat('x', 4096)
                                + "\"}]");
                break;
            case SERVER_FAILURE:
                sendJson(output, 503, "{\"detail\":\"unavailable\"}");
                break;
            default:
                throw new AssertionError("Unhandled fixture mode");
        }
    }

    private Request readRequest(InputStream input) throws IOException {
        String requestLine = readLine(input);
        if (requestLine == null) {
            throw new EOFException("Connection closed before request line");
        }
        String[] parts = requestLine.split(" ", 3);
        if (parts.length != 3) {
            throw new IOException("Malformed HTTP request line");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        while (true) {
            String line = readLine(input);
            if (line == null) {
                throw new EOFException("Connection closed during headers");
            }
            if (line.isEmpty()) {
                break;
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw new IOException("Malformed HTTP header");
            }
            headers.put(
                    line.substring(0, separator).trim().toLowerCase(Locale.ROOT),
                    line.substring(separator + 1).trim());
        }
        String path = parts[1];
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }

        int contentLength = 0;
        if (headers.containsKey("content-length")) {
            try {
                contentLength = Integer.parseInt(headers.get("content-length"));
            } catch (NumberFormatException error) {
                throw new IOException("Invalid Content-Length", error);
            }
        }
        if (contentLength < 0 || contentLength > MAX_REQUEST_BODY_BYTES) {
            throw new IOException("Request body exceeds fixture bound");
        }
        boolean rejectBeforeBody = "/api/v1/activities/create/upload".equals(path)
                && (mode.get() == Mode.AUTH_FAILURE
                || mode.get() == Mode.RATE_LIMITED);
        byte[] body;
        if (rejectBeforeBody) {
            body = new byte[0];
        } else {
            body = new byte[contentLength];
            int offset = 0;
            while (offset < body.length) {
                int read = input.read(body, offset, body.length - offset);
                if (read < 0) {
                    throw new EOFException("Connection closed during request body");
                }
                offset += read;
            }
        }
        return new Request(parts[0], path, headers, body);
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean carriageReturn = false;
        while (output.size() <= MAX_HEADER_LINE_BYTES) {
            int next = input.read();
            if (next < 0) {
                return output.size() == 0 ? null : output.toString(StandardCharsets.US_ASCII.name());
            }
            if (carriageReturn) {
                if (next == '\n') {
                    return output.toString(StandardCharsets.US_ASCII.name());
                }
                output.write('\r');
                carriageReturn = false;
            }
            if (next == '\r') {
                carriageReturn = true;
            } else {
                output.write(next);
            }
        }
        throw new IOException("HTTP header line exceeds fixture bound");
    }

    private static void sendJson(
            OutputStream output,
            int status,
            String body) throws IOException {
        send(output, status, Map.of(), body);
    }

    private static void send(
            OutputStream output,
            int status,
            Map<String, String> extraHeaders,
            String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder headers = new StringBuilder()
                .append("HTTP/1.1 ")
                .append(status)
                .append(' ')
                .append(reason(status))
                .append("\r\n")
                .append("Content-Type: application/json; charset=utf-8\r\n")
                .append("Content-Length: ")
                .append(bytes.length)
                .append("\r\n")
                .append("Connection: close\r\n");
        for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
            headers.append(header.getKey())
                    .append(": ")
                    .append(header.getValue())
                    .append("\r\n");
        }
        headers.append("\r\n");
        output.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
        output.write(bytes);
        output.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200:
                return "OK";
            case 201:
                return "Created";
            case 307:
                return "Temporary Redirect";
            case 400:
                return "Bad Request";
            case 401:
                return "Unauthorized";
            case 404:
                return "Not Found";
            case 429:
                return "Too Many Requests";
            case 503:
                return "Service Unavailable";
            default:
                return "Response";
        }
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }

    private void assertHealthy() {
        Throwable error = failure.get();
        if (error != null) {
            throw new AssertionError("TLS fixture failed", error);
        }
    }

    private static final class Request {
        private final String method;
        private final String path;
        private final Map<String, String> headers;
        private final byte[] body;

        private Request(
                String method,
                String path,
                Map<String, String> headers,
                byte[] body) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.body = body;
        }
    }
}
