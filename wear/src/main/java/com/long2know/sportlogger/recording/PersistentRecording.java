package com.long2know.sportlogger.recording;

import java.util.Objects;

public final class PersistentRecording {
    private final RecordingState state;
    private final int activityId;
    private final long startedAtMillis;
    private final long activeSinceMillis;
    private final long accumulatedMillis;
    private final long endedAtMillis;
    private final RecordingError error;

    public PersistentRecording(
            RecordingState state,
            int activityId,
            long startedAtMillis,
            long activeSinceMillis,
            long accumulatedMillis,
            long endedAtMillis,
            RecordingError error) {
        this.state = Objects.requireNonNull(state);
        this.activityId = activityId;
        this.startedAtMillis = startedAtMillis;
        this.activeSinceMillis = activeSinceMillis;
        this.accumulatedMillis = Math.max(0L, accumulatedMillis);
        this.endedAtMillis = endedAtMillis;
        this.error = error;
    }

    public static PersistentRecording idle() {
        return new PersistentRecording(RecordingState.IDLE, 0, 0L, 0L, 0L, 0L, null);
    }

    public RecordingState getState() {
        return state;
    }

    public int getActivityId() {
        return activityId;
    }

    public long getStartedAtMillis() {
        return startedAtMillis;
    }

    public long getActiveSinceMillis() {
        return activeSinceMillis;
    }

    public long getAccumulatedMillis() {
        return accumulatedMillis;
    }

    public long getEndedAtMillis() {
        return endedAtMillis;
    }

    public RecordingError getError() {
        return error;
    }

    public long durationAt(long nowMillis) {
        if (state == RecordingState.RECORDING && activeSinceMillis > 0L) {
            return accumulatedMillis + Math.max(0L, nowMillis - activeSinceMillis);
        }
        return accumulatedMillis;
    }

    public PersistentRecording withState(
            RecordingState nextState,
            long nextActiveSinceMillis,
            long nextAccumulatedMillis,
            long nextEndedAtMillis,
            RecordingError nextError) {
        return new PersistentRecording(
                nextState,
                activityId,
                startedAtMillis,
                nextActiveSinceMillis,
                nextAccumulatedMillis,
                nextEndedAtMillis,
                nextError);
    }

    public PersistentRecording withError(RecordingError nextError) {
        return new PersistentRecording(
                state,
                activityId,
                startedAtMillis,
                activeSinceMillis,
                accumulatedMillis,
                endedAtMillis,
                nextError);
    }
}
