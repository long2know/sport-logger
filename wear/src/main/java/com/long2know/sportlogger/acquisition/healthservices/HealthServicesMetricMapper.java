package com.long2know.sportlogger.acquisition.healthservices;

import androidx.health.services.client.data.DataType;

import com.long2know.sportlogger.acquisition.ExerciseMetric;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

public final class HealthServicesMetricMapper {
    private static final Map<ExerciseMetric, DataType<?, ?>> METRIC_TO_DATA_TYPE;
    private static final Map<DataType<?, ?>, ExerciseMetric> DATA_TYPE_TO_METRIC;

    static {
        EnumMap<ExerciseMetric, DataType<?, ?>> metrics =
                new EnumMap<>(ExerciseMetric.class);
        metrics.put(ExerciseMetric.HEART_RATE_BPM, DataType.HEART_RATE_BPM);
        metrics.put(ExerciseMetric.STEPS, DataType.STEPS);
        metrics.put(ExerciseMetric.STEPS_TOTAL, DataType.STEPS_TOTAL);
        metrics.put(ExerciseMetric.STEPS_PER_MINUTE, DataType.STEPS_PER_MINUTE);
        metrics.put(ExerciseMetric.DISTANCE, DataType.DISTANCE);
        metrics.put(ExerciseMetric.DISTANCE_TOTAL, DataType.DISTANCE_TOTAL);
        metrics.put(ExerciseMetric.PACE, DataType.PACE);
        metrics.put(ExerciseMetric.SPEED, DataType.SPEED);
        metrics.put(ExerciseMetric.LOCATION, DataType.LOCATION);
        metrics.put(ExerciseMetric.ABSOLUTE_ELEVATION, DataType.ABSOLUTE_ELEVATION);
        metrics.put(ExerciseMetric.ELEVATION_GAIN, DataType.ELEVATION_GAIN);
        metrics.put(ExerciseMetric.ELEVATION_GAIN_TOTAL, DataType.ELEVATION_GAIN_TOTAL);
        metrics.put(ExerciseMetric.ELEVATION_LOSS, DataType.ELEVATION_LOSS);
        metrics.put(ExerciseMetric.ELEVATION_LOSS_TOTAL, DataType.ELEVATION_LOSS_TOTAL);
        metrics.put(ExerciseMetric.CALORIES, DataType.CALORIES);
        metrics.put(ExerciseMetric.CALORIES_TOTAL, DataType.CALORIES_TOTAL);
        metrics.put(ExerciseMetric.FLOORS, DataType.FLOORS);
        metrics.put(ExerciseMetric.FLOORS_TOTAL, DataType.FLOORS_TOTAL);
        metrics.put(ExerciseMetric.VO2_MAX, DataType.VO2_MAX);
        metrics.put(ExerciseMetric.SWIMMING_STROKES, DataType.SWIMMING_STROKES);
        metrics.put(
                ExerciseMetric.SWIMMING_STROKES_TOTAL,
                DataType.SWIMMING_STROKES_TOTAL);
        metrics.put(ExerciseMetric.SWIMMING_LAP_COUNT, DataType.SWIMMING_LAP_COUNT);
        metrics.put(
                ExerciseMetric.SWIMMING_LAP_COUNT_TOTAL,
                DataType.SWIMMING_LAP_COUNT_TOTAL);
        metrics.put(ExerciseMetric.REP_COUNT, DataType.REP_COUNT);
        metrics.put(ExerciseMetric.REP_COUNT_TOTAL, DataType.REP_COUNT_TOTAL);
        METRIC_TO_DATA_TYPE = Collections.unmodifiableMap(metrics);

        Map<DataType<?, ?>, ExerciseMetric> dataTypes = new HashMap<>();
        for (Map.Entry<ExerciseMetric, DataType<?, ?>> entry : metrics.entrySet()) {
            dataTypes.put(entry.getValue(), entry.getKey());
        }
        DATA_TYPE_TO_METRIC = Collections.unmodifiableMap(dataTypes);
    }

    private HealthServicesMetricMapper() {
    }

    public static Map<ExerciseMetric, DataType<?, ?>> all() {
        return METRIC_TO_DATA_TYPE;
    }

    public static DataType<?, ?> dataType(ExerciseMetric metric) {
        return METRIC_TO_DATA_TYPE.get(metric);
    }

    public static ExerciseMetric metric(DataType<?, ?> dataType) {
        return DATA_TYPE_TO_METRIC.get(dataType);
    }
}
