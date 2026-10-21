package com.long2know.sportlogger.acquisition.healthservices;

import com.long2know.sportlogger.acquisition.ExerciseMetric;
import com.long2know.sportlogger.acquisition.MeasurementCapability;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class HealthServicesExerciseSnapshot {
    public enum State {
        UNINITIALIZED,
        READY,
        STARTING,
        ACTIVE,
        PAUSED,
        ENDING,
        RAW_FALLBACK,
        BLOCKED_BY_OTHER_APP,
        ERROR,
        CLOSED
    }

    public enum Ownership {
        UNKNOWN,
        NONE,
        OWNED_BY_THIS_APP,
        OWNED_BY_OTHER_APP
    }

    private final State state;
    private final Ownership ownership;
    private final Map<ExerciseMetric, MeasurementCapability> capabilities;
    private final Set<ExerciseMetric> activeHealthServicesMetrics;
    private final HealthServicesCapabilityDimensions capabilityDimensions;
    private final String detail;

    public HealthServicesExerciseSnapshot(
            State state,
            Ownership ownership,
            Map<ExerciseMetric, MeasurementCapability> capabilities,
            Set<ExerciseMetric> activeHealthServicesMetrics,
            String detail) {
        this(
                state,
                ownership,
                capabilities,
                activeHealthServicesMetrics,
                HealthServicesCapabilityDimensions.empty(),
                detail);
    }

    public HealthServicesExerciseSnapshot(
            State state,
            Ownership ownership,
            Map<ExerciseMetric, MeasurementCapability> capabilities,
            Set<ExerciseMetric> activeHealthServicesMetrics,
            HealthServicesCapabilityDimensions capabilityDimensions,
            String detail) {
        this.state = Objects.requireNonNull(state);
        this.ownership = Objects.requireNonNull(ownership);
        Objects.requireNonNull(capabilities);
        Objects.requireNonNull(activeHealthServicesMetrics);
        EnumMap<ExerciseMetric, MeasurementCapability> capabilityCopy =
                new EnumMap<>(ExerciseMetric.class);
        capabilityCopy.putAll(capabilities);
        this.capabilities = Collections.unmodifiableMap(capabilityCopy);
        EnumSet<ExerciseMetric> metricCopy = EnumSet.noneOf(ExerciseMetric.class);
        metricCopy.addAll(activeHealthServicesMetrics);
        this.activeHealthServicesMetrics = Collections.unmodifiableSet(metricCopy);
        this.capabilityDimensions = Objects.requireNonNull(capabilityDimensions);
        this.detail = detail == null ? "" : detail;
    }

    public static HealthServicesExerciseSnapshot uninitialized() {
        return new HealthServicesExerciseSnapshot(
                State.UNINITIALIZED,
                Ownership.UNKNOWN,
                Collections.emptyMap(),
                Collections.emptySet(),
                "");
    }

    public State getState() {
        return state;
    }

    public Ownership getOwnership() {
        return ownership;
    }

    public Map<ExerciseMetric, MeasurementCapability> getCapabilities() {
        return capabilities;
    }

    public Set<ExerciseMetric> getActiveHealthServicesMetrics() {
        return activeHealthServicesMetrics;
    }

    public HealthServicesCapabilityDimensions getCapabilityDimensions() {
        return capabilityDimensions;
    }

    public String getDetail() {
        return detail;
    }
}
