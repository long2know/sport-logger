package com.long2know.sportlogger.acquisition;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MeasurementUnitsTest {
    @Test
    public void paceUsesMillisecondsPerKilometerAndPreservesStoppedZero() {
        assertEquals(
                300D,
                MeasurementUnits.paceMillisecondsPerKilometerToSecondsPerKilometer(300_000D),
                0D);
        assertEquals(
                0D,
                MeasurementUnits.paceMillisecondsPerKilometerToSecondsPerKilometer(0D),
                0D);
    }

    @Test
    public void speedAndDistanceConversionsUseMetricBaseUnits() {
        assertEquals(
                36D,
                MeasurementUnits.metersPerSecondToKilometersPerHour(10D),
                0.000001D);
        assertEquals(5D, MeasurementUnits.metersToKilometers(5_000D), 0D);
    }

    @Test
    public void longCountsSaturateOnlyAtLegacyBoundary() {
        assertEquals(0, MeasurementUnits.countToLegacyInt(-1L));
        assertEquals(12, MeasurementUnits.countToLegacyInt(12L));
        assertEquals(Integer.MAX_VALUE, MeasurementUnits.countToLegacyInt(Long.MAX_VALUE));
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativePaceIsRejectedInsteadOfInventingAZero() {
        MeasurementUnits.paceMillisecondsPerKilometerToSecondsPerKilometer(-1D);
    }
}
