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
import com.long2know.utilities.contract.proto.PermissionState
import com.long2know.utilities.contract.proto.Position
import com.long2know.utilities.contract.proto.PositionMeasurement
import com.long2know.utilities.contract.proto.Quality
import com.long2know.utilities.contract.proto.SourceKind
import com.long2know.utilities.contract.proto.SourceMetadata

internal object AuthorizedHealthConnectTestData {
    private val source =
        SourceMetadata.newBuilder()
            .setKind(SourceKind.SOURCE_KIND_HEALTH_SERVICES)
            .setProvider("SportLogger synthetic acceptance")
            .setManufacturer("Android")
            .setModel("Synthetic AVD")
            .setAppContractVersion(1)
            .build()

    fun activity(
        sessionId: String,
        revision: Long,
        startTimeUtcMs: Long,
        endTimeUtcMs: Long,
        sampleCount: Int,
        routePointCount: Int,
    ): ActivityRecord {
        require(sampleCount >= routePointCount && routePointCount > 0)
        val samples =
            (0 until sampleCount).map { index ->
                val timestamp =
                    startTimeUtcMs +
                        ((endTimeUtcMs - startTimeUtcMs - 1L) * index /
                            sampleCount.coerceAtLeast(1))
                MeasurementSample.newBuilder()
                    .setSequence(index.toLong())
                    .setTimestampUtcMs(timestamp)
                    .setHeartRateBpm(
                        doubleMeasurement(
                            CanonicalMeasurements.Metric.HEART_RATE_BPM,
                            110.0 + index % 20,
                        )
                    )
                    .setSpeedMps(
                        doubleMeasurement(
                            CanonicalMeasurements.Metric.SPEED,
                            3.0 + index % 4,
                        )
                    )
                    .apply {
                        if (index < routePointCount) {
                            setLocation(
                                location(
                                    latitude = 47.6062 + index * 0.0001,
                                    longitude = -122.3321 + index * 0.0001,
                                    altitude = 20.0 + index,
                                )
                            )
                        }
                    }
                    .build()
            }
        return ActivityRecord.newBuilder()
            .setSessionId(sessionId)
            .setWatchRevision(revision)
            .setActivityType(ActivityType.ACTIVITY_TYPE_RUNNING)
            .setStartTimeUtcMs(startTimeUtcMs)
            .setEndTimeUtcMs(endTimeUtcMs)
            .setElapsedDurationMs(endTimeUtcMs - startTimeUtcMs)
            .setTimeZoneId("UTC")
            .setSource(source)
            .setSummary(
                ActivitySummary.newBuilder()
                    .setDistanceM(
                        doubleMeasurement(
                            CanonicalMeasurements.Metric.DISTANCE,
                            5_000.0 + revision,
                        )
                    )
                    .setCaloriesKcal(
                        doubleMeasurement(
                            CanonicalMeasurements.Metric.CALORIES,
                            450.0 + revision,
                        )
                    )
                    .setSteps(
                        longMeasurement(
                            CanonicalMeasurements.Metric.STEPS,
                            6_000 + revision,
                        )
                    )
                    .setElevationGainM(
                        doubleMeasurement(
                            CanonicalMeasurements.Metric.ELEVATION_GAIN,
                            80.0 + revision,
                        )
                    )
            )
            .addAllSamples(samples)
            .build()
    }

    private fun doubleMeasurement(
        metric: CanonicalMeasurements.Metric,
        value: Double,
    ): DoubleMeasurement =
        DoubleMeasurement.newBuilder()
            .setStatus(status(metric))
            .setValue(value)
            .build()

    private fun longMeasurement(
        metric: CanonicalMeasurements.Metric,
        value: Long,
    ): LongMeasurement =
        LongMeasurement.newBuilder()
            .setStatus(status(metric))
            .setValue(value)
            .build()

    private fun location(
        latitude: Double,
        longitude: Double,
        altitude: Double,
    ): PositionMeasurement =
        PositionMeasurement.newBuilder()
            .setStatus(status(CanonicalMeasurements.Metric.LOCATION))
            .setValue(
                Position.newBuilder()
                    .setLatitudeDegrees(latitude)
                    .setLongitudeDegrees(longitude)
                    .setAltitudeM(altitude)
                    .setAccuracyM(2.0)
            )
            .build()

    private fun status(
        metric: CanonicalMeasurements.Metric,
    ) =
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
}
