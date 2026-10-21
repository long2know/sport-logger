package com.long2know.sportlogger.acquisition;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ExerciseMetricTest {
    @Test
    public void issueSevenMetricsExposeExactPublicUnitsAndValueTypes() {
        assertMetric(
                ExerciseMetric.HEART_RATE_BPM,
                "HEART_RATE_BPM",
                ExerciseMetric.ValueType.DOUBLE,
                ExerciseMetric.Unit.BEATS_PER_MINUTE);
        assertMetric(
                ExerciseMetric.STEPS,
                "STEPS",
                ExerciseMetric.ValueType.LONG,
                ExerciseMetric.Unit.COUNT);
        assertMetric(
                ExerciseMetric.STEPS_PER_MINUTE,
                "STEPS_PER_MINUTE",
                ExerciseMetric.ValueType.LONG,
                ExerciseMetric.Unit.STEPS_PER_MINUTE);
        assertMetric(
                ExerciseMetric.DISTANCE,
                "DISTANCE",
                ExerciseMetric.ValueType.DOUBLE,
                ExerciseMetric.Unit.METERS);
        assertMetric(
                ExerciseMetric.PACE,
                "PACE",
                ExerciseMetric.ValueType.DOUBLE,
                ExerciseMetric.Unit.MILLISECONDS_PER_KILOMETER);
        assertMetric(
                ExerciseMetric.SPEED,
                "SPEED",
                ExerciseMetric.ValueType.DOUBLE,
                ExerciseMetric.Unit.METERS_PER_SECOND);
        assertMetric(
                ExerciseMetric.LOCATION,
                "LOCATION",
                ExerciseMetric.ValueType.LOCATION,
                ExerciseMetric.Unit.DEGREES);
        assertMetric(
                ExerciseMetric.FLOORS,
                "FLOORS",
                ExerciseMetric.ValueType.DOUBLE,
                ExerciseMetric.Unit.FLOORS);
        assertMetric(
                ExerciseMetric.CALORIES,
                "CALORIES",
                ExerciseMetric.ValueType.DOUBLE,
                ExerciseMetric.Unit.KILOCALORIES);
        assertMetric(
                ExerciseMetric.VO2_MAX,
                "VO2_MAX",
                ExerciseMetric.ValueType.DOUBLE,
                ExerciseMetric.Unit.MILLILITERS_PER_KILOGRAM_PER_MINUTE);
        assertMetric(
                ExerciseMetric.SWIMMING_LAP_COUNT_TOTAL,
                "SWIMMING_LAP_COUNT_TOTAL",
                ExerciseMetric.ValueType.LONG,
                ExerciseMetric.Unit.COUNT);
        assertMetric(
                ExerciseMetric.REP_COUNT_TOTAL,
                "REP_COUNT_TOTAL",
                ExerciseMetric.ValueType.LONG,
                ExerciseMetric.Unit.COUNT);
    }

    @Test
    public void optionalVendorMetricsAreNotCoreOrClaimedAsHealthServicesTypes() {
        assertOptionalVendor(ExerciseMetric.HRV_RMSSD);
        assertOptionalVendor(ExerciseMetric.OXYGEN_SATURATION);
        assertOptionalVendor(ExerciseMetric.SKIN_TEMPERATURE);
        assertNull(ExerciseMetric.PRESSURE.getHealthServicesIdentifier());
        assertTrue(ExerciseMetric.PRESSURE.isCoreMetric());
    }

    @Test
    public void zeroZeroLocationIsAValidPresentSample() {
        LocationSample location = new LocationSample(0D, 0D, null, null, null);
        MeasurementSample<LocationSample> sample = new MeasurementSample<>(
                ExerciseMetric.LOCATION,
                location,
                1L,
                MeasurementProvenance.HEALTH_SERVICES,
                MeasurementSample.Quality.UNKNOWN);

        assertEquals(0D, sample.getValue().getLatitude(), 0D);
        assertEquals(0D, sample.getValue().getLongitude(), 0D);
    }

    private static void assertMetric(
            ExerciseMetric metric,
            String identifier,
            ExerciseMetric.ValueType valueType,
            ExerciseMetric.Unit unit) {
        assertEquals(identifier, metric.getHealthServicesIdentifier());
        assertEquals(valueType, metric.getValueType());
        assertEquals(unit, metric.getUnit());
        assertTrue(metric.isCoreMetric());
        assertTrue(metric.isHealthServicesMetric());
    }

    private static void assertOptionalVendor(ExerciseMetric metric) {
        assertFalse(metric.isCoreMetric());
        assertFalse(metric.isHealthServicesMetric());
        assertTrue(metric.getSupportedProvenance().contains(MeasurementProvenance.VENDOR));
    }
}
