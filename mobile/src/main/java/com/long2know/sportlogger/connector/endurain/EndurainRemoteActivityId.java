package com.long2know.sportlogger.connector.endurain;

import java.util.Objects;

public final class EndurainRemoteActivityId {
    private final String normalizedBaseUrl;
    private final String connectionIdentity;
    private final long activityId;

    EndurainRemoteActivityId(
            String normalizedBaseUrl,
            String connectionIdentity,
            long activityId) {
        if (activityId < 1) {
            throw new IllegalArgumentException("Remote activity ID must be positive");
        }
        this.normalizedBaseUrl =
                Objects.requireNonNull(normalizedBaseUrl, "normalizedBaseUrl");
        this.connectionIdentity =
                Objects.requireNonNull(connectionIdentity, "connectionIdentity");
        this.activityId = activityId;
    }

    public String normalizedBaseUrl() {
        return normalizedBaseUrl;
    }

    public String connectionIdentity() {
        return connectionIdentity;
    }

    public long activityId() {
        return activityId;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof EndurainRemoteActivityId)) {
            return false;
        }
        EndurainRemoteActivityId that = (EndurainRemoteActivityId) other;
        return activityId == that.activityId
                && normalizedBaseUrl.equals(that.normalizedBaseUrl)
                && connectionIdentity.equals(that.connectionIdentity);
    }

    @Override
    public int hashCode() {
        return Objects.hash(normalizedBaseUrl, connectionIdentity, activityId);
    }

    @Override
    public String toString() {
        return normalizedBaseUrl + "#" + connectionIdentity + ":" + activityId;
    }
}
