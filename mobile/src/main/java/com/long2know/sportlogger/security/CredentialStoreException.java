package com.long2know.sportlogger.security;

public final class CredentialStoreException extends Exception {
    public enum Reason {
        KEYSTORE_UNAVAILABLE,
        STORAGE_UNAVAILABLE
    }

    private final Reason reason;

    CredentialStoreException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
