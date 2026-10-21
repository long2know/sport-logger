package com.long2know.sportlogger.security;

import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

import javax.net.ssl.HttpsURLConnection;

import okhttp3.Request;

public final class EndurainApiKey implements AutoCloseable {
    private static final int MAX_CHARACTERS = 4096;

    private char[] value;

    private EndurainApiKey(char[] value) {
        this.value = value;
    }

    public static EndurainApiKey from(char[] value) {
        Objects.requireNonNull(value, "value");
        if (value.length == 0 || value.length > MAX_CHARACTERS) {
            throw new IllegalArgumentException("Endurain API key has an invalid length");
        }
        for (char character : value) {
            if (character < 0x21 || character > 0x7e) {
                throw new IllegalArgumentException(
                        "Endurain API key must contain printable ASCII characters");
            }
        }
        return new EndurainApiKey(Arrays.copyOf(value, value.length));
    }

    public void applyTo(URLConnection connection) {
        Objects.requireNonNull(connection, "connection");
        if (!(connection instanceof HttpsURLConnection)) {
            throw new IllegalArgumentException(
                    "Endurain API keys may only be applied to HTTPS connections");
        }
        char[] current = requireOpen();
        connection.setRequestProperty("X-API-Key", new String(current));
    }

    public void applyTo(Request.Builder request) {
        Objects.requireNonNull(request, "request");
        if (!request.build().url().isHttps()) {
            throw new IllegalArgumentException(
                    "Endurain API keys may only be applied to HTTPS requests");
        }
        char[] current = requireOpen();
        request.header("X-API-Key", new String(current));
    }

    byte[] encodeUtf8() {
        char[] current = requireOpen();
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(current));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        if (encoded.hasArray()) {
            Arrays.fill(encoded.array(), (byte) 0);
        }
        return bytes;
    }

    static EndurainApiKey decodeUtf8(byte[] bytes) throws CharacterCodingException {
        CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes));
        char[] characters = new char[decoded.remaining()];
        decoded.get(characters);
        try {
            return from(characters);
        } finally {
            Arrays.fill(characters, '\0');
            if (decoded.hasArray()) {
                Arrays.fill(decoded.array(), '\0');
            }
        }
    }

    public boolean isClosed() {
        return value == null;
    }

    @Override
    public void close() {
        if (value != null) {
            Arrays.fill(value, '\0');
            value = null;
        }
    }

    @Override
    public String toString() {
        return "EndurainApiKey[redacted]";
    }

    private char[] requireOpen() {
        if (value == null) {
            throw new IllegalStateException("Endurain API key is closed");
        }
        return value;
    }
}
