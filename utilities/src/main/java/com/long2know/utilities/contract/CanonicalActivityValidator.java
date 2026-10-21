package com.long2know.utilities.contract;

import com.long2know.utilities.contract.proto.ActiveInterval;
import com.long2know.utilities.contract.proto.ActivityRecord;
import com.long2know.utilities.contract.proto.ActivitySummary;
import com.long2know.utilities.contract.proto.AggregationSemantics;
import com.long2know.utilities.contract.proto.CapabilityState;
import com.long2know.utilities.contract.proto.DoubleMeasurement;
import com.long2know.utilities.contract.proto.Lap;
import com.long2know.utilities.contract.proto.LiveAvailability;
import com.long2know.utilities.contract.proto.LongMeasurement;
import com.long2know.utilities.contract.proto.MeasurementSample;
import com.long2know.utilities.contract.proto.MeasurementStatus;
import com.long2know.utilities.contract.proto.PermissionState;
import com.long2know.utilities.contract.proto.Position;
import com.long2know.utilities.contract.proto.Quality;
import com.long2know.utilities.contract.proto.SourceKind;

import java.util.HashSet;
import java.util.Set;

public final class CanonicalActivityValidator {
    private CanonicalActivityValidator() {
    }

    public static void validateCompleted(ActivityRecord activity) {
        if (activity == null) {
            fail("ACTIVITY_REQUIRED", "Activity is required");
        }
        CanonicalIds.requireUuid(activity.getSessionId(), "Session identifier");
        if (activity.getWatchRevision() == 0L) {
            fail("REVISION_INVALID", "Completed activity revision must be positive");
        }
        if (activity.getStartTimeUtcMs() <= 0L) {
            fail("START_TIME_INVALID", "Activity start time must be positive");
        }
        if (activity.getEndTimeUtcMs() < activity.getStartTimeUtcMs()) {
            fail("END_TIME_INVALID", "Activity end time precedes its start");
        }
        if (activity.getElapsedDurationMs() < 0L) {
            fail("DURATION_INVALID", "Elapsed duration cannot be negative");
        }
        if (activity.hasActiveDurationMs()
                && (activity.getActiveDurationMs() < 0L
                || activity.getActiveDurationMs() > activity.getElapsedDurationMs())) {
            fail("ACTIVE_DURATION_INVALID", "Active duration exceeds elapsed duration");
        }
        if (!activity.hasSource()
                || activity.getSource().getKind() == SourceKind.SOURCE_KIND_UNSPECIFIED
                || activity.getSource().getAppContractVersion() == 0) {
            fail("SOURCE_INVALID", "Activity source and contract version are required");
        }
        if (activity.hasSummary()) {
            validateSummary(activity.getSummary(), "activity summary");
        }
        Set<String> lapIds = new HashSet<>();
        for (Lap lap : activity.getLapsList()) {
            if (lap.getLapId().isEmpty() || !lapIds.add(lap.getLapId())) {
                fail("LAP_ID_INVALID", "Lap identifiers must be non-empty and unique");
            }
            validateInterval(
                    lap.getStartTimeUtcMs(),
                    lap.getEndTimeUtcMs(),
                    lap.getElapsedDurationMs(),
                    lap.hasActiveDurationMs() ? lap.getActiveDurationMs() : null,
                    "lap");
            if (lap.hasSummary()) {
                validateSummary(lap.getSummary(), "lap summary");
            }
        }
        long lastIntervalEnd = Long.MIN_VALUE;
        for (ActiveInterval interval : activity.getActiveIntervalsList()) {
            validateInterval(
                    interval.getStartTimeUtcMs(),
                    interval.getEndTimeUtcMs(),
                    interval.getEndTimeUtcMs() - interval.getStartTimeUtcMs(),
                    interval.getActiveDurationMs(),
                    "active interval");
            if (interval.getStartTimeUtcMs() < lastIntervalEnd) {
                fail("ACTIVE_INTERVAL_ORDER", "Active intervals overlap or are unordered");
            }
            lastIntervalEnd = interval.getEndTimeUtcMs();
        }
        long lastSequence = -1L;
        for (MeasurementSample sample : activity.getSamplesList()) {
            if (sample.getSequence() <= lastSequence) {
                fail("SAMPLE_SEQUENCE_INVALID", "Sample sequences must be strictly increasing");
            }
            lastSequence = sample.getSequence();
            validateSample(sample);
        }
    }

    private static void validateInterval(
            long start,
            long end,
            long elapsed,
            Long active,
            String label) {
        if (start <= 0L || end < start || elapsed < 0L) {
            fail("INTERVAL_INVALID", "Invalid " + label + " time range");
        }
        if (active != null && (active < 0L || active > elapsed)) {
            fail("INTERVAL_ACTIVE_INVALID", "Invalid " + label + " active duration");
        }
    }

    private static void validateSummary(ActivitySummary summary, String label) {
        validateNonNegative(summary.hasDistanceM() ? summary.getDistanceM() : null, label);
        validateNonNegative(summary.hasCaloriesKcal() ? summary.getCaloriesKcal() : null, label);
        validateNonNegative(summary.hasSteps() ? summary.getSteps() : null, label);
        validateNonNegative(
                summary.hasElevationGainM() ? summary.getElevationGainM() : null,
                label);
        validateNonNegative(
                summary.hasElevationLossM() ? summary.getElevationLossM() : null,
                label);
        validateNonNegative(summary.hasFloors() ? summary.getFloors() : null, label);
        validateNonNegative(
                summary.hasAverageHeartRateBpm()
                        ? summary.getAverageHeartRateBpm()
                        : null,
                label);
        validateNonNegative(
                summary.hasMaximumHeartRateBpm()
                        ? summary.getMaximumHeartRateBpm()
                        : null,
                label);
    }

    public static void validateSample(MeasurementSample sample) {
        if (sample.getTimestampUtcMs() <= 0L) {
            fail("SAMPLE_TIMESTAMP_INVALID", "Sample timestamp must be positive");
        }
        if (sample.hasMonotonicTimeMs() && sample.getMonotonicTimeMs() < 0L) {
            fail("MONOTONIC_TIME_INVALID", "Monotonic sample time cannot be negative");
        }
        if (sample.hasSessionElapsedDurationMs()
                && sample.getSessionElapsedDurationMs() < 0L) {
            fail("SAMPLE_ELAPSED_INVALID", "Sample elapsed duration cannot be negative");
        }
        if (sample.hasSessionActiveDurationMs()
                && sample.getSessionActiveDurationMs() < 0L) {
            fail("SAMPLE_ACTIVE_INVALID", "Sample active duration cannot be negative");
        }
        if (sample.hasLocation()) {
            validateStatus(sample.getLocation().getStatus());
            if (sample.getLocation().hasValue()) {
                Position position = sample.getLocation().getValue();
                requireFinite(position.getLatitudeDegrees(), "latitude");
                requireFinite(position.getLongitudeDegrees(), "longitude");
                if (position.getLatitudeDegrees() < -90D
                        || position.getLatitudeDegrees() > 90D
                        || position.getLongitudeDegrees() < -180D
                        || position.getLongitudeDegrees() > 180D) {
                    fail("POSITION_RANGE_INVALID", "Position is outside WGS84 bounds");
                }
                if (position.hasAltitudeM()) {
                    requireFinite(position.getAltitudeM(), "altitude");
                }
                if (position.hasAccuracyM()) {
                    requireNonNegativeFinite(position.getAccuracyM(), "accuracy");
                }
                if (position.hasBearingDegrees()) {
                    requireFinite(position.getBearingDegrees(), "bearing");
                }
            }
        }
        validateNonNegative(sample.hasHeartRateBpm() ? sample.getHeartRateBpm() : null, "sample");
        validateNonNegative(sample.hasSteps() ? sample.getSteps() : null, "sample");
        validateNonNegative(sample.hasStepsTotal() ? sample.getStepsTotal() : null, "sample");
        validateNonNegative(
                sample.hasCadenceStepsPerMinute()
                        ? sample.getCadenceStepsPerMinute()
                        : null,
                "sample");
        validateNonNegative(sample.hasDistanceM() ? sample.getDistanceM() : null, "sample");
        validateNonNegative(
                sample.hasDistanceTotalM() ? sample.getDistanceTotalM() : null,
                "sample");
        validateNonNegative(sample.hasPaceMsPerKm() ? sample.getPaceMsPerKm() : null, "sample");
        validateNonNegative(sample.hasSpeedMps() ? sample.getSpeedMps() : null, "sample");
        validateFinite(
                sample.hasAbsoluteElevationM()
                        ? sample.getAbsoluteElevationM()
                        : null,
                "sample");
        validateNonNegative(
                sample.hasElevationGainM() ? sample.getElevationGainM() : null,
                "sample");
        validateNonNegative(
                sample.hasElevationGainTotalM()
                        ? sample.getElevationGainTotalM()
                        : null,
                "sample");
        validateNonNegative(
                sample.hasElevationLossM() ? sample.getElevationLossM() : null,
                "sample");
        validateNonNegative(
                sample.hasElevationLossTotalM()
                        ? sample.getElevationLossTotalM()
                        : null,
                "sample");
        validateNonNegative(sample.hasCaloriesKcal() ? sample.getCaloriesKcal() : null, "sample");
        validateNonNegative(
                sample.hasCaloriesTotalKcal() ? sample.getCaloriesTotalKcal() : null,
                "sample");
        validateNonNegative(sample.hasFloors() ? sample.getFloors() : null, "sample");
        validateNonNegative(sample.hasFloorsTotal() ? sample.getFloorsTotal() : null, "sample");
        validateNonNegative(
                sample.hasVo2MaxMlPerKgMin() ? sample.getVo2MaxMlPerKgMin() : null,
                "sample");
        validateNonNegative(
                sample.hasSwimmingStrokes() ? sample.getSwimmingStrokes() : null,
                "sample");
        validateNonNegative(
                sample.hasSwimmingStrokesTotal()
                        ? sample.getSwimmingStrokesTotal()
                        : null,
                "sample");
        validateNonNegative(
                sample.hasSwimmingLapCount() ? sample.getSwimmingLapCount() : null,
                "sample");
        validateNonNegative(
                sample.hasSwimmingLapCountTotal()
                        ? sample.getSwimmingLapCountTotal()
                        : null,
                "sample");
        validateNonNegative(
                sample.hasRepetitionCount() ? sample.getRepetitionCount() : null,
                "sample");
        validateNonNegative(
                sample.hasRepetitionCountTotal()
                        ? sample.getRepetitionCountTotal()
                        : null,
                "sample");
        validateNonNegative(sample.hasPressureHpa() ? sample.getPressureHpa() : null, "sample");
    }

    private static void validateNonNegative(DoubleMeasurement value, String label) {
        if (value == null) {
            return;
        }
        validateStatus(value.getStatus());
        if (value.hasValue()) {
            requireNonNegativeFinite(value.getValue(), label);
        }
    }

    private static void validateNonNegative(LongMeasurement value, String label) {
        if (value == null) {
            return;
        }
        validateStatus(value.getStatus());
        if (value.hasValue() && value.getValue() < 0L) {
            fail("MEASUREMENT_NEGATIVE", label + " measurement cannot be negative");
        }
    }

    private static void validateFinite(DoubleMeasurement value, String label) {
        if (value == null) {
            return;
        }
        validateStatus(value.getStatus());
        if (value.hasValue()) {
            requireFinite(value.getValue(), label);
        }
    }

    private static void validateStatus(MeasurementStatus status) {
        if (status.getMetricIdentifier().isEmpty() || status.getPublicUnit().isEmpty()) {
            fail("MEASUREMENT_IDENTITY_REQUIRED", "Measurement identifier and unit are required");
        }
        if (status.getAggregation()
                == AggregationSemantics.AGGREGATION_SEMANTICS_UNSPECIFIED) {
            fail("MEASUREMENT_AGGREGATION_REQUIRED", "Measurement aggregation is required");
        }
        if (status.getExerciseCapability() == CapabilityState.CAPABILITY_STATE_UNSPECIFIED
                || status.getRuntimePermission() == PermissionState.PERMISSION_STATE_UNSPECIFIED
                || status.getRawHardware() == CapabilityState.CAPABILITY_STATE_UNSPECIFIED
                || status.getVendorCapability() == CapabilityState.CAPABILITY_STATE_UNSPECIFIED
                || status.getLiveAvailability()
                == LiveAvailability.LIVE_AVAILABILITY_UNSPECIFIED
                || status.getQuality() == Quality.QUALITY_UNSPECIFIED
                || !status.hasSource()
                || status.getSource().getKind() == SourceKind.SOURCE_KIND_UNSPECIFIED) {
            fail(
                    "MEASUREMENT_STATUS_INCOMPLETE",
                    "Measurement capability, permission, availability, provenance, and quality"
                            + " dimensions are required");
        }
        if (status.hasPlatformAccuracy()) {
            requireNonNegativeFinite(status.getPlatformAccuracy(), "platform accuracy");
        }
    }

    private static void requireNonNegativeFinite(double value, String label) {
        requireFinite(value, label);
        if (value < 0D) {
            fail("MEASUREMENT_NEGATIVE", label + " measurement cannot be negative");
        }
    }

    private static void requireFinite(double value, String label) {
        if (!Double.isFinite(value)) {
            fail("MEASUREMENT_NOT_FINITE", label + " measurement must be finite");
        }
    }

    private static void fail(String code, String message) {
        throw new CanonicalContractException(code, message);
    }
}
