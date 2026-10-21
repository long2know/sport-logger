package com.long2know.sportlogger.publishing.endurain.profile;

import com.long2know.sportlogger.connector.endurain.EndurainConnectionConfig;
import com.long2know.sportlogger.connector.endurain.EndurainEndpoint;

import java.util.Objects;

public final class EndurainConnectionProfile {
    private final String connectionIdentity;
    private final String label;
    private final String normalizedBaseUrl;
    private final boolean enabled;
    private final String credentialReference;

    EndurainConnectionProfile(
            String connectionIdentity,
            String label,
            String normalizedBaseUrl,
            boolean enabled,
            String credentialReference) {
        this.connectionIdentity =
                Objects.requireNonNull(connectionIdentity, "connectionIdentity");
        this.label = Objects.requireNonNull(label, "label");
        this.normalizedBaseUrl =
                Objects.requireNonNull(normalizedBaseUrl, "normalizedBaseUrl");
        this.enabled = enabled;
        this.credentialReference =
                Objects.requireNonNull(credentialReference, "credentialReference");
    }

    public String getConnectionIdentity() {
        return connectionIdentity;
    }

    public String getLabel() {
        return label;
    }

    public String getNormalizedBaseUrl() {
        return normalizedBaseUrl;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getCredentialReference() {
        return credentialReference;
    }

    public EndurainConnectionConfig toConnectionConfig() {
        return new EndurainConnectionConfig(
                EndurainEndpoint.parse(normalizedBaseUrl),
                connectionIdentity);
    }
}
