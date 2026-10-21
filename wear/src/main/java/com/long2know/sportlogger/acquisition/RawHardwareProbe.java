package com.long2know.sportlogger.acquisition;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public final class RawHardwareProbe {
    private RawHardwareProbe() {
    }

    public static Map<ExerciseMetric, MeasurementCapability.RawHardwareState> probe(
            Context context) {
        Context applicationContext = context.getApplicationContext();
        SensorManager sensorManager =
                (SensorManager) applicationContext.getSystemService(Context.SENSOR_SERVICE);
        boolean heartRate = hasSensor(sensorManager, Sensor.TYPE_HEART_RATE);
        boolean steps = hasSensor(sensorManager, Sensor.TYPE_STEP_COUNTER)
                || hasSensor(sensorManager, Sensor.TYPE_STEP_DETECTOR);
        boolean pressure = hasSensor(sensorManager, Sensor.TYPE_PRESSURE);
        boolean gps = applicationContext.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS);
        return statesFor(heartRate, steps, pressure, gps);
    }

    static Map<ExerciseMetric, MeasurementCapability.RawHardwareState> statesFor(
            boolean heartRate,
            boolean steps,
            boolean pressure,
            boolean gps) {
        EnumMap<ExerciseMetric, MeasurementCapability.RawHardwareState> states =
                new EnumMap<>(ExerciseMetric.class);
        for (ExerciseMetric metric : ExerciseMetric.values()) {
            states.put(
                    metric,
                    metric.isCoreMetric()
                            ? MeasurementCapability.RawHardwareState.ABSENT
                            : MeasurementCapability.RawHardwareState.UNKNOWN);
        }
        set(states, heartRate, ExerciseMetric.HEART_RATE_BPM);
        set(
                states,
                steps,
                ExerciseMetric.STEPS,
                ExerciseMetric.STEPS_TOTAL,
                ExerciseMetric.STEPS_PER_MINUTE);
        set(states, pressure, ExerciseMetric.PRESSURE);
        set(
                states,
                gps,
                ExerciseMetric.LOCATION,
                ExerciseMetric.DISTANCE,
                ExerciseMetric.DISTANCE_TOTAL,
                ExerciseMetric.PACE,
                ExerciseMetric.SPEED,
                ExerciseMetric.ABSOLUTE_ELEVATION,
                ExerciseMetric.ELEVATION_GAIN,
                ExerciseMetric.ELEVATION_GAIN_TOTAL,
                ExerciseMetric.ELEVATION_LOSS,
                ExerciseMetric.ELEVATION_LOSS_TOTAL);
        return Collections.unmodifiableMap(states);
    }

    private static boolean hasSensor(SensorManager sensorManager, int sensorType) {
        return sensorManager != null && sensorManager.getDefaultSensor(sensorType) != null;
    }

    private static void set(
            EnumMap<ExerciseMetric, MeasurementCapability.RawHardwareState> states,
            boolean present,
            ExerciseMetric... metrics) {
        MeasurementCapability.RawHardwareState state = present
                ? MeasurementCapability.RawHardwareState.PRESENT
                : MeasurementCapability.RawHardwareState.ABSENT;
        for (ExerciseMetric metric : metrics) {
            states.put(metric, state);
        }
    }
}
