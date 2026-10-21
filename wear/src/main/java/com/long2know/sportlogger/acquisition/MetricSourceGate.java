package com.long2know.sportlogger.acquisition;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public final class MetricSourceGate {
    private final Map<ExerciseMetric, MeasurementProvenance> selectedSources =
            new EnumMap<>(ExerciseMetric.class);
    private final Map<ExerciseMetric, Long> lastSequences =
            new EnumMap<>(ExerciseMetric.class);

    public synchronized void select(
            ExerciseMetric metric,
            MeasurementProvenance provenance) {
        Objects.requireNonNull(metric);
        Objects.requireNonNull(provenance);
        if (!metric.getSupportedProvenance().contains(provenance)) {
            throw new IllegalArgumentException(
                    provenance + " cannot provide " + metric);
        }
        selectedSources.put(metric, provenance);
    }

    public synchronized void clear(ExerciseMetric metric) {
        selectedSources.remove(Objects.requireNonNull(metric));
    }

    public synchronized MeasurementProvenance selectedSource(ExerciseMetric metric) {
        return selectedSources.get(metric);
    }

    public synchronized boolean accept(MeasurementSample<?> sample) {
        Objects.requireNonNull(sample);
        ExerciseMetric metric = sample.getMetric();
        if (selectedSources.get(metric) != sample.getProvenance()) {
            return false;
        }
        long sequence = sample.getSequenceNanos();
        Long previous = lastSequences.get(metric);
        if (previous != null && sequence <= previous) {
            return false;
        }
        lastSequences.put(metric, sequence);
        return true;
    }
}
