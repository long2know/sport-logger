package com.long2know.sportlogger.connector.endurain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class EndurainConnectionConfig {
    private static final Pattern CONNECTION_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Set<EndurainCapability> CAPABILITIES =
            Collections.unmodifiableSet(EnumSet.of(
                    EndurainCapability.PUBLISH_TCX,
                    EndurainCapability.RECONCILE_UNKNOWN_DELIVERY));

    private final EndurainEndpoint endpoint;
    private final String connectionIdentity;

    public EndurainConnectionConfig(
            EndurainEndpoint endpoint,
            String connectionIdentity) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(connectionIdentity, "connectionIdentity");
        if (!CONNECTION_ID.matcher(connectionIdentity).matches()) {
            throw new IllegalArgumentException(
                    "Connection identity must be a stable opaque identifier");
        }
        this.connectionIdentity = connectionIdentity;
    }

    public EndurainEndpoint endpoint() {
        return endpoint;
    }

    public String connectionIdentity() {
        return connectionIdentity;
    }

    public Set<EndurainCapability> capabilities() {
        return CAPABILITIES;
    }

    public boolean supportsDelete() {
        return false;
    }

    public EndurainRemoteActivityId remoteActivity(long activityId) {
        return new EndurainRemoteActivityId(
                endpoint.normalizedBaseUrl(),
                connectionIdentity,
                activityId);
    }
}
