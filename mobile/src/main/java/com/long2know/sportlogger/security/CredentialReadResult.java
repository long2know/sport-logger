package com.long2know.sportlogger.security;

public final class CredentialReadResult implements AutoCloseable {
    public enum State {
        AVAILABLE,
        NOT_CONFIGURED,
        KEYSTORE_KEY_MISSING,
        KEYSTORE_UNAVAILABLE,
        STORAGE_CORRUPT,
        STORAGE_UNAVAILABLE
    }

    private final State state;
    private final EndurainApiKey credential;

    private CredentialReadResult(State state, EndurainApiKey credential) {
        this.state = state;
        this.credential = credential;
    }

    static CredentialReadResult available(EndurainApiKey credential) {
        return new CredentialReadResult(State.AVAILABLE, credential);
    }

    static CredentialReadResult of(State state) {
        return new CredentialReadResult(state, null);
    }

    public State state() {
        return state;
    }

    public EndurainApiKey credential() {
        if (credential == null) {
            throw new IllegalStateException("No credential is available");
        }
        return credential;
    }

    @Override
    public void close() {
        if (credential != null) {
            credential.close();
        }
    }
}
