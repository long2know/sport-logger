package com.long2know.sportlogger.publishing.endurain;

import com.long2know.sportlogger.publishing.endurain.profile.EndurainConnectionProfile;
import com.long2know.sportlogger.security.CredentialReadResult;
import com.long2know.sportlogger.security.CredentialStoreException;
import com.long2know.sportlogger.security.EndurainCredentialStore;

import java.util.Objects;

public final class SecureEndurainCredentialGateway
        implements EndurainCredentialGateway {
    private final EndurainCredentialStore store;

    public SecureEndurainCredentialGateway(EndurainCredentialStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public void store(
            EndurainConnectionProfile profile,
            char[] apiKey) throws CredentialStoreException {
        store.store(profile.toConnectionConfig(), apiKey);
    }

    @Override
    public Access load(EndurainConnectionProfile profile) {
        CredentialReadResult read =
                store.load(profile.toConnectionConfig());
        if (read.state() == CredentialReadResult.State.AVAILABLE) {
            return Access.available(read.credential());
        }
        try {
            return Access.unavailable(State.valueOf(read.state().name()));
        } finally {
            read.close();
        }
    }

    @Override
    public void reset(
            EndurainConnectionProfile profile) throws CredentialStoreException {
        store.reset(profile.toConnectionConfig());
    }
}
