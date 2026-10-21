package com.long2know.utilities.contract;

import com.long2know.utilities.contract.proto.AggregationSemantics;
import com.long2know.utilities.contract.proto.CapabilityState;
import com.long2know.utilities.contract.proto.LiveAvailability;
import com.long2know.utilities.contract.proto.MeasurementStatus;
import com.long2know.utilities.contract.proto.PermissionState;
import com.long2know.utilities.contract.proto.Quality;
import com.long2know.utilities.contract.proto.SourceMetadata;

import java.util.Objects;

public final class CanonicalMeasurements {
    private CanonicalMeasurements() {
    }

    public static MeasurementStatus status(Metric metric, Dimensions dimensions) {
        Objects.requireNonNull(metric);
        Objects.requireNonNull(dimensions);
        MeasurementStatus.Builder status = MeasurementStatus.newBuilder()
                .setMetricIdentifier(metric.identifier)
                .setPublicUnit(metric.unit)
                .setAggregation(metric.aggregation)
                .setExerciseCapability(dimensions.exerciseCapability)
                .setRuntimePermission(dimensions.runtimePermission)
                .setRawHardware(dimensions.rawHardware)
                .setVendorCapability(dimensions.vendorCapability)
                .setLiveAvailability(dimensions.liveAvailability)
                .setSource(dimensions.source)
                .setQuality(dimensions.quality);
        if (dimensions.platformAccuracy != null) {
            status.setPlatformAccuracy(dimensions.platformAccuracy);
        }
        return status.build();
    }

    public enum Metric {
        LOCATION(
                "LOCATION",
                "degrees",
                AggregationSemantics.AGGREGATION_SEMANTICS_INSTANTANEOUS),
        HEART_RATE_BPM(
                "HEART_RATE_BPM",
                "beats/minute",
                AggregationSemantics.AGGREGATION_SEMANTICS_INSTANTANEOUS),
        STEPS(
                "STEPS",
                "count",
                AggregationSemantics.AGGREGATION_SEMANTICS_DELTA),
        STEPS_TOTAL(
                "STEPS_TOTAL",
                "count",
                AggregationSemantics.AGGREGATION_SEMANTICS_CUMULATIVE),
        CADENCE_STEPS_PER_MINUTE(
                "STEPS_PER_MINUTE",
                "steps/minute",
                AggregationSemantics.AGGREGATION_SEMANTICS_INSTANTANEOUS),
        DISTANCE(
                "DISTANCE",
                "metres",
                AggregationSemantics.AGGREGATION_SEMANTICS_DELTA),
        DISTANCE_TOTAL(
                "DISTANCE_TOTAL",
                "metres",
                AggregationSemantics.AGGREGATION_SEMANTICS_CUMULATIVE),
        PACE(
                "PACE",
                "milliseconds/kilometre",
                AggregationSemantics.AGGREGATION_SEMANTICS_INSTANTANEOUS),
        SPEED(
                "SPEED",
                "metres/second",
                AggregationSemantics.AGGREGATION_SEMANTICS_INSTANTANEOUS),
        ABSOLUTE_ELEVATION(
                "ABSOLUTE_ELEVATION",
                "metres",
                AggregationSemantics.AGGREGATION_SEMANTICS_INSTANTANEOUS),
        ELEVATION_GAIN(
                "ELEVATION_GAIN",
                "metres",
                AggregationSemantics.AGGREGATION_SEMANTICS_DELTA),
        ELEVATION_GAIN_TOTAL(
                "ELEVATION_GAIN_TOTAL",
                "metres",
                AggregationSemantics.AGGREGATION_SEMANTICS_CUMULATIVE),
        ELEVATION_LOSS(
                "ELEVATION_LOSS",
                "metres",
                AggregationSemantics.AGGREGATION_SEMANTICS_DELTA),
        ELEVATION_LOSS_TOTAL(
                "ELEVATION_LOSS_TOTAL",
                "metres",
                AggregationSemantics.AGGREGATION_SEMANTICS_CUMULATIVE),
        CALORIES(
                "CALORIES",
                "kilocalories",
                AggregationSemantics.AGGREGATION_SEMANTICS_DELTA),
        CALORIES_TOTAL(
                "CALORIES_TOTAL",
                "kilocalories",
                AggregationSemantics.AGGREGATION_SEMANTICS_CUMULATIVE),
        FLOORS(
                "FLOORS",
                "floors",
                AggregationSemantics.AGGREGATION_SEMANTICS_DELTA),
        FLOORS_TOTAL(
                "FLOORS_TOTAL",
                "floors",
                AggregationSemantics.AGGREGATION_SEMANTICS_CUMULATIVE),
        VO2_MAX(
                "VO2_MAX",
                "millilitres/kilogram/minute",
                AggregationSemantics.AGGREGATION_SEMANTICS_INSTANTANEOUS),
        SWIMMING_STROKES(
                "SWIMMING_STROKES",
                "count",
                AggregationSemantics.AGGREGATION_SEMANTICS_DELTA),
        SWIMMING_STROKES_TOTAL(
                "SWIMMING_STROKES_TOTAL",
                "count",
                AggregationSemantics.AGGREGATION_SEMANTICS_CUMULATIVE),
        SWIMMING_LAP_COUNT(
                "SWIMMING_LAP_COUNT",
                "count",
                AggregationSemantics.AGGREGATION_SEMANTICS_DELTA),
        SWIMMING_LAP_COUNT_TOTAL(
                "SWIMMING_LAP_COUNT_TOTAL",
                "count",
                AggregationSemantics.AGGREGATION_SEMANTICS_CUMULATIVE),
        REPETITION_COUNT(
                "REP_COUNT",
                "count",
                AggregationSemantics.AGGREGATION_SEMANTICS_DELTA),
        REPETITION_COUNT_TOTAL(
                "REP_COUNT_TOTAL",
                "count",
                AggregationSemantics.AGGREGATION_SEMANTICS_CUMULATIVE),
        PRESSURE(
                "PRESSURE",
                "hectopascals",
                AggregationSemantics.AGGREGATION_SEMANTICS_INSTANTANEOUS);

        private final String identifier;
        private final String unit;
        private final AggregationSemantics aggregation;

        Metric(String identifier, String unit, AggregationSemantics aggregation) {
            this.identifier = identifier;
            this.unit = unit;
            this.aggregation = aggregation;
        }

        public String getIdentifier() {
            return identifier;
        }

        public String getUnit() {
            return unit;
        }

        public AggregationSemantics getAggregation() {
            return aggregation;
        }
    }

    public static final class Dimensions {
        private final CapabilityState exerciseCapability;
        private final PermissionState runtimePermission;
        private final CapabilityState rawHardware;
        private final CapabilityState vendorCapability;
        private final LiveAvailability liveAvailability;
        private final SourceMetadata source;
        private final Quality quality;
        private final Double platformAccuracy;

        private Dimensions(Builder builder) {
            exerciseCapability = builder.exerciseCapability;
            runtimePermission = builder.runtimePermission;
            rawHardware = builder.rawHardware;
            vendorCapability = builder.vendorCapability;
            liveAvailability = builder.liveAvailability;
            source = Objects.requireNonNull(builder.source);
            quality = builder.quality;
            platformAccuracy = builder.platformAccuracy;
        }

        public static Builder builder(SourceMetadata source) {
            return new Builder(source);
        }

        public static final class Builder {
            private CapabilityState exerciseCapability =
                    CapabilityState.CAPABILITY_STATE_UNKNOWN;
            private PermissionState runtimePermission =
                    PermissionState.PERMISSION_STATE_UNKNOWN;
            private CapabilityState rawHardware =
                    CapabilityState.CAPABILITY_STATE_UNKNOWN;
            private CapabilityState vendorCapability =
                    CapabilityState.CAPABILITY_STATE_UNKNOWN;
            private LiveAvailability liveAvailability =
                    LiveAvailability.LIVE_AVAILABILITY_UNKNOWN;
            private final SourceMetadata source;
            private Quality quality = Quality.QUALITY_UNKNOWN;
            private Double platformAccuracy;

            private Builder(SourceMetadata source) {
                this.source = Objects.requireNonNull(source);
            }

            public Builder exerciseCapability(CapabilityState value) {
                exerciseCapability = Objects.requireNonNull(value);
                return this;
            }

            public Builder runtimePermission(PermissionState value) {
                runtimePermission = Objects.requireNonNull(value);
                return this;
            }

            public Builder rawHardware(CapabilityState value) {
                rawHardware = Objects.requireNonNull(value);
                return this;
            }

            public Builder vendorCapability(CapabilityState value) {
                vendorCapability = Objects.requireNonNull(value);
                return this;
            }

            public Builder liveAvailability(LiveAvailability value) {
                liveAvailability = Objects.requireNonNull(value);
                return this;
            }

            public Builder quality(Quality value) {
                quality = Objects.requireNonNull(value);
                return this;
            }

            public Builder platformAccuracy(double value) {
                platformAccuracy = value;
                return this;
            }

            public Dimensions build() {
                return new Dimensions(this);
            }
        }
    }
}
