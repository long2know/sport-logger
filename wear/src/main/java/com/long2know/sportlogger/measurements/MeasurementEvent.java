package com.long2know.sportlogger.measurements;

import com.long2know.utilities.models.LocationData;

import java.util.Objects;

public final class MeasurementEvent {
    public enum Kind {
        LOCATION,
        HEART_RATE,
        STEPS
    }

    private final Kind kind;
    private final long sequence;
    private final LocationData location;
    private final float heartRate;
    private final int steps;

    private MeasurementEvent(
            Kind kind,
            long sequence,
            LocationData location,
            float heartRate,
            int steps) {
        this.kind = Objects.requireNonNull(kind);
        this.sequence = sequence;
        this.location = location;
        this.heartRate = heartRate;
        this.steps = steps;
    }

    public static MeasurementEvent location(long sequence, LocationData location) {
        return new MeasurementEvent(
                Kind.LOCATION,
                sequence,
                Objects.requireNonNull(location),
                0F,
                0);
    }

    public static MeasurementEvent heartRate(long sequence, float heartRate) {
        return new MeasurementEvent(Kind.HEART_RATE, sequence, null, heartRate, 0);
    }

    public static MeasurementEvent steps(long sequence, int steps) {
        return new MeasurementEvent(Kind.STEPS, sequence, null, 0F, steps);
    }

    public Kind getKind() {
        return kind;
    }

    public long getSequence() {
        return sequence;
    }

    public LocationData getLocation() {
        return location;
    }

    public float getHeartRate() {
        return heartRate;
    }

    public int getSteps() {
        return steps;
    }
}
