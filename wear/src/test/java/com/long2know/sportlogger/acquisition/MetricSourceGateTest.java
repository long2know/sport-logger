package com.long2know.sportlogger.acquisition;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MetricSourceGateTest {
    @Test
    public void healthServicesSelectionDropsSimultaneousRawSamplesAndDuplicates() {
        MetricSourceGate gate = new MetricSourceGate();
        gate.select(
                ExerciseMetric.HEART_RATE_BPM,
                MeasurementProvenance.HEALTH_SERVICES);

        assertTrue(gate.accept(sample(10L, MeasurementProvenance.HEALTH_SERVICES)));
        assertFalse(gate.accept(sample(11L, MeasurementProvenance.RAW_PLATFORM)));
        assertFalse(gate.accept(sample(10L, MeasurementProvenance.HEALTH_SERVICES)));
        assertTrue(gate.accept(sample(12L, MeasurementProvenance.HEALTH_SERVICES)));
    }

    @Test
    public void explicitFallbackSwitchAllowsRawWithoutDoubleCountingOlderSamples() {
        MetricSourceGate gate = new MetricSourceGate();
        gate.select(
                ExerciseMetric.HEART_RATE_BPM,
                MeasurementProvenance.HEALTH_SERVICES);
        assertTrue(gate.accept(sample(100L, MeasurementProvenance.HEALTH_SERVICES)));

        gate.select(
                ExerciseMetric.HEART_RATE_BPM,
                MeasurementProvenance.RAW_PLATFORM);
        assertFalse(gate.accept(sample(99L, MeasurementProvenance.RAW_PLATFORM)));
        assertTrue(gate.accept(sample(101L, MeasurementProvenance.RAW_PLATFORM)));
    }

    private static MeasurementSample<Double> sample(
            long sequence,
            MeasurementProvenance provenance) {
        return new MeasurementSample<>(
                ExerciseMetric.HEART_RATE_BPM,
                120D,
                sequence,
                provenance,
                MeasurementSample.Quality.UNKNOWN);
    }
}
