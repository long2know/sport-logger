package com.long2know.sportlogger.publishing.endurain.factory;

import com.long2know.sportlogger.publishing.endurain.EndurainCredentialGateway;
import com.long2know.sportlogger.publishing.endurain.EndurainDeliveryKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class EndurainDeliveryStatus {
    public enum State {
        PREPARED,
        PENDING,
        UPLOADED,
        UNKNOWN_DELIVERY,
        DUPLICATE_REVIEW,
        FAILED
    }

    public enum ArtifactState {
        READY,
        MISSING,
        LENGTH_MISMATCH,
        SHA256_MISMATCH,
        UNREADABLE,
        MANIFEST_INVALID
    }

    private final String artifactId;
    private final EndurainDeliveryKey key;
    private final State state;
    private final ArtifactState artifactState;
    private final EndurainCredentialGateway.State credentialState;
    private final List<Long> remoteIds;
    private final Long retryNotBeforeUtcMs;
    private final String lastFailureCode;
    private final String lastFailureMessage;
    private final boolean deleteUnsupportedRecorded;

    public EndurainDeliveryStatus(
            String artifactId,
            EndurainDeliveryKey key,
            State state,
            ArtifactState artifactState,
            EndurainCredentialGateway.State credentialState,
            List<Long> remoteIds,
            Long retryNotBeforeUtcMs,
            String lastFailureCode,
            String lastFailureMessage,
            boolean deleteUnsupportedRecorded) {
        this.artifactId = Objects.requireNonNull(artifactId, "artifactId");
        this.key = Objects.requireNonNull(key, "key");
        this.state = Objects.requireNonNull(state, "state");
        this.artifactState =
                Objects.requireNonNull(artifactState, "artifactState");
        this.credentialState =
                Objects.requireNonNull(credentialState, "credentialState");
        this.remoteIds = Collections.unmodifiableList(
                new ArrayList<>(remoteIds));
        this.retryNotBeforeUtcMs = retryNotBeforeUtcMs;
        this.lastFailureCode = lastFailureCode;
        this.lastFailureMessage = lastFailureMessage;
        this.deleteUnsupportedRecorded = deleteUnsupportedRecorded;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public EndurainDeliveryKey getKey() {
        return key;
    }

    public State getState() {
        return state;
    }

    public ArtifactState getArtifactState() {
        return artifactState;
    }

    public EndurainCredentialGateway.State getCredentialState() {
        return credentialState;
    }

    public List<Long> getRemoteIds() {
        return remoteIds;
    }

    public Long getRetryNotBeforeUtcMs() {
        return retryNotBeforeUtcMs;
    }

    public String getLastFailureCode() {
        return lastFailureCode;
    }

    public String getLastFailureMessage() {
        return lastFailureMessage;
    }

    public boolean isDeleteUnsupportedRecorded() {
        return deleteUnsupportedRecorded;
    }

    public boolean isRemoteEditSupported() {
        return false;
    }

    public boolean isRemoteDeleteSupported() {
        return false;
    }

    public boolean requiresReconciliation() {
        return state == State.UNKNOWN_DELIVERY
                || state == State.DUPLICATE_REVIEW;
    }

    public boolean canManualRetry() {
        return state == State.FAILED
                && artifactState == ArtifactState.READY
                && credentialState == EndurainCredentialGateway.State.AVAILABLE;
    }

    public boolean canConfirmDuplicateRiskRetry() {
        return requiresReconciliation()
                && artifactState == ArtifactState.READY
                && credentialState == EndurainCredentialGateway.State.AVAILABLE;
    }

    public boolean canRepairArtifact() {
        return state == State.FAILED
                && artifactState != ArtifactState.READY;
    }
}
