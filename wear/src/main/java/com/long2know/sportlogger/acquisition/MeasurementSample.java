package com.long2know.sportlogger.acquisition;

import java.util.Objects;

public final class MeasurementSample<T> {
    public enum Quality {
        UNKNOWN,
        UNRELIABLE,
        LOW,
        MEDIUM,
        HIGH
    }

    private final ExerciseMetric metric;
    private final T value;
    private final long sequenceNanos;
    private final MeasurementProvenance provenance;
    private final Quality quality;
    private final Long timestampUtcMillis;
    private final Long activeDurationMillis;

    public MeasurementSample(
            ExerciseMetric metric,
            T value,
            long sequenceNanos,
            MeasurementProvenance provenance,
            Quality quality) {
        this(
                metric,
                value,
                sequenceNanos,
                provenance,
                quality,
                null,
                null);
    }

    public MeasurementSample(
            ExerciseMetric metric,
            T value,
            long sequenceNanos,
            MeasurementProvenance provenance,
            Quality quality,
            Long timestampUtcMillis,
            Long activeDurationMillis) {
        this.metric = Objects.requireNonNull(metric);
        this.value = Objects.requireNonNull(value);
        this.provenance = Objects.requireNonNull(provenance);
        this.quality = Objects.requireNonNull(quality);
        if (sequenceNanos < 0L) {
            throw new IllegalArgumentException("Sequence must be monotonic and non-negative");
        }
        if (!metric.accepts(value)) {
            throw new IllegalArgumentException(
                    metric + " requires " + metric.getValueType() + " values");
        }
        if (!metric.getSupportedProvenance().contains(provenance)) {
            throw new IllegalArgumentException(
                    provenance + " is not a valid source for " + metric);
        }
        this.sequenceNanos = sequenceNanos;
        this.timestampUtcMillis = timestampUtcMillis;
        this.activeDurationMillis = activeDurationMillis == null
                ? null
                : Math.max(0L, activeDurationMillis);
    }

    public ExerciseMetric getMetric() {
        return metric;
    }

    public T getValue() {
        return value;
    }

    public long getSequenceNanos() {
        return sequenceNanos;
    }

    public MeasurementProvenance getProvenance() {
        return provenance;
    }

    public Quality getQuality() {
        return quality;
    }

    public Long getTimestampUtcMillis() {
        return timestampUtcMillis;
    }

    public Long getActiveDurationMillis() {
        return activeDurationMillis;
    }
}
