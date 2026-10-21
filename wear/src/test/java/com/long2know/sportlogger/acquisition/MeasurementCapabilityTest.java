package com.long2know.sportlogger.acquisition;

import org.junit.Test;

import java.util.Collections;
import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MeasurementCapabilityTest {
    @Test
    public void capabilityPermissionHardwareVendorLiveAndProvenanceRemainIndependent() {
        MeasurementCapability capability = new MeasurementCapability(
                ExerciseMetric.HEART_RATE_BPM,
                MeasurementCapability.Support.SUPPORTED,
                MeasurementCapability.PermissionState.DENIED,
                MeasurementCapability.RawHardwareState.PRESENT,
                MeasurementCapability.VendorState.UNAVAILABLE,
                MeasurementCapability.LiveAvailability.UNAVAILABLE_DEVICE_OFF_BODY,
                EnumSet.of(MeasurementProvenance.RAW_PLATFORM));

        assertEquals(
                MeasurementCapability.Support.SUPPORTED,
                capability.getExerciseSupport());
        assertEquals(
                MeasurementCapability.PermissionState.DENIED,
                capability.getPermissionState());
        assertEquals(
                MeasurementCapability.RawHardwareState.PRESENT,
                capability.getRawHardwareState());
        assertEquals(
                MeasurementCapability.VendorState.UNAVAILABLE,
                capability.getVendorState());
        assertEquals(
                MeasurementCapability.LiveAvailability.UNAVAILABLE_DEVICE_OFF_BODY,
                capability.getLiveAvailability());
        assertTrue(
                capability.getActiveProvenance().contains(
                        MeasurementProvenance.RAW_PLATFORM));
    }

    @Test
    public void unsupportedMetricRemainsExplicitlyUnsupportedWithNoFakeSource() {
        MeasurementCapability capability = new MeasurementCapability(
                ExerciseMetric.VO2_MAX,
                MeasurementCapability.Support.UNSUPPORTED,
                MeasurementCapability.PermissionState.NOT_REQUIRED,
                MeasurementCapability.RawHardwareState.ABSENT,
                MeasurementCapability.VendorState.UNAVAILABLE,
                MeasurementCapability.LiveAvailability.UNAVAILABLE,
                Collections.emptySet());

        assertEquals(
                MeasurementCapability.Support.UNSUPPORTED,
                capability.getExerciseSupport());
        assertTrue(capability.getActiveProvenance().isEmpty());
    }
}
