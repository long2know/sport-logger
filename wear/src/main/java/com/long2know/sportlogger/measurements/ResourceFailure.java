package com.long2know.sportlogger.measurements;

import java.util.Objects;

public final class ResourceFailure {
    public enum Code {
        UNAVAILABLE,
        PERMISSION_DENIED,
        REGISTRATION_FAILED,
        CLEANUP_FAILED
    }

    private final String resource;
    private final Code code;
    private final String message;

    public ResourceFailure(String resource, Code code, String message) {
        this.resource = Objects.requireNonNull(resource);
        this.code = Objects.requireNonNull(code);
        this.message = message == null ? "" : message;
    }

    public String getResource() {
        return resource;
    }

    public Code getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
