package com.long2know.sportlogger.publishing.endurain;

import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository;
import com.long2know.sportlogger.publishing.endurain.profile.EndurainConnectionProfile;
import com.long2know.utilities.contract.CanonicalIds;

import java.util.Objects;

public final class EndurainDeliveryKey {
    public static final String CONNECTOR_ID = "endurain-tcx-v1";

    private final String sessionId;
    private final long revision;
    private final String normalizedBaseUrl;
    private final String connectionIdentity;

    public EndurainDeliveryKey(
            String sessionId,
            long revision,
            String normalizedBaseUrl,
            String connectionIdentity) {
        this.sessionId = CanonicalIds.requireUuid(sessionId, "Session identifier");
        if (revision < 1L) {
            throw new IllegalArgumentException("Activity revision must be positive");
        }
        this.revision = revision;
        this.normalizedBaseUrl =
                Objects.requireNonNull(normalizedBaseUrl, "normalizedBaseUrl");
        this.connectionIdentity =
                Objects.requireNonNull(connectionIdentity, "connectionIdentity");
    }

    public static EndurainDeliveryKey forProfile(
            String sessionId,
            long revision,
            EndurainConnectionProfile profile) {
        Objects.requireNonNull(profile, "profile");
        return new EndurainDeliveryKey(
                sessionId,
                revision,
                profile.getNormalizedBaseUrl(),
                profile.getConnectionIdentity());
    }

    public String getSessionId() {
        return sessionId;
    }

    public long getRevision() {
        return revision;
    }

    public String getConnectorId() {
        return CONNECTOR_ID;
    }

    public String getNormalizedBaseUrl() {
        return normalizedBaseUrl;
    }

    public String getConnectionIdentity() {
        return connectionIdentity;
    }

    public PhoneActivityRepository.ConnectorKey toRepositoryKey() {
        return new PhoneActivityRepository.ConnectorKey(
                sessionId,
                revision,
                CONNECTOR_ID,
                normalizedBaseUrl,
                connectionIdentity);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof EndurainDeliveryKey)) {
            return false;
        }
        EndurainDeliveryKey that = (EndurainDeliveryKey) other;
        return revision == that.revision
                && sessionId.equals(that.sessionId)
                && normalizedBaseUrl.equals(that.normalizedBaseUrl)
                && connectionIdentity.equals(that.connectionIdentity);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                sessionId,
                revision,
                normalizedBaseUrl,
                connectionIdentity);
    }
}
