package com.long2know.sportlogger.acquisition.healthservices;

import androidx.health.services.client.data.DataType;
import androidx.health.services.client.data.ExerciseCapabilities;
import androidx.health.services.client.data.ExerciseType;
import androidx.health.services.client.data.ExerciseTypeCapabilities;
import androidx.health.services.client.data.MeasureCapabilities;
import androidx.health.services.client.data.PassiveMonitoringCapabilities;

import com.long2know.sportlogger.acquisition.ExerciseMetric;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class HealthServicesCapabilityDimensions {
    private final Map<ExerciseType, Set<ExerciseMetric>> exerciseMetrics;
    private final Set<ExerciseMetric> measureMetrics;
    private final Set<ExerciseMetric> passiveMetrics;
    private final Set<ExerciseMetric> passiveGoalMetrics;

    public HealthServicesCapabilityDimensions(
            ExerciseCapabilities exerciseCapabilities,
            MeasureCapabilities measureCapabilities,
            PassiveMonitoringCapabilities passiveCapabilities) {
        Map<ExerciseType, Set<ExerciseMetric>> exercises = new HashMap<>();
        if (exerciseCapabilities != null) {
            for (Map.Entry<ExerciseType, ExerciseTypeCapabilities> entry
                    : exerciseCapabilities.getTypeToCapabilities().entrySet()) {
                exercises.put(
                        entry.getKey(),
                        map(entry.getValue().getSupportedDataTypes()));
            }
        }
        this.exerciseMetrics = Collections.unmodifiableMap(exercises);
        this.measureMetrics = measureCapabilities == null
                ? Collections.emptySet()
                : map(measureCapabilities.getSupportedDataTypesMeasure());
        this.passiveMetrics = passiveCapabilities == null
                ? Collections.emptySet()
                : map(passiveCapabilities.getSupportedDataTypesPassiveMonitoring());
        this.passiveGoalMetrics = passiveCapabilities == null
                ? Collections.emptySet()
                : map(passiveCapabilities.getSupportedDataTypesPassiveGoals());
    }

    private HealthServicesCapabilityDimensions() {
        this.exerciseMetrics = Collections.emptyMap();
        this.measureMetrics = Collections.emptySet();
        this.passiveMetrics = Collections.emptySet();
        this.passiveGoalMetrics = Collections.emptySet();
    }

    public static HealthServicesCapabilityDimensions empty() {
        return new HealthServicesCapabilityDimensions();
    }

    public Map<ExerciseType, Set<ExerciseMetric>> getExerciseMetrics() {
        return exerciseMetrics;
    }

    public Set<ExerciseMetric> getExerciseMetrics(ExerciseType exerciseType) {
        Set<ExerciseMetric> metrics = exerciseMetrics.get(exerciseType);
        return metrics == null ? Collections.emptySet() : metrics;
    }

    public Set<ExerciseMetric> getMeasureMetrics() {
        return measureMetrics;
    }

    public Set<ExerciseMetric> getPassiveMetrics() {
        return passiveMetrics;
    }

    public Set<ExerciseMetric> getPassiveGoalMetrics() {
        return passiveGoalMetrics;
    }

    private static Set<ExerciseMetric> map(
            Set<? extends DataType<?, ?>> dataTypes) {
        EnumSet<ExerciseMetric> metrics = EnumSet.noneOf(ExerciseMetric.class);
        for (DataType<?, ?> dataType : dataTypes) {
            ExerciseMetric metric = HealthServicesMetricMapper.metric(dataType);
            if (metric != null) {
                metrics.add(metric);
            }
        }
        return Collections.unmodifiableSet(metrics);
    }
}
