package com.long2know.sportlogger.recording.canonical;

import com.long2know.sportlogger.acquisition.ExerciseMetric;
import com.long2know.sportlogger.acquisition.LocationSample;
import com.long2know.sportlogger.acquisition.MeasurementCapability;
import com.long2know.sportlogger.acquisition.MeasurementProvenance;
import com.long2know.sportlogger.acquisition.MeasurementSample;
import com.long2know.utilities.contract.proto.LiveAvailability;
import com.long2know.utilities.contract.proto.SourceKind;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CanonicalMeasurementMapperTest {
    private final CanonicalMeasurementMapper mapper =
            new CanonicalMeasurementMapper();

    @Test
    public void zeroCoordinatesRemainPresentWhileOptionalFieldsRemainAbsent() {
        MeasurementSample<LocationSample> sample = new MeasurementSample<>(
                ExerciseMetric.LOCATION,
                new LocationSample(0D, 0D, null, null, null),
                5_000_000L,
                MeasurementProvenance.HEALTH_SERVICES,
                MeasurementSample.Quality.HIGH);

        com.long2know.utilities.contract.proto.MeasurementSample mapped =
                mapper.map(sample, supported(ExerciseMetric.LOCATION), timing(1L));

        assertTrue(mapped.hasLocation());
        assertTrue(mapped.getLocation().hasValue());
        assertEquals(0D, mapped.getLocation().getValue().getLatitudeDegrees(), 0D);
        assertEquals(0D, mapped.getLocation().getValue().getLongitudeDegrees(), 0D);
        assertFalse(mapped.getLocation().getValue().hasAltitudeM());
        assertFalse(mapped.getLocation().getValue().hasAccuracyM());
        assertEquals(
                SourceKind.SOURCE_KIND_HEALTH_SERVICES,
                mapped.getLocation().getStatus().getSource().getKind());
    }

    @Test
    public void paceZeroAndFractionalFloorsRemainRecordedValues() {
        com.long2know.utilities.contract.proto.MeasurementSample pace =
                mapper.map(
                        new MeasurementSample<>(
                                ExerciseMetric.PACE,
                                0D,
                                6_000_000L,
                                MeasurementProvenance.HEALTH_SERVICES,
                                MeasurementSample.Quality.UNKNOWN),
                        supported(ExerciseMetric.PACE),
                        timing(2L));
        com.long2know.utilities.contract.proto.MeasurementSample floors =
                mapper.map(
                        new MeasurementSample<>(
                                ExerciseMetric.FLOORS,
                                2.5D,
                                7_000_000L,
                                MeasurementProvenance.HEALTH_SERVICES,
                                MeasurementSample.Quality.UNKNOWN),
                        supported(ExerciseMetric.FLOORS),
                        timing(3L));

        assertTrue(pace.getPaceMsPerKm().hasValue());
        assertEquals(0D, pace.getPaceMsPerKm().getValue(), 0D);
        assertEquals(2.5D, floors.getFloors().getValue(), 0D);
    }

    @Test
    public void unsupportedVendorMetricsStayExplicitlyUnsupported() {
        assertFalse(mapper.supports(ExerciseMetric.HRV_RMSSD));
        assertFalse(mapper.supports(ExerciseMetric.OXYGEN_SATURATION));
        assertFalse(mapper.supports(ExerciseMetric.SKIN_TEMPERATURE));
        assertTrue(mapper.supports(ExerciseMetric.SWIMMING_LAP_COUNT_TOTAL));
    }

    private static MeasurementCapability supported(ExerciseMetric metric) {
        return new MeasurementCapability(
                metric,
                MeasurementCapability.Support.SUPPORTED,
                MeasurementCapability.PermissionState.GRANTED,
                MeasurementCapability.RawHardwareState.UNKNOWN,
                MeasurementCapability.VendorState.UNAVAILABLE,
                MeasurementCapability.LiveAvailability.AVAILABLE,
                Collections.singleton(MeasurementProvenance.HEALTH_SERVICES));
    }

    private static CanonicalMeasurementMapper.Timing timing(long sequence) {
        return new CanonicalMeasurementMapper.Timing(
                sequence,
                1_000L + sequence,
                50_000L + sequence,
                "boot-1",
                sequence * 100L,
                sequence * 90L);
    }
}
