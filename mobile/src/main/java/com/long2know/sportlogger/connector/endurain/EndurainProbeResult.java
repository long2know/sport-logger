package com.long2know.sportlogger.connector.endurain;

import java.time.Duration;

public final class EndurainProbeResult {
    public enum State {
        AVAILABLE,
        INCOMPATIBLE_RESPONSE,
        REDIRECT_REJECTED,
        TLS_VALIDATION_FAILED,
        RETRYABLE,
        RATE_LIMITED,
        PERMANENT_FAILURE
    }

    private final State state;
    private final String apiVersion;
    private final Integer httpStatus;
    private final Duration retryAfter;

    private EndurainProbeResult(
            State state,
            String apiVersion,
            Integer httpStatus,
            Duration retryAfter) {
        this.state = state;
        this.apiVersion = apiVersion;
        this.httpStatus = httpStatus;
        this.retryAfter = retryAfter;
    }

    static EndurainProbeResult available(String apiVersion) {
        return new EndurainProbeResult(State.AVAILABLE, apiVersion, 200, null);
    }

    static EndurainProbeResult of(State state, Integer httpStatus) {
        return new EndurainProbeResult(state, null, httpStatus, null);
    }

    static EndurainProbeResult rateLimited(
            Integer httpStatus,
            Duration retryAfter) {
        return new EndurainProbeResult(
                State.RATE_LIMITED,
                null,
                httpStatus,
                retryAfter);
    }

    public State state() {
        return state;
    }

    public String apiVersion() {
        return apiVersion;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public boolean isCanonicalV0190() {
        return state == State.AVAILABLE && "v0.19.0".equals(apiVersion);
    }
}
