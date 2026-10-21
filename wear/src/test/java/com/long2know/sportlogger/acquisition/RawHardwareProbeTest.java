package com.long2know.sportlogger.acquisition;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;

public class RawHardwareProbeTest {
    @Test
    public void rawHardwareDimensionIsIndependentFromHealthServicesSupport() {
        Map<ExerciseMetric, MeasurementCapability.RawHardwareState> states =
                RawHardwareProbe.statesFor(true, false, true, true);

        assertEquals(
                MeasurementCapability.RawHardwareState.PRESENT,
                states.get(ExerciseMetric.HEART_RATE_BPM));
        assertEquals(
                MeasurementCapability.RawHardwareState.ABSENT,
                states.get(ExerciseMetric.STEPS));
        assertEquals(
                MeasurementCapability.RawHardwareState.PRESENT,
                states.get(ExerciseMetric.PRESSURE));
        assertEquals(
                MeasurementCapability.RawHardwareState.PRESENT,
                states.get(ExerciseMetric.LOCATION));
        assertEquals(
                MeasurementCapability.RawHardwareState.UNKNOWN,
                states.get(ExerciseMetric.OXYGEN_SATURATION));
    }
}
