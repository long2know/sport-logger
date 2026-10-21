package com.long2know.sportlogger.acquisition;

import androidx.annotation.Nullable;

import java.util.Objects;

public final class LiveMeasurementSnapshot {
    public static final class Value<T> {
        private final T value;
        private final MeasurementProvenance provenance;
        private final long sequenceNanos;
        private final MeasurementSample.Quality quality;

        Value(
                T value,
                MeasurementProvenance provenance,
                long sequenceNanos,
                MeasurementSample.Quality quality) {
            this.value = Objects.requireNonNull(value);
            this.provenance = Objects.requireNonNull(provenance);
            this.sequenceNanos = Math.max(0L, sequenceNanos);
            this.quality = Objects.requireNonNull(quality);
        }

        public T getValue() {
            return value;
        }

        public MeasurementProvenance getProvenance() {
            return provenance;
        }

        public long getSequenceNanos() {
            return sequenceNanos;
        }

        public MeasurementSample.Quality getQuality() {
            return quality;
        }
    }

    private final int activityId;
    private final Value<Double> heartRateBpm;
    private final Value<Long> steps;
    private final Value<Double> distanceMeters;
    private final Value<LocationSample> location;

    LiveMeasurementSnapshot(
            int activityId,
            @Nullable Value<Double> heartRateBpm,
            @Nullable Value<Long> steps,
            @Nullable Value<Double> distanceMeters,
            @Nullable Value<LocationSample> location) {
        this.activityId = Math.max(0, activityId);
        this.heartRateBpm = heartRateBpm;
        this.steps = steps;
        this.distanceMeters = distanceMeters;
        this.location = location;
    }

    public static LiveMeasurementSnapshot empty(int activityId) {
        return new LiveMeasurementSnapshot(activityId, null, null, null, null);
    }

    public int getActivityId() {
        return activityId;
    }

    @Nullable
    public Value<Double> getHeartRateBpm() {
        return heartRateBpm;
    }

    @Nullable
    public Value<Long> getSteps() {
        return steps;
    }

    @Nullable
    public Value<Double> getDistanceMeters() {
        return distanceMeters;
    }

    @Nullable
    public Value<LocationSample> getLocation() {
        return location;
    }
}
