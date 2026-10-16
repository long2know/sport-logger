package com.long2know.sportlogger.recording;

import java.util.Objects;

public final class RecordingSnapshot {
    private final RecordingState state;
    private final int activityId;
    private final long durationMillis;
    private final RecordingError error;

    public RecordingSnapshot(
            RecordingState state,
            int activityId,
            long durationMillis,
            RecordingError error) {
        this.state = Objects.requireNonNull(state);
        this.activityId = activityId;
        this.durationMillis = Math.max(0L, durationMillis);
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
