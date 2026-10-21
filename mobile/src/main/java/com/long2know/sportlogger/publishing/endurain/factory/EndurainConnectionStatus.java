package com.long2know.sportlogger.publishing.endurain.factory;

import com.long2know.sportlogger.publishing.endurain.EndurainCredentialGateway;
import com.long2know.sportlogger.publishing.endurain.profile.EndurainConnectionProfile;

import java.util.Objects;

public final class EndurainConnectionStatus {
    public enum DisableSemantics {
        FUTURE_CLAIMS_ONLY
    }

    private final EndurainConnectionProfile profile;
    private final EndurainCredentialGateway.State credentialState;

    public EndurainConnectionStatus(
            EndurainConnectionProfile profile,
            EndurainCredentialGateway.State credentialState) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.credentialState =
                Objects.requireNonNull(credentialState, "credentialState");
    }

    public EndurainConnectionProfile getProfile() {
        return profile;
    }

    public EndurainCredentialGateway.State getCredentialState() {
        return credentialState;
    }

    public DisableSemantics getDisableSemantics() {
        return DisableSemantics.FUTURE_CLAIMS_ONLY;
    }
}
