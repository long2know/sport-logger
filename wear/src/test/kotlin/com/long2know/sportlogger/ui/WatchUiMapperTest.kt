package com.long2know.sportlogger.ui

import com.long2know.sportlogger.acquisition.ExerciseMetric
import com.long2know.sportlogger.acquisition.MeasurementCapability
import com.long2know.sportlogger.acquisition.MeasurementProvenance
import com.long2know.sportlogger.acquisition.RouteStatus
import com.long2know.sportlogger.acquisition.healthservices.HealthServicesCapabilityDimensions
import com.long2know.sportlogger.acquisition.healthservices.HealthServicesExerciseSnapshot
import com.long2know.sportlogger.recording.RecordingAuthority
import com.long2know.sportlogger.recording.RecordingSnapshot
import com.long2know.sportlogger.recording.RecordingState
import com.long2know.utilities.contract.proto.ActivityType
import com.long2know.utilities.models.LocationData
import com.long2know.utilities.models.SharedData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class WatchUiMapperTest {
    private val sharedData = SharedData.getInstance()

    @Before
    fun setUp() {
        sharedData.resetMeasurements()
    }

    @After
    fun tearDown() {
        sharedData.resetMeasurements()
    }

    @Test
    fun availableZeroValuesIncludingZeroCoordinatesRemainPresent() {
        sharedData.setHeartRate(0f)
        sharedData.setSteps(0)
        sharedData.setLocation(
            LocationData().apply {
                Latitude = 0.0
                Longitude = 0.0
                TotalDistance = 0.0
            },
        )
        val snapshot = snapshot(
            capability(ExerciseMetric.HEART_RATE_BPM),
            capability(ExerciseMetric.STEPS_TOTAL),
            capability(ExerciseMetric.DISTANCE_TOTAL),
            capability(ExerciseMetric.LOCATION),
        )

        val metrics = WatchUiMapper.liveMetrics(
            sharedData,
            snapshot,
            RouteStatus.AVAILABLE,
        )

        assertEquals(
            MetricValueUi.Decimal(0.0),
            metrics.heartRate.value,
        )
        assertEquals(
            MetricValueUi.Count(0L),
            metrics.steps.value,
        )
        assertEquals(
            MetricValueUi.Decimal(0.0),
            metrics.distance.value,
        )
        assertEquals(
            MetricValueUi.Coordinates(0.0, 0.0),
            metrics.location.value,
        )
    }

    @Test
    fun defaultZeroesStayMissingWhileMetricsAreAcquiring() {
        val snapshot = snapshot(
            capability(
                ExerciseMetric.HEART_RATE_BPM,
                live = MeasurementCapability.LiveAvailability.ACQUIRING,
            ),
            capability(
                ExerciseMetric.STEPS_TOTAL,
                live = MeasurementCapability.LiveAvailability.ACQUIRING,
            ),
            capability(
                ExerciseMetric.DISTANCE_TOTAL,
                live = MeasurementCapability.LiveAvailability.ACQUIRING,
            ),
            capability(
                ExerciseMetric.LOCATION,
                live = MeasurementCapability.LiveAvailability.ACQUIRING,
            ),
        )

        val metrics = WatchUiMapper.liveMetrics(
            sharedData,
            snapshot,
            RouteStatus.ACQUIRING,
        )

        assertNull(metrics.heartRate.value)
        assertNull(metrics.steps.value)
        assertNull(metrics.distance.value)
        assertNull(metrics.location.value)
        assertEquals(MetricAvailabilityUi.ACQUIRING, metrics.heartRate.availability)
        assertEquals(MetricAvailabilityUi.ACQUIRING, metrics.location.availability)
    }

    @Test
    fun readinessDistinguishesDeniedUnsupportedOffBodyAndNoLocation() {
        assertEquals(
            MetricAvailabilityUi.PERMISSION_DENIED,
            WatchUiMapper.availability(
                capability(
                    ExerciseMetric.HEART_RATE_BPM,
                    permission = MeasurementCapability.PermissionState.DENIED,
                ),
            ),
        )
        assertEquals(
            MetricAvailabilityUi.UNSUPPORTED,
            WatchUiMapper.availability(
                capability(
                    ExerciseMetric.HEART_RATE_BPM,
                    support = MeasurementCapability.Support.UNSUPPORTED,
                    raw = MeasurementCapability.RawHardwareState.ABSENT,
                    live = MeasurementCapability.LiveAvailability.UNKNOWN,
                ),
            ),
        )
        assertEquals(
            MetricAvailabilityUi.OFF_BODY,
            WatchUiMapper.availability(
                capability(
                    ExerciseMetric.HEART_RATE_BPM,
                    live = MeasurementCapability.LiveAvailability.UNAVAILABLE_DEVICE_OFF_BODY,
                ),
            ),
        )
        assertEquals(
            MetricAvailabilityUi.NO_LOCATION,
            WatchUiMapper.availability(
                capability(
                    ExerciseMetric.LOCATION,
                    live = MeasurementCapability.LiveAvailability.NO_GNSS,
                ),
            ),
        )
    }

    @Test
    fun activitySupportKeepsRawFallbackTruthful() {
        val emptyDimensions = HealthServicesExerciseSnapshot(
            HealthServicesExerciseSnapshot.State.READY,
            HealthServicesExerciseSnapshot.Ownership.NONE,
            emptyMap(),
            emptySet(),
            HealthServicesCapabilityDimensions.empty(),
            "",
        )

        assertEquals(
            ActivitySupportState.CHECKING,
            WatchUiMapper.activitySupport(
                ActivityChoice.CYCLING,
                emptyDimensions,
                setOf(ActivityChoice.CYCLING),
            ),
        )
        assertEquals(
            ActivitySupportState.UNSUPPORTED,
            WatchUiMapper.activitySupport(
                ActivityChoice.CYCLING,
                emptyDimensions,
                setOf(ActivityChoice.RUNNING),
            ),
        )
    }

    @Test
    fun recoveredRecordingUsesAuthoritativeTypeAndServiceDurations() {
        val snapshot = RecordingSnapshot(
            RecordingState.RECORDING,
            44,
            ActivityType.ACTIVITY_TYPE_CYCLING,
            RecordingAuthority.CANONICAL,
            1_000L,
            12_000L,
            17_000L,
            5_000L,
            4_000L,
            3,
            null,
            3,
            9_000L,
            8_000L,
        )

        val mapped = WatchUiMapper.recording(snapshot)

        assertEquals(ActivityChoice.CYCLING, mapped.activity)
        assertEquals(RecordingAuthority.CANONICAL, mapped.authority)
        assertEquals(12_000L, mapped.activeDurationMillis)
        assertEquals(17_000L, mapped.elapsedDurationMillis)
        assertEquals(3, mapped.currentLapIndex)
        assertEquals(9_000L, mapped.lastLapElapsedDurationMillis)
        assertEquals(8_000L, mapped.lastLapActiveDurationMillis)
    }

    private fun snapshot(
        vararg capabilities: MeasurementCapability,
    ): HealthServicesExerciseSnapshot =
        HealthServicesExerciseSnapshot(
            HealthServicesExerciseSnapshot.State.ACTIVE,
            HealthServicesExerciseSnapshot.Ownership.OWNED_BY_THIS_APP,
            capabilities.associateBy { it.metric },
            capabilities.mapTo(mutableSetOf()) { it.metric },
            "",
        )

    private fun capability(
        metric: ExerciseMetric,
        support: MeasurementCapability.Support = MeasurementCapability.Support.SUPPORTED,
        permission: MeasurementCapability.PermissionState =
            MeasurementCapability.PermissionState.GRANTED,
        raw: MeasurementCapability.RawHardwareState =
            MeasurementCapability.RawHardwareState.PRESENT,
        live: MeasurementCapability.LiveAvailability =
            MeasurementCapability.LiveAvailability.AVAILABLE,
    ): MeasurementCapability =
        MeasurementCapability(
            metric,
            support,
            permission,
            raw,
            MeasurementCapability.VendorState.UNKNOWN,
            live,
            setOf(MeasurementProvenance.HEALTH_SERVICES),
        )
}
