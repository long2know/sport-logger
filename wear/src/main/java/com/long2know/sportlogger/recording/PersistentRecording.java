package com.long2know.sportlogger.recording;

import java.util.Objects;

public final class PersistentRecording {
    private final RecordingState state;
    private final int activityId;
    private final long startedAtMillis;
    private final long activeSinceMillis;
    private final long activeSinceElapsedRealtimeMillis;
    private final long elapsedSinceElapsedRealtimeMillis;
    private final long accumulatedMillis;
    private final long accumulatedElapsedMillis;
    private final long endedAtMillis;
    private final int bootCount;
    private final RecordingError error;

    public PersistentRecording(
            RecordingState state,
            int activityId,
            long startedAtMillis,
            long activeSinceMillis,
            long accumulatedMillis,
            long endedAtMillis,
            RecordingError error) {
        this(
                state,
                activityId,
                startedAtMillis,
                activeSinceMillis,
                0L,
                0L,
                accumulatedMillis,
                accumulatedMillis,
                endedAtMillis,
                -1,
                error);
    }

    public PersistentRecording(
            RecordingState state,
            int activityId,
            long startedAtMillis,
            long activeSinceMillis,
            long activeSinceElapsedRealtimeMillis,
            long accumulatedMillis,
            long endedAtMillis,
            int bootCount,
            RecordingError error) {
        this(
                state,
                activityId,
                startedAtMillis,
                activeSinceMillis,
                activeSinceElapsedRealtimeMillis,
                activeSinceElapsedRealtimeMillis,
                accumulatedMillis,
                accumulatedMillis,
                endedAtMillis,
                bootCount,
                error);
    }

    public PersistentRecording(
            RecordingState state,
            int activityId,
            long startedAtMillis,
            long activeSinceMillis,
            long activeSinceElapsedRealtimeMillis,
            long elapsedSinceElapsedRealtimeMillis,
            long accumulatedMillis,
            long accumulatedElapsedMillis,
            long endedAtMillis,
            int bootCount,
            RecordingError error) {
        this.state = Objects.requireNonNull(state);
        this.activityId = activityId;
        this.startedAtMillis = startedAtMillis;
        this.activeSinceMillis = activeSinceMillis;
        this.activeSinceElapsedRealtimeMillis =
                Math.max(0L, activeSinceElapsedRealtimeMillis);
        this.elapsedSinceElapsedRealtimeMillis =
                Math.max(0L, elapsedSinceElapsedRealtimeMillis);
        this.accumulatedMillis = Math.max(0L, accumulatedMillis);
        this.accumulatedElapsedMillis = Math.max(
                this.accumulatedMillis,
                accumulatedElapsedMillis);
        this.endedAtMillis = endedAtMillis;
        this.bootCount = bootCount;
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

    public long getActiveSinceElapsedRealtimeMillis() {
        return activeSinceElapsedRealtimeMillis;
    }

    public long getAccumulatedMillis() {
        return accumulatedMillis;
    }

    public long getElapsedSinceElapsedRealtimeMillis() {
        return elapsedSinceElapsedRealtimeMillis;
    }

    public long getAccumulatedElapsedMillis() {
        return accumulatedElapsedMillis;
    }

    public long getEndedAtMillis() {
        return endedAtMillis;
    }

    public int getBootCount() {
        return bootCount;
    }

    public RecordingError getError() {
        return error;
    }

    public long durationAt(long nowMillis) {
        return durationAt(nowMillis, nowMillis, bootCount);
    }

    public long durationAt(
            long wallClockMillis,
            long elapsedRealtimeMillis,
            int currentBootCount) {
        if (state != RecordingState.RECORDING) {
            return accumulatedMillis;
        }
        if (activeSinceElapsedRealtimeMillis > 0L
                && !isBootInterrupted(elapsedRealtimeMillis, currentBootCount)) {
            return saturatedAdd(
                    accumulatedMillis,
                    Math.max(0L, elapsedRealtimeMillis - activeSinceElapsedRealtimeMillis));
        }
        if (activeSinceElapsedRealtimeMillis == 0L && activeSinceMillis > 0L) {
            return saturatedAdd(
                    accumulatedMillis,
                    Math.max(0L, wallClockMillis - activeSinceMillis));
        }
        return accumulatedMillis;
    }

    public boolean isBootInterrupted(long elapsedRealtimeMillis, int currentBootCount) {
        if (state != RecordingState.RECORDING
                && state != RecordingState.PAUSED) {
            return false;
        }
        if (bootCount >= 0
                && currentBootCount >= 0
                && bootCount != currentBootCount) {
            return true;
        }
        return (activeSinceElapsedRealtimeMillis > 0L
                && elapsedRealtimeMillis < activeSinceElapsedRealtimeMillis)
                || (elapsedSinceElapsedRealtimeMillis > 0L
                && elapsedRealtimeMillis < elapsedSinceElapsedRealtimeMillis);
    }

    public long elapsedDurationAt(
            long wallClockMillis,
            long elapsedRealtimeMillis,
            int currentBootCount) {
        if (state == RecordingState.IDLE) {
            return 0L;
        }
        if ((state == RecordingState.RECORDING || state == RecordingState.PAUSED)
                && elapsedSinceElapsedRealtimeMillis > 0L
                && !isBootInterrupted(elapsedRealtimeMillis, currentBootCount)) {
            return saturatedAdd(
                    accumulatedElapsedMillis,
                    Math.max(0L, elapsedRealtimeMillis - elapsedSinceElapsedRealtimeMillis));
        }
        if ((state == RecordingState.RECORDING || state == RecordingState.PAUSED)
                && elapsedSinceElapsedRealtimeMillis == 0L
                && startedAtMillis > 0L) {
            return Math.max(
                    accumulatedElapsedMillis,
                    Math.max(0L, wallClockMillis - startedAtMillis));
        }
        return accumulatedElapsedMillis;
    }

    public PersistentRecording checkpoint(
            long wallClockMillis,
            long elapsedRealtimeMillis,
            int currentBootCount) {
        if (state != RecordingState.RECORDING && state != RecordingState.PAUSED) {
            return this;
        }
        long activeDuration = durationAt(
                wallClockMillis,
                elapsedRealtimeMillis,
                currentBootCount);
        long elapsedDuration = elapsedDurationAt(
                wallClockMillis,
                elapsedRealtimeMillis,
                currentBootCount);
        return new PersistentRecording(
                state,
                activityId,
                startedAtMillis,
                state == RecordingState.RECORDING ? wallClockMillis : 0L,
                state == RecordingState.RECORDING ? elapsedRealtimeMillis : 0L,
                elapsedRealtimeMillis,
                activeDuration,
                elapsedDuration,
                endedAtMillis,
                currentBootCount,
                error);
    }

    public PersistentRecording withState(
            RecordingState nextState,
            long nextActiveSinceMillis,
            long nextAccumulatedMillis,
            long nextEndedAtMillis,
            RecordingError nextError) {
        return withState(
                nextState,
                nextActiveSinceMillis,
                0L,
                0L,
                nextAccumulatedMillis,
                nextAccumulatedMillis,
                nextEndedAtMillis,
                -1,
                nextError);
    }

    public PersistentRecording withState(
            RecordingState nextState,
            long nextActiveSinceMillis,
            long nextActiveSinceElapsedRealtimeMillis,
            long nextAccumulatedMillis,
            long nextEndedAtMillis,
            int nextBootCount,
            RecordingError nextError) {
        return withState(
                nextState,
                nextActiveSinceMillis,
                nextActiveSinceElapsedRealtimeMillis,
                0L,
                nextAccumulatedMillis,
                Math.max(accumulatedElapsedMillis, nextAccumulatedMillis),
                nextEndedAtMillis,
                nextBootCount,
                nextError);
    }

    public PersistentRecording withState(
            RecordingState nextState,
            long nextActiveSinceMillis,
            long nextActiveSinceElapsedRealtimeMillis,
            long nextElapsedSinceElapsedRealtimeMillis,
            long nextAccumulatedMillis,
            long nextAccumulatedElapsedMillis,
            long nextEndedAtMillis,
            int nextBootCount,
            RecordingError nextError) {
        return new PersistentRecording(
                nextState,
                activityId,
                startedAtMillis,
                nextActiveSinceMillis,
                nextActiveSinceElapsedRealtimeMillis,
                nextElapsedSinceElapsedRealtimeMillis,
                nextAccumulatedMillis,
                nextAccumulatedElapsedMillis,
                nextEndedAtMillis,
                nextBootCount,
                nextError);
    }

    public PersistentRecording withError(RecordingError nextError) {
        return new PersistentRecording(
                state,
                activityId,
                startedAtMillis,
                activeSinceMillis,
                activeSinceElapsedRealtimeMillis,
                elapsedSinceElapsedRealtimeMillis,
                accumulatedMillis,
                accumulatedElapsedMillis,
                endedAtMillis,
                bootCount,
                nextError);
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
