package com.long2know.sportlogger.recording;

public interface RecordingLapRecorder {
    RecordingLapRecorder UNAVAILABLE = new RecordingLapRecorder() {
        @Override
        public void recordManualLap(Boundary boundary, Callback callback) {
            callback.onFailure(new IllegalStateException(
                    "Durable manual lap storage is unavailable"));
        }

        @Override
        public void recover(int activityId, Callback callback) {
            callback.onPersisted(LapState.initial());
        }
    };

    interface Callback {
        void onPersisted(LapState state);

        void onFailure(Throwable error);
    }

    void recordManualLap(Boundary boundary, Callback callback);

    void recover(int activityId, Callback callback);

    final class Boundary {
        private final int activityId;
        private final long wallTimeUtcMillis;
        private final long elapsedDurationMillis;
        private final long activeDurationMillis;

        public Boundary(
                int activityId,
                long wallTimeUtcMillis,
                long elapsedDurationMillis,
                long activeDurationMillis) {
            if (activityId <= 0
                    || wallTimeUtcMillis <= 0L
                    || elapsedDurationMillis < 0L
                    || activeDurationMillis < 0L
                    || activeDurationMillis > elapsedDurationMillis) {
                throw new IllegalArgumentException("Manual lap boundary is invalid");
            }
            this.activityId = activityId;
            this.wallTimeUtcMillis = wallTimeUtcMillis;
            this.elapsedDurationMillis = elapsedDurationMillis;
            this.activeDurationMillis = activeDurationMillis;
        }

        public int getActivityId() {
            return activityId;
        }

        public long getWallTimeUtcMillis() {
            return wallTimeUtcMillis;
        }

        public long getElapsedDurationMillis() {
            return elapsedDurationMillis;
        }

        public long getActiveDurationMillis() {
            return activeDurationMillis;
        }
    }

    final class LapState {
        private final int currentLapIndex;
        private final Long lastLapElapsedDurationMillis;
        private final Long lastLapActiveDurationMillis;

        public LapState(
                int currentLapIndex,
                Long lastLapElapsedDurationMillis,
                Long lastLapActiveDurationMillis) {
            this.currentLapIndex = Math.max(0, currentLapIndex);
            this.lastLapElapsedDurationMillis = lastLapElapsedDurationMillis;
            this.lastLapActiveDurationMillis = lastLapActiveDurationMillis;
        }

        public static LapState initial() {
            return new LapState(1, null, null);
        }

        public int getCurrentLapIndex() {
            return currentLapIndex;
        }

        public Long getLastLapElapsedDurationMillis() {
            return lastLapElapsedDurationMillis;
        }

        public Long getLastLapActiveDurationMillis() {
            return lastLapActiveDurationMillis;
        }
    }
}
