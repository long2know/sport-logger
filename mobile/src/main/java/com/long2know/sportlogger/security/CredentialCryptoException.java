package com.long2know.sportlogger.security;

final class CredentialCryptoException extends Exception {
    enum Reason {
        KEY_MISSING,
        KEY_UNAVAILABLE,
        AUTHENTICATION_FAILED,
        INVALID_ENVELOPE
    }

    private final Reason reason;

    CredentialCryptoException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    CredentialCryptoException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }
}
