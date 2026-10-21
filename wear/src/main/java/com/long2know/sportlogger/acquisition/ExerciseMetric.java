package com.long2know.sportlogger.acquisition;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum ExerciseMetric {
    HEART_RATE_BPM(
            "HEART_RATE_BPM",
            ValueType.DOUBLE,
            Unit.BEATS_PER_MINUTE,
            PermissionDomain.HEART_RATE,
            true,
            MeasurementProvenance.HEALTH_SERVICES,
            MeasurementProvenance.RAW_PLATFORM),
    STEPS(
            "STEPS",
            ValueType.LONG,
            Unit.COUNT,
            PermissionDomain.ACTIVITY_RECOGNITION,
            true,
            MeasurementProvenance.HEALTH_SERVICES,
            MeasurementProvenance.RAW_PLATFORM),
    STEPS_TOTAL(
            "STEPS_TOTAL",
            ValueType.LONG,
            Unit.COUNT,
            PermissionDomain.ACTIVITY_RECOGNITION,
            true,
            MeasurementProvenance.HEALTH_SERVICES,
            MeasurementProvenance.RAW_PLATFORM,
            MeasurementProvenance.DERIVED),
    STEPS_PER_MINUTE(
            "STEPS_PER_MINUTE",
            ValueType.LONG,
            Unit.STEPS_PER_MINUTE,
            PermissionDomain.ACTIVITY_RECOGNITION,
            true,
            MeasurementProvenance.HEALTH_SERVICES,
            MeasurementProvenance.DERIVED),
    DISTANCE(
            "DISTANCE",
            ValueType.DOUBLE,
            Unit.METERS,
            PermissionDomain.ACTIVITY_RECOGNITION,
            true,
            MeasurementProvenance.HEALTH_SERVICES,
            MeasurementProvenance.DERIVED),
    DISTANCE_TOTAL(
            "DISTANCE_TOTAL",
            ValueType.DOUBLE,
            Unit.METERS,
            PermissionDomain.ACTIVITY_RECOGNITION,
            true,
            MeasurementProvenance.HEALTH_SERVICES,
            MeasurementProvenance.DERIVED),
    PACE(
            "PACE",
            ValueType.DOUBLE,
            Unit.MILLISECONDS_PER_KILOMETER,
            PermissionDomain.ACTIVITY_RECOGNITION,
            true,
            MeasurementProvenance.HEALTH_SERVICES,
            MeasurementProvenance.DERIVED),
    SPEED(
            "SPEED",
            ValueType.DOUBLE,
            Unit.METERS_PER_SECOND,
            PermissionDomain.ACTIVITY_RECOGNITION,
            true,
            MeasurementProvenance.HEALTH_SERVICES,
            MeasurementProvenance.RAW_PLATFORM,
            MeasurementProvenance.DERIVED),
    LOCATION(
            "LOCATION",
            ValueType.LOCATION,
            Unit.DEGREES,
            PermissionDomain.FINE_LOCATION,
            true,
            MeasurementProvenance.HEALTH_SERVICES,
            MeasurementProvenance.RAW_PLATFORM),
    ABSOLUTE_ELEVATION(
            "ABSOLUTE_ELEVATION",
            ValueType.DOUBLE,
            Unit.METERS,
            PermissionDomain.FINE_LOCATION,
            true,
            MeasurementProvenance.HEALTH_SERVICES,
            MeasurementProvenance.RAW_PLATFORM),
    ELEVATION_GAIN(
            "ELEVATION_GAIN",
            ValueType.DOUBLE,
            Unit.METERS,
            PermissionDomain.ACTIVITY_RECOGNITION,
            true,
            MeasurementProvenance.HEALTH_SERVICES,
            MeasurementProvenance.DERIVED),
    ELEVATION_GAIN_TOTAL(
            "ELEVATION_GAIN_TOTAL",
            ValueType.DOUBLE,
            Unit.METERS,
            PermissionDomain.ACTIVITY_RECOGNITION,
            true,
            MeasurementProvenance.HEALTH_SERVICES,
            MeasurementProvenance.DERIVED),
    ELEVATION_LOSS(
            "ELEVATION_LOSS",
            ValueType.DOUBLE,
            Unit.METERS,
            PermissionDomain.ACTIVITY_RECOGNITION,
            true,
            MeasurementProvenance.HEALTH_SERVICES,
            MeasurementProvenance.DERIVED),
    ELEVATION_LOSS_TOTAL(
            "ELEVATION_LOSS_TOTAL",
            ValueType.DOUBLE,
            Unit.METERS,
            PermissionDomain.ACTIVITY_RECOGNITION,
            true,
            MeasurementProvenance.HEALTH_SERVICES,
            MeasurementProvenance.DERIVED),
    CALORIES(
            "CALORIES",
            ValueType.DOUBLE,
            Unit.KILOCALORIES,
            PermissionDomain.ACTIVITY_RECOGNITION,
            true,
            MeasurementProvenance.HEALTH_SERVICES),
    CALORIES_TOTAL(
            "CALORIES_TOTAL",
            ValueType.DOUBLE,
            Unit.KILOCALORIES,
            PermissionDomain.ACTIVITY_RECOGNITION,
            true,
            MeasurementProvenance.HEALTH_SERVICES),
    FLOORS(
            "FLOORS",
            ValueType.DOUBLE,
            Unit.FLOORS,
            PermissionDomain.ACTIVITY_RECOGNITION,
            true,
            MeasurementProvenance.HEALTH_SERVICES),
    FLOORS_TOTAL(
            "FLOORS_TOTAL",
            ValueType.DOUBLE,
            Unit.FLOORS,
            PermissionDomain.ACTIVITY_RECOGNITION,
            true,
            MeasurementProvenance.HEALTH_SERVICES),
    VO2_MAX(
            "VO2_MAX",
            ValueType.DOUBLE,
            Unit.MILLILITERS_PER_KILOGRAM_PER_MINUTE,
            PermissionDomain.DEVICE_DEFINED,
            true,
            MeasurementProvenance.HEALTH_SERVICES),
    SWIMMING_STROKES(
            "SWIMMING_STROKES",
            ValueType.LONG,
            Unit.COUNT,
            PermissionDomain.ACTIVITY_RECOGNITION,
            true,
            MeasurementProvenance.HEALTH_SERVICES),
    SWIMMING_STROKES_TOTAL(
            "SWIMMING_STROKES_TOTAL",
            ValueType.LONG,
            Unit.COUNT,
            PermissionDomain.ACTIVITY_RECOGNITION,
            true,
            MeasurementProvenance.HEALTH_SERVICES),
    SWIMMING_LAP_COUNT(
            "SWIMMING_LAP_COUNT",
            ValueType.LONG,
            Unit.COUNT,
            PermissionDomain.ACTIVITY_RECOGNITION,
            true,
            MeasurementProvenance.HEALTH_SERVICES),
    SWIMMING_LAP_COUNT_TOTAL(
            "SWIMMING_LAP_COUNT_TOTAL",
            ValueType.LONG,
            Unit.COUNT,
            PermissionDomain.ACTIVITY_RECOGNITION,
            true,
            MeasurementProvenance.HEALTH_SERVICES),
    REP_COUNT(
            "REP_COUNT",
            ValueType.LONG,
            Unit.COUNT,
            PermissionDomain.ACTIVITY_RECOGNITION,
            true,
            MeasurementProvenance.HEALTH_SERVICES),
    REP_COUNT_TOTAL(
            "REP_COUNT_TOTAL",
            ValueType.LONG,
            Unit.COUNT,
            PermissionDomain.ACTIVITY_RECOGNITION,
            true,
            MeasurementProvenance.HEALTH_SERVICES),
    PRESSURE(
            null,
            ValueType.DOUBLE,
            Unit.HECTOPASCALS,
            PermissionDomain.NONE,
            true,
            MeasurementProvenance.RAW_PLATFORM),
    HRV_RMSSD(
            null,
            ValueType.DOUBLE,
            Unit.MILLISECONDS,
            PermissionDomain.VENDOR,
            false,
            MeasurementProvenance.VENDOR,
            MeasurementProvenance.DERIVED),
    OXYGEN_SATURATION(
            null,
            ValueType.DOUBLE,
            Unit.PERCENT,
            PermissionDomain.VENDOR,
            false,
            MeasurementProvenance.VENDOR),
    SKIN_TEMPERATURE(
            null,
            ValueType.DOUBLE,
            Unit.CELSIUS,
            PermissionDomain.VENDOR,
            false,
            MeasurementProvenance.VENDOR);

    public enum ValueType {
        DOUBLE,
        LONG,
        LOCATION
    }

    public enum Unit {
        BEATS_PER_MINUTE,
        COUNT,
        STEPS_PER_MINUTE,
        METERS,
        MILLISECONDS_PER_KILOMETER,
        METERS_PER_SECOND,
        DEGREES,
        KILOCALORIES,
        FLOORS,
        MILLILITERS_PER_KILOGRAM_PER_MINUTE,
        HECTOPASCALS,
        MILLISECONDS,
        PERCENT,
        CELSIUS
    }

    public enum PermissionDomain {
        NONE,
        ACTIVITY_RECOGNITION,
        HEART_RATE,
        FINE_LOCATION,
        DEVICE_DEFINED,
        VENDOR
    }

    private final String healthServicesIdentifier;
    private final ValueType valueType;
    private final Unit unit;
    private final PermissionDomain permissionDomain;
    private final boolean coreMetric;
    private final Set<MeasurementProvenance> supportedProvenance;

    ExerciseMetric(
            String healthServicesIdentifier,
            ValueType valueType,
            Unit unit,
            PermissionDomain permissionDomain,
            boolean coreMetric,
            MeasurementProvenance... supportedProvenance) {
        this.healthServicesIdentifier = healthServicesIdentifier;
        this.valueType = valueType;
        this.unit = unit;
        this.permissionDomain = permissionDomain;
        this.coreMetric = coreMetric;
        EnumSet<MeasurementProvenance> provenance = EnumSet.noneOf(MeasurementProvenance.class);
        Collections.addAll(provenance, supportedProvenance);
        this.supportedProvenance = Collections.unmodifiableSet(provenance);
    }

    public String getHealthServicesIdentifier() {
        return healthServicesIdentifier;
    }

    public ValueType getValueType() {
        return valueType;
    }

    public Unit getUnit() {
        return unit;
    }

    public PermissionDomain getPermissionDomain() {
        return permissionDomain;
    }

    public boolean isCoreMetric() {
        return coreMetric;
    }

    public boolean isHealthServicesMetric() {
        return healthServicesIdentifier != null;
    }

    public Set<MeasurementProvenance> getSupportedProvenance() {
        return supportedProvenance;
    }

    boolean accepts(Object value) {
        switch (valueType) {
            case DOUBLE:
                return value instanceof Double;
            case LONG:
                return value instanceof Long;
            case LOCATION:
                return value instanceof LocationSample;
            default:
                return false;
        }
    }
}
