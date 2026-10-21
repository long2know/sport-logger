package com.long2know.sportlogger.healthconnect

import com.long2know.utilities.contract.CanonicalMeasurements
import com.long2know.utilities.contract.proto.ActivityRecord
import com.long2know.utilities.contract.proto.ActivitySummary
import com.long2know.utilities.contract.proto.ActivityType
import com.long2know.utilities.contract.proto.CapabilityState
import com.long2know.utilities.contract.proto.DoubleMeasurement
import com.long2know.utilities.contract.proto.LiveAvailability
import com.long2know.utilities.contract.proto.LongMeasurement
import com.long2know.utilities.contract.proto.MeasurementSample
import com.long2know.utilities.contract.proto.MeasurementStatus
import com.long2know.utilities.contract.proto.PermissionState
import com.long2know.utilities.contract.proto.Position
import com.long2know.utilities.contract.proto.PositionMeasurement
import com.long2know.utilities.contract.proto.Quality
import com.long2know.utilities.contract.proto.SourceKind
import com.long2know.utilities.contract.proto.SourceMetadata

internal object HealthConnectTestData {
    const val SESSION_ID = "d59d512b-5e88-58f7-976f-99c6d771c527"
    const val START_MS = 1_700_000_000_000L
    const val END_MS = START_MS + 3_600_000L

    val source: SourceMetadata =
        SourceMetadata.newBuilder()
            .setKind(SourceKind.SOURCE_KIND_HEALTH_SERVICES)
            .setProvider("Health Services")
            .setManufacturer("Example")
            .setModel("Watch")
            .setAppContractVersion(1)
            .build()

    fun status(metric: CanonicalMeasurements.Metric): MeasurementStatus =
        CanonicalMeasurements.status(
            metric,
            CanonicalMeasurements.Dimensions.builder(source)
                .exerciseCapability(CapabilityState.CAPABILITY_STATE_SUPPORTED)
                .runtimePermission(PermissionState.PERMISSION_STATE_GRANTED)
                .rawHardware(CapabilityState.CAPABILITY_STATE_SUPPORTED)
                .vendorCapability(CapabilityState.CAPABILITY_STATE_UNKNOWN)
                .liveAvailability(LiveAvailability.LIVE_AVAILABILITY_AVAILABLE)
                .quality(Quality.QUALITY_VALID)
                .build(),
        )

    fun doubleMeasurement(
        metric: CanonicalMeasurements.Metric,
        value: Double? = null,
    ): DoubleMeasurement =
        DoubleMeasurement.newBuilder()
            .setStatus(status(metric))
            .apply { value?.let(::setValue) }
            .build()

    fun longMeasurement(
        metric: CanonicalMeasurements.Metric,
        value: Long? = null,
    ): LongMeasurement =
        LongMeasurement.newBuilder()
            .setStatus(status(metric))
            .apply { value?.let(::setValue) }
            .build()

    fun location(
        latitude: Double,
        longitude: Double,
        altitude: Double? = null,
        accuracy: Double? = null,
    ): PositionMeasurement {
        val position =
            Position.newBuilder()
                .setLatitudeDegrees(latitude)
                .setLongitudeDegrees(longitude)
                .apply {
                    altitude?.let(::setAltitudeM)
                    accuracy?.let(::setAccuracyM)
                }
                .build()
        return PositionMeasurement.newBuilder()
            .setStatus(status(CanonicalMeasurements.Metric.LOCATION))
            .setValue(position)
            .build()
    }
    fun sample(
        sequence: Long,
        timestampMs: Long,
        location: PositionMeasurement? = null,
        heartRate: DoubleMeasurement? = null,
        speed: DoubleMeasurement? = null,
    ): MeasurementSample =
        MeasurementSample.newBuilder()
            .setSequence(sequence)
            .setTimestampUtcMs(timestampMs)
            .apply {
                location?.let(::setLocation)
                heartRate?.let(::setHeartRateBpm)
                speed?.let(::setSpeedMps)
            }
            .build()

    fun summary(
        distanceM: DoubleMeasurement? = null,
        caloriesKcal: DoubleMeasurement? = null,
        steps: LongMeasurement? = null,
        elevationGainM: DoubleMeasurement? = null,
    ): ActivitySummary =
        ActivitySummary.newBuilder()
            .apply {
                distanceM?.let(::setDistanceM)
                caloriesKcal?.let(::setCaloriesKcal)
                steps?.let(::setSteps)
                elevationGainM?.let(::setElevationGainM)
            }
            .build()

    fun activity(
        revision: Long,
        samples: List<MeasurementSample> = emptyList(),
        summary: ActivitySummary? = null,
        startMs: Long = START_MS,
        endMs: Long = END_MS,
        type: ActivityType = ActivityType.ACTIVITY_TYPE_RUNNING,
    ): ActivityRecord =
        ActivityRecord.newBuilder()
            .setSessionId(SESSION_ID)
            .setWatchRevision(revision)
            .setActivityType(type)
            .setStartTimeUtcMs(startMs)
            .setEndTimeUtcMs(endMs)
            .setElapsedDurationMs(endMs - startMs)
            .setTimeZoneId("America/Los_Angeles")
            .setSource(source)
            .addAllSamples(samples)
            .apply { summary?.let(::setSummary) }
            .build()
}
