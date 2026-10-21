package com.long2know.sportlogger.publishing.endurain;

import com.long2know.sportlogger.connector.endurain.EndurainConnectionConfig;
import com.long2know.sportlogger.connector.endurain.TcxUploadArtifact;
import com.long2know.sportlogger.security.EndurainApiKey;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public interface EndurainTransportGateway {
    UploadOutcome upload(
            EndurainConnectionConfig config,
            TcxUploadArtifact artifact,
            EndurainApiKey credential);

    final class UploadOutcome {
        public enum State {
            DELIVERED,
            AUTHENTICATION_REQUIRED,
            VALIDATION_REJECTED,
            PERMANENT_FAILURE,
            RETRYABLE,
            RATE_LIMITED,
            UNKNOWN_DELIVERY
        }

        private final State state;
        private final String reason;
        private final List<Long> remoteIds;
        private final Integer httpStatus;
        private final Duration retryAfter;

        private UploadOutcome(
                State state,
                String reason,
                List<Long> remoteIds,
                Integer httpStatus,
                Duration retryAfter) {
            this.state = Objects.requireNonNull(state, "state");
            this.reason = Objects.requireNonNull(reason, "reason");
            this.remoteIds = Collections.unmodifiableList(
                    new ArrayList<>(remoteIds));
            this.httpStatus = httpStatus;
            this.retryAfter = retryAfter;
        }

        public static UploadOutcome delivered(List<Long> remoteIds) {
            if (remoteIds == null || remoteIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "Delivered upload requires remote identifiers");
            }
            return new UploadOutcome(
                    State.DELIVERED,
                    "NONE",
                    remoteIds,
                    201,
                    null);
        }

        public static UploadOutcome failure(
                State state,
                String reason,
                Integer httpStatus) {
            if (state == State.DELIVERED || state == State.RATE_LIMITED) {
                throw new IllegalArgumentException(
                        "Use the typed delivered or rate-limited outcome");
            }
            return new UploadOutcome(
                    state,
                    reason,
                    Collections.emptyList(),
                    httpStatus,
                    null);
        }

        public static UploadOutcome rateLimited(
                Duration retryAfter,
                Integer httpStatus) {
            return new UploadOutcome(
                    State.RATE_LIMITED,
                    "RATE_LIMITED",
                    Collections.emptyList(),
                    httpStatus,
                    Objects.requireNonNull(retryAfter, "retryAfter"));
        }

        public static UploadOutcome unknownRateLimited(
                Duration retryAfter,
                Integer httpStatus) {
            return new UploadOutcome(
                    State.UNKNOWN_DELIVERY,
                    "RATE_LIMITED",
                    Collections.emptyList(),
                    httpStatus,
                    Objects.requireNonNull(retryAfter, "retryAfter"));
        }

        public State getState() {
            return state;
        }

        public String getReason() {
            return reason;
        }

        public List<Long> getRemoteIds() {
            return remoteIds;
        }

        public Integer getHttpStatus() {
            return httpStatus;
        }

        public Duration getRetryAfter() {
            return retryAfter;
        }
    }
}
