package com.long2know.sportlogger.acquisition;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public final class MeasurementCapability {
    public enum Support {
        UNKNOWN,
        SUPPORTED,
        UNSUPPORTED
    }

    public enum PermissionState {
        UNKNOWN,
        NOT_REQUIRED,
        GRANTED,
        DENIED,
        DENIED_PERMANENTLY
    }

    public enum RawHardwareState {
        UNKNOWN,
        PRESENT,
        ABSENT
    }

    public enum VendorState {
        UNKNOWN,
        UNAVAILABLE,
        AVAILABLE_NOT_ENTITLED,
        AVAILABLE_ENTITLED
    }

    public enum LiveAvailability {
        UNKNOWN,
        AVAILABLE,
        ACQUIRING,
        UNAVAILABLE,
        UNAVAILABLE_DEVICE_OFF_BODY,
        NO_GNSS
    }

    private final ExerciseMetric metric;
    private final Support exerciseSupport;
    private final PermissionState permissionState;
    private final RawHardwareState rawHardwareState;
    private final VendorState vendorState;
    private final LiveAvailability liveAvailability;
    private final Set<MeasurementProvenance> activeProvenance;

    public MeasurementCapability(
            ExerciseMetric metric,
            Support exerciseSupport,
            PermissionState permissionState,
            RawHardwareState rawHardwareState,
            VendorState vendorState,
            LiveAvailability liveAvailability,
            Set<MeasurementProvenance> activeProvenance) {
        this.metric = Objects.requireNonNull(metric);
        this.exerciseSupport = Objects.requireNonNull(exerciseSupport);
        this.permissionState = Objects.requireNonNull(permissionState);
        this.rawHardwareState = Objects.requireNonNull(rawHardwareState);
        this.vendorState = Objects.requireNonNull(vendorState);
        this.liveAvailability = Objects.requireNonNull(liveAvailability);
        Objects.requireNonNull(activeProvenance);
        EnumSet<MeasurementProvenance> provenance =
                EnumSet.noneOf(MeasurementProvenance.class);
        provenance.addAll(activeProvenance);
        if (!metric.getSupportedProvenance().containsAll(provenance)) {
            throw new IllegalArgumentException(
                    "Capability contains an invalid source for " + metric);
        }
        this.activeProvenance = Collections.unmodifiableSet(provenance);
    }

    public static MeasurementCapability unknown(ExerciseMetric metric) {
        return new MeasurementCapability(
                metric,
                Support.UNKNOWN,
                PermissionState.UNKNOWN,
                RawHardwareState.UNKNOWN,
                VendorState.UNKNOWN,
                LiveAvailability.UNKNOWN,
                Collections.emptySet());
    }

    public ExerciseMetric getMetric() {
        return metric;
    }

    public Support getExerciseSupport() {
        return exerciseSupport;
    }

    public PermissionState getPermissionState() {
        return permissionState;
    }

    public RawHardwareState getRawHardwareState() {
        return rawHardwareState;
    }

    public VendorState getVendorState() {
        return vendorState;
    }

    public LiveAvailability getLiveAvailability() {
        return liveAvailability;
    }

    public Set<MeasurementProvenance> getActiveProvenance() {
        return activeProvenance;
    }
}
