package com.long2know.sportlogger.connector.endurain;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

public final class EndurainEndpoint {
    private static final String ABOUT_PATH = "/api/v1/about";
    private static final String UPLOAD_PATH = "/api/v1/activities/create/upload";

    private final URI baseUri;
    private final String normalizedBaseUrl;

    private EndurainEndpoint(URI baseUri) {
        this.baseUri = baseUri;
        normalizedBaseUrl = baseUri.toASCIIString();
    }

    public static EndurainEndpoint parse(String value) {
        Objects.requireNonNull(value, "value");
        String candidate = value.trim();
        if (candidate.isEmpty()) {
            throw new IllegalArgumentException("Endurain base URL is required");
        }

        final URI parsed;
        try {
            parsed = new URI(candidate);
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Endurain base URL is malformed");
        }

        if (!parsed.isAbsolute()
                || parsed.isOpaque()
                || !"https".equalsIgnoreCase(parsed.getScheme())) {
            throw new IllegalArgumentException("Endurain base URL must use HTTPS");
        }
        if (parsed.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Endurain base URL must not contain credentials");
        }
        if (parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "Endurain base URL must not contain a query or fragment");
        }

        String host = parsed.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("Endurain base URL must contain a valid host");
        }
        String normalizedHost = normalizeHost(host);
        int port = parsed.getPort();
        if (port == 0 || port < -1 || port > 65535) {
            throw new IllegalArgumentException("Endurain base URL contains an invalid port");
        }
        if (port == 443) {
            port = -1;
        }

        String normalizedPath = normalizePath(parsed.getRawPath());
        try {
            return new EndurainEndpoint(new URI(
                    "https",
                    null,
                    normalizedHost,
                    port,
                    normalizedPath.isEmpty() ? null : normalizedPath,
                    null,
                    null));
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Endurain base URL cannot be normalized", error);
        }
    }

    public String normalizedBaseUrl() {
        return normalizedBaseUrl;
    }

    public URI aboutUri() {
        return URI.create(normalizedBaseUrl + ABOUT_PATH);
    }

    public URI uploadUri() {
        return URI.create(normalizedBaseUrl + UPLOAD_PATH);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EndurainEndpoint
                && baseUri.equals(((EndurainEndpoint) other).baseUri);
    }

    @Override
    public int hashCode() {
        return baseUri.hashCode();
    }

    @Override
    public String toString() {
        return normalizedBaseUrl;
    }

    private static String normalizeHost(String host) {
        String unwrapped = host;
        if (unwrapped.startsWith("[") && unwrapped.endsWith("]")) {
            unwrapped = unwrapped.substring(1, unwrapped.length() - 1);
        }
        if (unwrapped.endsWith(".")) {
            throw new IllegalArgumentException(
                    "Endurain base URL host must not end with a dot");
        }
        if (unwrapped.indexOf(':') >= 0) {
            return unwrapped.toLowerCase(Locale.ROOT);
        }
        try {
            return IDN.toASCII(unwrapped, IDN.USE_STD3_ASCII_RULES)
                    .toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    "Endurain base URL contains an invalid host");
        }
    }

    private static String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty() || "/".equals(rawPath)) {
            return "";
        }
        if (rawPath.indexOf('\\') >= 0
                || rawPath.indexOf('%') >= 0
                || rawPath.contains("//")) {
            throw new IllegalArgumentException(
                    "Endurain base URL contains an unsafe path");
        }

        final String normalized;
        try {
            normalized = new URI(null, null, rawPath, null).normalize().getRawPath();
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException(
                    "Endurain base URL contains an invalid path");
        }
        if (!rawPath.equals(normalized)
                || rawPath.equals("/.")
                || rawPath.startsWith("/../")
                || rawPath.endsWith("/..")) {
            throw new IllegalArgumentException(
                    "Endurain base URL path must not contain dot segments");
        }

        int end = rawPath.length();
        while (end > 0 && rawPath.charAt(end - 1) == '/') {
            end--;
        }
        return rawPath.substring(0, end);
    }
}
