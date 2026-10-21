package com.long2know.sportlogger.recording;

import java.util.Objects;

public final class RecordingSnapshot {
    private final RecordingState state;
    private final int activityId;
    private final long startedAtMillis;
    private final long durationMillis;
    private final long elapsedDurationMillis;
    private final long activeSinceElapsedRealtimeMillis;
    private final long elapsedSinceElapsedRealtimeMillis;
    private final int bootCount;
    private final RecordingError error;

    public RecordingSnapshot(
            RecordingState state,
            int activityId,
            long durationMillis,
            RecordingError error) {
        this(
                state,
                activityId,
                0L,
                durationMillis,
                durationMillis,
                0L,
                0L,
                -1,
                error);
    }

    public RecordingSnapshot(
            RecordingState state,
            int activityId,
            long startedAtMillis,
            long durationMillis,
            long elapsedDurationMillis,
            long activeSinceElapsedRealtimeMillis,
            long elapsedSinceElapsedRealtimeMillis,
            int bootCount,
            RecordingError error) {
        this.state = Objects.requireNonNull(state);
        this.activityId = activityId;
        this.startedAtMillis = startedAtMillis;
        this.durationMillis = Math.max(0L, durationMillis);
        this.elapsedDurationMillis =
                Math.max(this.durationMillis, elapsedDurationMillis);
        this.activeSinceElapsedRealtimeMillis =
                Math.max(0L, activeSinceElapsedRealtimeMillis);
        this.elapsedSinceElapsedRealtimeMillis =
                Math.max(0L, elapsedSinceElapsedRealtimeMillis);
        this.bootCount = bootCount;
        this.error = error;
    }

    public RecordingState getState() {
        return state;
    }

    public int getActivityId() {
        return activityId;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public long getStartedAtMillis() {
        return startedAtMillis;
    }

    public long getElapsedDurationMillis() {
        return elapsedDurationMillis;
    }

    public long getActiveSinceElapsedRealtimeMillis() {
        return activeSinceElapsedRealtimeMillis;
    }

    public long getElapsedSinceElapsedRealtimeMillis() {
        return elapsedSinceElapsedRealtimeMillis;
    }

    public int getBootCount() {
        return bootCount;
    }

    public RecordingError getError() {
        return error;
    }

    public boolean isActive() {
        return state == RecordingState.STARTING
                || state == RecordingState.RECORDING
                || state == RecordingState.PAUSED
                || state == RecordingState.STOPPING
                || state == RecordingState.DISCARDING
                || state == RecordingState.EXPORT_PENDING;
    }
}
