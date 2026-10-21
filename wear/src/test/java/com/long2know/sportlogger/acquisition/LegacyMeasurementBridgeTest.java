package com.long2know.sportlogger.acquisition;

import com.long2know.sportlogger.measurements.MeasurementEvent;
import com.long2know.utilities.models.LocationData;
import com.long2know.utilities.models.SharedData;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LegacyMeasurementBridgeTest {
    private LegacyMeasurementBridge bridge;

    @Before
    public void setUp() {
        SharedData.getInstance().resetMeasurements();
        bridge = new LegacyMeasurementBridge();
        bridge.beginSession();
    }

    @After
    public void tearDown() {
        SharedData.getInstance().resetMeasurements();
    }

    @Test
    public void healthServicesHeartRateDropsSimultaneousRawStream() {
        bridge.configure(EnumSet.of(ExerciseMetric.HEART_RATE_BPM));

        assertTrue(bridge.applyHealthServices(new MeasurementSample<>(
                ExerciseMetric.HEART_RATE_BPM,
                144D,
                10L,
                MeasurementProvenance.HEALTH_SERVICES,
                MeasurementSample.Quality.HIGH)));
        assertFalse(bridge.applyRaw(MeasurementEvent.heartRate(11L, 99F)));
        assertEquals(144F, SharedData.getInstance().getData().HeartRate, 0F);
    }

    @Test
    public void healthServicesDistanceIsNotOverwrittenByRawRouteFallback() {
        bridge.configure(EnumSet.of(ExerciseMetric.DISTANCE_TOTAL));
        assertTrue(bridge.applyHealthServices(new MeasurementSample<>(
                ExerciseMetric.DISTANCE_TOTAL,
                1_234.5D,
                20L,
                MeasurementProvenance.HEALTH_SERVICES,
                MeasurementSample.Quality.UNKNOWN)));

        LocationData rawLocation = new LocationData();
        rawLocation.Latitude = 0D;
        rawLocation.Longitude = 0D;
        rawLocation.Distance = 50D;
        rawLocation.TotalDistance = 50D;
        assertTrue(bridge.applyRaw(MeasurementEvent.location(21L, rawLocation)));

        LocationData merged = SharedData.getInstance().getData();
        assertEquals(0D, merged.Latitude, 0D);
        assertEquals(0D, merged.Longitude, 0D);
        assertEquals(1_234.5D, merged.TotalDistance, 0D);
    }

    @Test
    public void rawFallbackRemainsActiveWhenHealthServicesHasNoMetric() {
        bridge.configure(Collections.emptySet());

        assertTrue(bridge.applyRaw(MeasurementEvent.steps(30L, 12)));
        assertEquals(12, SharedData.getInstance().getData().Steps);
        assertTrue(bridge.needsRawSensorSource());
        assertTrue(bridge.needsRawLocationSource());
    }

    @Test
    public void rawPressureKeepsHectopascalValueAndProvenance() {
        AtomicReference<MeasurementSample<?>> accepted = new AtomicReference<>();
        LegacyMeasurementBridge pressureBridge =
                new LegacyMeasurementBridge(accepted::set);
        pressureBridge.beginSession();

        assertFalse(pressureBridge.applyRaw(
                MeasurementEvent.pressure(40L, 1_013.25D)));

        assertEquals(ExerciseMetric.PRESSURE, accepted.get().getMetric());
        assertEquals(1_013.25D, (Double) accepted.get().getValue(), 0D);
        assertEquals(
                MeasurementProvenance.RAW_PLATFORM,
                accepted.get().getProvenance());
    }
}
