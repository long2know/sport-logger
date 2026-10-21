package com.long2know.sportlogger.recording.canonical;

import com.long2know.sportlogger.acquisition.ExerciseMetric;
import com.long2know.sportlogger.acquisition.LocationSample;
import com.long2know.sportlogger.acquisition.MeasurementCapability;
import com.long2know.sportlogger.acquisition.MeasurementProvenance;
import com.long2know.utilities.contract.CanonicalMeasurements;
import com.long2know.utilities.contract.proto.CapabilityState;
import com.long2know.utilities.contract.proto.DoubleMeasurement;
import com.long2know.utilities.contract.proto.LiveAvailability;
import com.long2know.utilities.contract.proto.LongMeasurement;
import com.long2know.utilities.contract.proto.MeasurementStatus;
import com.long2know.utilities.contract.proto.PermissionState;
import com.long2know.utilities.contract.proto.Position;
import com.long2know.utilities.contract.proto.PositionMeasurement;
import com.long2know.utilities.contract.proto.Quality;
import com.long2know.utilities.contract.proto.SourceKind;
import com.long2know.utilities.contract.proto.SourceMetadata;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public final class CanonicalMeasurementMapper {
    private static final int APP_CONTRACT_VERSION = 1;
    private static final Map<ExerciseMetric, CanonicalMeasurements.Metric> METRICS =
            createMetricMap();

    public boolean supports(ExerciseMetric metric) {
        return METRICS.containsKey(metric);
    }

    public com.long2know.utilities.contract.proto.MeasurementSample map(
            com.long2know.sportlogger.acquisition.MeasurementSample<?> sample,
            MeasurementCapability capability,
            Timing timing) {
        Objects.requireNonNull(sample);
        Objects.requireNonNull(timing);
        CanonicalMeasurements.Metric metric = METRICS.get(sample.getMetric());
        if (metric == null) {
            throw new IllegalArgumentException(
                    "Canonical contract does not support " + sample.getMetric());
        }

        CanonicalMeasurements.Dimensions.Builder dimensions =
                CanonicalMeasurements.Dimensions.builder(source(sample))
                        .exerciseCapability(exerciseCapability(capability))
                        .runtimePermission(runtimePermission(capability))
                        .rawHardware(rawHardware(capability))
                        .vendorCapability(vendorCapability(capability))
                        .liveAvailability(liveAvailability(capability))
                        .quality(quality(sample.getQuality()));
        Double platformAccuracy = platformAccuracy(sample);
        if (platformAccuracy != null) {
            dimensions.platformAccuracy(platformAccuracy);
        }
        MeasurementStatus status = CanonicalMeasurements.status(
                metric,
                dimensions.build());

        com.long2know.utilities.contract.proto.MeasurementSample.Builder result =
                com.long2know.utilities.contract.proto.MeasurementSample.newBuilder()
                        .setSequence(timing.sequence)
                        .setTimestampUtcMs(timing.timestampUtcMs);
        if (timing.monotonicTimeMs != null) {
            result.setMonotonicTimeMs(timing.monotonicTimeMs);
        }
        if (timing.bootId != null && !timing.bootId.isEmpty()) {
            result.setBootId(timing.bootId);
        }
        if (timing.sessionElapsedDurationMs != null) {
            result.setSessionElapsedDurationMs(timing.sessionElapsedDurationMs);
        }
        if (timing.sessionActiveDurationMs != null) {
            result.setSessionActiveDurationMs(timing.sessionActiveDurationMs);
        }

        switch (sample.getMetric()) {
            case LOCATION:
                result.setLocation(location(
                        (LocationSample) sample.getValue(),
                        status));
                break;
            case HEART_RATE_BPM:
                result.setHeartRateBpm(doubleMeasurement(sample, status));
                break;
            case STEPS:
                result.setSteps(longMeasurement(sample, status));
                break;
            case STEPS_TOTAL:
                result.setStepsTotal(longMeasurement(sample, status));
                break;
            case STEPS_PER_MINUTE:
                result.setCadenceStepsPerMinute(longMeasurement(sample, status));
                break;
            case DISTANCE:
                result.setDistanceM(doubleMeasurement(sample, status));
                break;
            case DISTANCE_TOTAL:
                result.setDistanceTotalM(doubleMeasurement(sample, status));
                break;
            case PACE:
                result.setPaceMsPerKm(doubleMeasurement(sample, status));
                break;
            case SPEED:
                result.setSpeedMps(doubleMeasurement(sample, status));
                break;
            case ABSOLUTE_ELEVATION:
                result.setAbsoluteElevationM(doubleMeasurement(sample, status));
                break;
            case ELEVATION_GAIN:
                result.setElevationGainM(doubleMeasurement(sample, status));
                break;
            case ELEVATION_GAIN_TOTAL:
                result.setElevationGainTotalM(doubleMeasurement(sample, status));
                break;
            case ELEVATION_LOSS:
                result.setElevationLossM(doubleMeasurement(sample, status));
                break;
            case ELEVATION_LOSS_TOTAL:
                result.setElevationLossTotalM(doubleMeasurement(sample, status));
                break;
            case CALORIES:
                result.setCaloriesKcal(doubleMeasurement(sample, status));
                break;
            case CALORIES_TOTAL:
                result.setCaloriesTotalKcal(doubleMeasurement(sample, status));
                break;
            case FLOORS:
                result.setFloors(doubleMeasurement(sample, status));
                break;
            case FLOORS_TOTAL:
                result.setFloorsTotal(doubleMeasurement(sample, status));
                break;
            case VO2_MAX:
                result.setVo2MaxMlPerKgMin(doubleMeasurement(sample, status));
                break;
            case SWIMMING_STROKES:
                result.setSwimmingStrokes(longMeasurement(sample, status));
                break;
            case SWIMMING_STROKES_TOTAL:
                result.setSwimmingStrokesTotal(longMeasurement(sample, status));
                break;
            case SWIMMING_LAP_COUNT:
                result.setSwimmingLapCount(longMeasurement(sample, status));
                break;
            case SWIMMING_LAP_COUNT_TOTAL:
                result.setSwimmingLapCountTotal(longMeasurement(sample, status));
                break;
            case REP_COUNT:
                result.setRepetitionCount(longMeasurement(sample, status));
                break;
            case REP_COUNT_TOTAL:
                result.setRepetitionCountTotal(longMeasurement(sample, status));
                break;
            case PRESSURE:
                result.setPressureHpa(doubleMeasurement(sample, status));
                break;
            default:
                throw new IllegalArgumentException(
                        "Canonical field mapping is missing for " + sample.getMetric());
        }
        return result.build();
    }

    private static PositionMeasurement location(
            LocationSample sample,
            MeasurementStatus status) {
        Position.Builder position = Position.newBuilder()
                .setLatitudeDegrees(sample.getLatitude())
                .setLongitudeDegrees(sample.getLongitude());
        if (sample.getAltitudeMeters() != null) {
            position.setAltitudeM(sample.getAltitudeMeters());
        }
        if (sample.getHorizontalAccuracyMeters() != null) {
            position.setAccuracyM(sample.getHorizontalAccuracyMeters());
        }
        if (sample.getBearingDegrees() != null) {
            position.setBearingDegrees(sample.getBearingDegrees());
        }
        return PositionMeasurement.newBuilder()
                .setValue(position)
                .setStatus(status)
                .build();
    }

    private static DoubleMeasurement doubleMeasurement(
            com.long2know.sportlogger.acquisition.MeasurementSample<?> sample,
            MeasurementStatus status) {
        return DoubleMeasurement.newBuilder()
                .setValue((Double) sample.getValue())
                .setStatus(status)
                .build();
    }

    private static LongMeasurement longMeasurement(
            com.long2know.sportlogger.acquisition.MeasurementSample<?> sample,
            MeasurementStatus status) {
        return LongMeasurement.newBuilder()
                .setValue((Long) sample.getValue())
                .setStatus(status)
                .build();
    }

    private static SourceMetadata source(
            com.long2know.sportlogger.acquisition.MeasurementSample<?> sample) {
        SourceMetadata.Builder source = SourceMetadata.newBuilder()
                .setKind(sourceKind(sample.getProvenance()))
                .setAppContractVersion(APP_CONTRACT_VERSION);
        switch (sample.getProvenance()) {
            case HEALTH_SERVICES:
                source.setProvider("androidx.health.services");
                break;
            case RAW_PLATFORM:
                source.setProvider(
                        sample.getMetric() == ExerciseMetric.LOCATION
                                ? "android.location"
                                : "android.sensor");
                break;
            case DERIVED:
                source.setProvider("sportlogger");
                break;
            case VENDOR:
                source.setProvider("vendor");
                break;
            default:
                break;
        }
        return source.build();
    }

    private static SourceKind sourceKind(MeasurementProvenance provenance) {
        switch (provenance) {
            case HEALTH_SERVICES:
                return SourceKind.SOURCE_KIND_HEALTH_SERVICES;
            case RAW_PLATFORM:
                return SourceKind.SOURCE_KIND_RAW_PLATFORM;
            case VENDOR:
                return SourceKind.SOURCE_KIND_VENDOR;
            case DERIVED:
                return SourceKind.SOURCE_KIND_DERIVED;
            default:
                return SourceKind.SOURCE_KIND_UNSPECIFIED;
        }
    }

    private static Quality quality(
            com.long2know.sportlogger.acquisition.MeasurementSample.Quality quality) {
        switch (quality) {
            case HIGH:
            case MEDIUM:
                return Quality.QUALITY_VALID;
            case LOW:
                return Quality.QUALITY_DEGRADED;
            case UNRELIABLE:
                return Quality.QUALITY_INVALID;
            case UNKNOWN:
            default:
                return Quality.QUALITY_UNKNOWN;
        }
    }

    private static CapabilityState exerciseCapability(
            MeasurementCapability capability) {
        if (capability == null) {
            return CapabilityState.CAPABILITY_STATE_UNKNOWN;
        }
        switch (capability.getExerciseSupport()) {
            case SUPPORTED:
                return CapabilityState.CAPABILITY_STATE_SUPPORTED;
            case UNSUPPORTED:
                return CapabilityState.CAPABILITY_STATE_UNSUPPORTED;
            case UNKNOWN:
            default:
                return CapabilityState.CAPABILITY_STATE_UNKNOWN;
        }
    }

    private static PermissionState runtimePermission(
            MeasurementCapability capability) {
        if (capability == null) {
            return PermissionState.PERMISSION_STATE_UNKNOWN;
        }
        switch (capability.getPermissionState()) {
            case NOT_REQUIRED:
                return PermissionState.PERMISSION_STATE_NOT_REQUIRED;
            case GRANTED:
                return PermissionState.PERMISSION_STATE_GRANTED;
            case DENIED:
                return PermissionState.PERMISSION_STATE_DENIED;
            case DENIED_PERMANENTLY:
                return PermissionState.PERMISSION_STATE_DENIED_PERMANENTLY;
            case UNKNOWN:
            default:
                return PermissionState.PERMISSION_STATE_UNKNOWN;
        }
    }

    private static CapabilityState rawHardware(MeasurementCapability capability) {
        if (capability == null) {
            return CapabilityState.CAPABILITY_STATE_UNKNOWN;
        }
        switch (capability.getRawHardwareState()) {
            case PRESENT:
                return CapabilityState.CAPABILITY_STATE_SUPPORTED;
            case ABSENT:
                return CapabilityState.CAPABILITY_STATE_UNSUPPORTED;
            case UNKNOWN:
            default:
                return CapabilityState.CAPABILITY_STATE_UNKNOWN;
        }
    }

    private static CapabilityState vendorCapability(
            MeasurementCapability capability) {
        if (capability == null) {
            return CapabilityState.CAPABILITY_STATE_UNKNOWN;
        }
        switch (capability.getVendorState()) {
            case AVAILABLE_ENTITLED:
                return CapabilityState.CAPABILITY_STATE_SUPPORTED;
            case UNAVAILABLE:
                return CapabilityState.CAPABILITY_STATE_UNSUPPORTED;
            case AVAILABLE_NOT_ENTITLED:
            case UNKNOWN:
            default:
                return CapabilityState.CAPABILITY_STATE_UNKNOWN;
        }
    }

    private static LiveAvailability liveAvailability(
            MeasurementCapability capability) {
        if (capability == null) {
            return LiveAvailability.LIVE_AVAILABILITY_UNKNOWN;
        }
        switch (capability.getLiveAvailability()) {
            case AVAILABLE:
                return LiveAvailability.LIVE_AVAILABILITY_AVAILABLE;
            case ACQUIRING:
                return LiveAvailability.LIVE_AVAILABILITY_ACQUIRING;
            case UNAVAILABLE:
                return LiveAvailability.LIVE_AVAILABILITY_UNAVAILABLE;
            case UNAVAILABLE_DEVICE_OFF_BODY:
                return LiveAvailability.LIVE_AVAILABILITY_UNAVAILABLE_DEVICE_OFF_BODY;
            case NO_GNSS:
                return LiveAvailability.LIVE_AVAILABILITY_UNAVAILABLE_NO_GNSS;
            case UNKNOWN:
            default:
                return LiveAvailability.LIVE_AVAILABILITY_UNKNOWN;
        }
    }

    private static Double platformAccuracy(
            com.long2know.sportlogger.acquisition.MeasurementSample<?> sample) {
        if (sample.getMetric() != ExerciseMetric.LOCATION) {
            return null;
        }
        return ((LocationSample) sample.getValue()).getHorizontalAccuracyMeters();
    }

    private static Map<ExerciseMetric, CanonicalMeasurements.Metric> createMetricMap() {
        EnumMap<ExerciseMetric, CanonicalMeasurements.Metric> metrics =
                new EnumMap<>(ExerciseMetric.class);
        metrics.put(ExerciseMetric.LOCATION, CanonicalMeasurements.Metric.LOCATION);
        metrics.put(
                ExerciseMetric.HEART_RATE_BPM,
                CanonicalMeasurements.Metric.HEART_RATE_BPM);
        metrics.put(ExerciseMetric.STEPS, CanonicalMeasurements.Metric.STEPS);
        metrics.put(ExerciseMetric.STEPS_TOTAL, CanonicalMeasurements.Metric.STEPS_TOTAL);
        metrics.put(
                ExerciseMetric.STEPS_PER_MINUTE,
                CanonicalMeasurements.Metric.CADENCE_STEPS_PER_MINUTE);
        metrics.put(ExerciseMetric.DISTANCE, CanonicalMeasurements.Metric.DISTANCE);
        metrics.put(
                ExerciseMetric.DISTANCE_TOTAL,
                CanonicalMeasurements.Metric.DISTANCE_TOTAL);
        metrics.put(ExerciseMetric.PACE, CanonicalMeasurements.Metric.PACE);
        metrics.put(ExerciseMetric.SPEED, CanonicalMeasurements.Metric.SPEED);
        metrics.put(
                ExerciseMetric.ABSOLUTE_ELEVATION,
                CanonicalMeasurements.Metric.ABSOLUTE_ELEVATION);
        metrics.put(
                ExerciseMetric.ELEVATION_GAIN,
                CanonicalMeasurements.Metric.ELEVATION_GAIN);
        metrics.put(
                ExerciseMetric.ELEVATION_GAIN_TOTAL,
                CanonicalMeasurements.Metric.ELEVATION_GAIN_TOTAL);
        metrics.put(
                ExerciseMetric.ELEVATION_LOSS,
                CanonicalMeasurements.Metric.ELEVATION_LOSS);
        metrics.put(
                ExerciseMetric.ELEVATION_LOSS_TOTAL,
                CanonicalMeasurements.Metric.ELEVATION_LOSS_TOTAL);
        metrics.put(ExerciseMetric.CALORIES, CanonicalMeasurements.Metric.CALORIES);
        metrics.put(
                ExerciseMetric.CALORIES_TOTAL,
                CanonicalMeasurements.Metric.CALORIES_TOTAL);
        metrics.put(ExerciseMetric.FLOORS, CanonicalMeasurements.Metric.FLOORS);
        metrics.put(ExerciseMetric.FLOORS_TOTAL, CanonicalMeasurements.Metric.FLOORS_TOTAL);
        metrics.put(ExerciseMetric.VO2_MAX, CanonicalMeasurements.Metric.VO2_MAX);
        metrics.put(
                ExerciseMetric.SWIMMING_STROKES,
                CanonicalMeasurements.Metric.SWIMMING_STROKES);
        metrics.put(
                ExerciseMetric.SWIMMING_STROKES_TOTAL,
                CanonicalMeasurements.Metric.SWIMMING_STROKES_TOTAL);
        metrics.put(
                ExerciseMetric.SWIMMING_LAP_COUNT,
                CanonicalMeasurements.Metric.SWIMMING_LAP_COUNT);
        metrics.put(
                ExerciseMetric.SWIMMING_LAP_COUNT_TOTAL,
                CanonicalMeasurements.Metric.SWIMMING_LAP_COUNT_TOTAL);
        metrics.put(
                ExerciseMetric.REP_COUNT,
                CanonicalMeasurements.Metric.REPETITION_COUNT);
        metrics.put(
                ExerciseMetric.REP_COUNT_TOTAL,
                CanonicalMeasurements.Metric.REPETITION_COUNT_TOTAL);
        metrics.put(ExerciseMetric.PRESSURE, CanonicalMeasurements.Metric.PRESSURE);
        return metrics;
    }

    public static final class Timing {
        private final long sequence;
        private final long timestampUtcMs;
        private final Long monotonicTimeMs;
        private final String bootId;
        private final Long sessionElapsedDurationMs;
        private final Long sessionActiveDurationMs;

        public Timing(
                long sequence,
                long timestampUtcMs,
                Long monotonicTimeMs,
                String bootId,
                Long sessionElapsedDurationMs,
                Long sessionActiveDurationMs) {
            if (sequence < 0L) {
                throw new IllegalArgumentException("Sequence cannot be negative");
            }
            this.sequence = sequence;
            this.timestampUtcMs = timestampUtcMs;
            this.monotonicTimeMs = monotonicTimeMs;
            this.bootId = bootId;
            this.sessionElapsedDurationMs = sessionElapsedDurationMs;
            this.sessionActiveDurationMs = sessionActiveDurationMs;
        }
    }
}
