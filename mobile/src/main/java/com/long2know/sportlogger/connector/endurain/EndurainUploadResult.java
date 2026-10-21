package com.long2know.sportlogger.connector.endurain;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class EndurainUploadResult {
    public enum State {
        DELIVERED,
        AUTHENTICATION_REQUIRED,
        VALIDATION_REJECTED,
        PERMANENT_FAILURE,
        RETRYABLE,
        RATE_LIMITED,
        UNKNOWN_DELIVERY
    }

    public enum Reason {
        NONE,
        INVALID_LOCAL_ARTIFACT,
        AUTHENTICATION_REJECTED,
        SERVER_VALIDATION_REJECTED,
        ENDPOINT_NOT_SUPPORTED,
        PERMANENT_SERVER_REJECTION,
        REDIRECT_REJECTED,
        TLS_VALIDATION_FAILED,
        PRE_TRANSMISSION_NETWORK_FAILURE,
        RATE_LIMITED,
        POST_TRANSMISSION_NETWORK_FAILURE,
        UNCERTAIN_SERVER_FAILURE,
        MALFORMED_SUCCESS_RESPONSE
    }

    private final State state;
    private final Reason reason;
    private final List<EndurainRemoteActivityId> remoteActivities;
    private final Integer httpStatus;
    private final Duration retryAfter;

    private EndurainUploadResult(
            State state,
            Reason reason,
            List<EndurainRemoteActivityId> remoteActivities,
            Integer httpStatus,
            Duration retryAfter) {
        this.state = Objects.requireNonNull(state, "state");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.remoteActivities = Collections.unmodifiableList(
                new ArrayList<>(remoteActivities));
        this.httpStatus = httpStatus;
        this.retryAfter = retryAfter;
    }

    static EndurainUploadResult delivered(
            List<EndurainRemoteActivityId> remoteActivities) {
        if (remoteActivities.isEmpty()) {
            throw new IllegalArgumentException("Delivered result requires remote IDs");
        }
        return new EndurainUploadResult(
                State.DELIVERED,
                Reason.NONE,
                remoteActivities,
                201,
                null);
    }

    static EndurainUploadResult failure(
            State state,
            Reason reason,
            Integer httpStatus) {
        return new EndurainUploadResult(
                state,
                reason,
                Collections.emptyList(),
                httpStatus,
                null);
    }

    static EndurainUploadResult rateLimited(
            Integer httpStatus,
            Duration retryAfter) {
        return new EndurainUploadResult(
                State.RATE_LIMITED,
                Reason.RATE_LIMITED,
                Collections.emptyList(),
                httpStatus,
                Objects.requireNonNull(retryAfter, "retryAfter"));
    }

    public State state() {
        return state;
    }

    public Reason reason() {
        return reason;
    }

    public List<EndurainRemoteActivityId> remoteActivities() {
        return remoteActivities;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public boolean requiresManualReconciliation() {
        return state == State.UNKNOWN_DELIVERY;
    }

    public boolean mayRetryAutomatically() {
        return state == State.RETRYABLE || state == State.RATE_LIMITED;
    }
}
