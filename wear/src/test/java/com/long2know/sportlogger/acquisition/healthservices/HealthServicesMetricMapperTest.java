package com.long2know.sportlogger.acquisition.healthservices;

import androidx.health.services.client.data.DataType;

import com.long2know.sportlogger.acquisition.ExerciseMetric;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class HealthServicesMetricMapperTest {
    @Test
    public void everyIssueSevenHealthServicesIdentifierCompilesAgainst110() {
        assertMapped(ExerciseMetric.HEART_RATE_BPM, DataType.HEART_RATE_BPM);
        assertMapped(ExerciseMetric.STEPS, DataType.STEPS);
        assertMapped(ExerciseMetric.STEPS_TOTAL, DataType.STEPS_TOTAL);
        assertMapped(ExerciseMetric.STEPS_PER_MINUTE, DataType.STEPS_PER_MINUTE);
        assertMapped(ExerciseMetric.DISTANCE, DataType.DISTANCE);
        assertMapped(ExerciseMetric.DISTANCE_TOTAL, DataType.DISTANCE_TOTAL);
        assertMapped(ExerciseMetric.PACE, DataType.PACE);
        assertMapped(ExerciseMetric.SPEED, DataType.SPEED);
        assertMapped(ExerciseMetric.LOCATION, DataType.LOCATION);
        assertMapped(ExerciseMetric.ABSOLUTE_ELEVATION, DataType.ABSOLUTE_ELEVATION);
        assertMapped(ExerciseMetric.ELEVATION_GAIN, DataType.ELEVATION_GAIN);
        assertMapped(ExerciseMetric.ELEVATION_GAIN_TOTAL, DataType.ELEVATION_GAIN_TOTAL);
        assertMapped(ExerciseMetric.ELEVATION_LOSS, DataType.ELEVATION_LOSS);
        assertMapped(ExerciseMetric.ELEVATION_LOSS_TOTAL, DataType.ELEVATION_LOSS_TOTAL);
        assertMapped(ExerciseMetric.CALORIES, DataType.CALORIES);
        assertMapped(ExerciseMetric.CALORIES_TOTAL, DataType.CALORIES_TOTAL);
        assertMapped(ExerciseMetric.FLOORS, DataType.FLOORS);
        assertMapped(ExerciseMetric.FLOORS_TOTAL, DataType.FLOORS_TOTAL);
        assertMapped(ExerciseMetric.VO2_MAX, DataType.VO2_MAX);
        assertMapped(ExerciseMetric.SWIMMING_STROKES, DataType.SWIMMING_STROKES);
        assertMapped(
                ExerciseMetric.SWIMMING_STROKES_TOTAL,
                DataType.SWIMMING_STROKES_TOTAL);
        assertMapped(ExerciseMetric.SWIMMING_LAP_COUNT, DataType.SWIMMING_LAP_COUNT);
        assertMapped(
                ExerciseMetric.SWIMMING_LAP_COUNT_TOTAL,
                DataType.SWIMMING_LAP_COUNT_TOTAL);
        assertMapped(ExerciseMetric.REP_COUNT, DataType.REP_COUNT);
        assertMapped(ExerciseMetric.REP_COUNT_TOTAL, DataType.REP_COUNT_TOTAL);
    }

    @Test
    public void rawAndVendorOnlyMetricsHaveNoInventedHealthServicesType() {
        assertNull(HealthServicesMetricMapper.dataType(ExerciseMetric.PRESSURE));
        assertNull(HealthServicesMetricMapper.dataType(ExerciseMetric.HRV_RMSSD));
        assertNull(HealthServicesMetricMapper.dataType(ExerciseMetric.OXYGEN_SATURATION));
        assertNull(HealthServicesMetricMapper.dataType(ExerciseMetric.SKIN_TEMPERATURE));
    }

    private static void assertMapped(
            ExerciseMetric metric,
            DataType<?, ?> dataType) {
        assertSame(dataType, HealthServicesMetricMapper.dataType(metric));
        assertEquals(metric, HealthServicesMetricMapper.metric(dataType));
    }
}
