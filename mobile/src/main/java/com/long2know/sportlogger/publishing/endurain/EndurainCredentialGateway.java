package com.long2know.sportlogger.publishing.endurain;

import com.long2know.sportlogger.publishing.endurain.profile.EndurainConnectionProfile;
import com.long2know.sportlogger.security.CredentialStoreException;
import com.long2know.sportlogger.security.EndurainApiKey;

import java.util.Objects;

public interface EndurainCredentialGateway {
    enum State {
        AVAILABLE,
        NOT_CONFIGURED,
        KEYSTORE_KEY_MISSING,
        KEYSTORE_UNAVAILABLE,
        STORAGE_CORRUPT,
        STORAGE_UNAVAILABLE
    }

    void store(EndurainConnectionProfile profile, char[] apiKey)
            throws CredentialStoreException;

    Access load(EndurainConnectionProfile profile);

    void reset(EndurainConnectionProfile profile)
            throws CredentialStoreException;

    final class Access implements AutoCloseable {
        private final State state;
        private final EndurainApiKey credential;

        private Access(State state, EndurainApiKey credential) {
            this.state = Objects.requireNonNull(state, "state");
            this.credential = credential;
        }

        public static Access available(EndurainApiKey credential) {
            return new Access(
                    State.AVAILABLE,
                    Objects.requireNonNull(credential, "credential"));
        }

        public static Access unavailable(State state) {
            if (state == State.AVAILABLE) {
                throw new IllegalArgumentException(
                        "Available credential access requires a credential");
            }
            return new Access(state, null);
        }

        public State getState() {
            return state;
        }

        public EndurainApiKey getCredential() {
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
}
