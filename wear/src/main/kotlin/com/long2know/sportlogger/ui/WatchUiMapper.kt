package com.long2know.sportlogger.ui

import com.long2know.sportlogger.acquisition.ExerciseMetric
import com.long2know.sportlogger.acquisition.MeasurementCapability
import com.long2know.sportlogger.acquisition.MeasurementProvenance
import com.long2know.sportlogger.acquisition.RouteStatus
import com.long2know.sportlogger.acquisition.healthservices.HealthServicesExerciseSnapshot
import com.long2know.sportlogger.recording.RecordingSnapshot
import com.long2know.utilities.contract.proto.ActivityType
import com.long2know.utilities.models.SharedData

object WatchUiMapper {
    fun recording(snapshot: RecordingSnapshot): RecordingUi =
        RecordingUi(
            state = snapshot.state,
            activityId = snapshot.activityId,
            activity = if (snapshot.activityType == ActivityType.ACTIVITY_TYPE_UNSPECIFIED) {
                null
            } else {
                ActivityChoice.fromCanonical(snapshot.activityType)
            },
            authority = snapshot.authority,
            activeDurationMillis = snapshot.durationMillis,
            elapsedDurationMillis = snapshot.elapsedDurationMillis,
            currentLapIndex = snapshot.currentLapIndex,
            lastLapElapsedDurationMillis = snapshot.lastLapElapsedDurationMillis,
            lastLapActiveDurationMillis = snapshot.lastLapActiveDurationMillis,
            errorCode = snapshot.error?.code?.name,
            errorMessage = snapshot.error?.message,
        )

    fun activitySupport(
        choice: ActivityChoice,
        snapshot: HealthServicesExerciseSnapshot,
        supportedActivities: Set<ActivityChoice> = ActivityChoice.entries.toSet(),
    ): ActivitySupportState {
        if (supportedActivities.isEmpty()) {
            return ActivitySupportState.CHECKING
        }
        if (choice !in supportedActivities) {
            return ActivitySupportState.UNSUPPORTED
        }
        val exercises = snapshot.capabilityDimensions.exerciseMetrics
        if (exercises.isEmpty()) {
            return ActivitySupportState.CHECKING
        }
        return if (exercises.containsKey(choice.healthServicesType)) {
            ActivitySupportState.SUPPORTED
        } else {
            ActivitySupportState.LIMITED
        }
    }

    fun liveMetrics(
        sharedData: SharedData,
        snapshot: HealthServicesExerciseSnapshot,
        routeStatus: RouteStatus,
    ): LiveMetricsUi {
        val data = sharedData.data
        val capabilities = snapshot.capabilities
        val heartRate = capabilities[ExerciseMetric.HEART_RATE_BPM]
        val steps = preferredCapability(
            capabilities,
            ExerciseMetric.STEPS_TOTAL,
            ExerciseMetric.STEPS,
        )
        val distance = preferredCapability(
            capabilities,
            ExerciseMetric.DISTANCE_TOTAL,
            ExerciseMetric.DISTANCE,
        )
        val location = capabilities[ExerciseMetric.LOCATION]

        return LiveMetricsUi(
            heartRate = metric(
                MetricId.HEART_RATE,
                heartRate,
                MetricValueUi.Decimal(data.HeartRate.toDouble()),
            ),
            steps = metric(
                MetricId.STEPS,
                steps,
                MetricValueUi.Count(data.Steps.toLong()),
            ),
            distance = metric(
                MetricId.DISTANCE,
                distance,
                MetricValueUi.Decimal(data.TotalDistance),
            ),
            location = locationMetric(
                location,
                routeStatus,
                data.Latitude,
                data.Longitude,
            ),
        )
    }

    private fun preferredCapability(
        capabilities: Map<ExerciseMetric, MeasurementCapability>,
        first: ExerciseMetric,
        second: ExerciseMetric,
    ): MeasurementCapability? {
        val firstCapability = capabilities[first]
        if (firstCapability?.liveAvailability == MeasurementCapability.LiveAvailability.AVAILABLE) {
            return firstCapability
        }
        return capabilities[second] ?: firstCapability
    }

    private fun metric(
        id: MetricId,
        capability: MeasurementCapability?,
        availableValue: MetricValueUi,
    ): MetricUi {
        val availability = availability(capability)
        return MetricUi(
            id = id,
            value = if (availability == MetricAvailabilityUi.AVAILABLE) {
                availableValue
            } else {
                null
            },
            availability = availability,
            sources = sources(capability),
        )
    }

    private fun locationMetric(
        capability: MeasurementCapability?,
        routeStatus: RouteStatus,
        latitude: Double,
        longitude: Double,
    ): MetricUi {
        val availability = when (routeStatus) {
            RouteStatus.AVAILABLE -> MetricAvailabilityUi.AVAILABLE
            RouteStatus.ACQUIRING -> MetricAvailabilityUi.ACQUIRING
            RouteStatus.PERMISSION_DENIED,
            RouteStatus.APPROXIMATE_ONLY,
            -> MetricAvailabilityUi.PERMISSION_DENIED

            RouteStatus.LOCATION_DISABLED,
            RouteStatus.NO_GNSS,
            RouteStatus.NO_COMPATIBLE_PROVIDER,
            -> MetricAvailabilityUi.NO_LOCATION

            RouteStatus.UNAVAILABLE -> MetricAvailabilityUi.UNAVAILABLE
            RouteStatus.UNKNOWN -> availability(capability)
        }
        return MetricUi(
            id = MetricId.LOCATION,
            value = if (availability == MetricAvailabilityUi.AVAILABLE) {
                MetricValueUi.Coordinates(latitude, longitude)
            } else {
                null
            },
            availability = availability,
            sources = sources(capability),
        )
    }

    fun availability(capability: MeasurementCapability?): MetricAvailabilityUi {
        if (capability == null) {
            return MetricAvailabilityUi.CHECKING
        }
        if (capability.permissionState == MeasurementCapability.PermissionState.DENIED ||
            capability.permissionState == MeasurementCapability.PermissionState.DENIED_PERMANENTLY
        ) {
            return MetricAvailabilityUi.PERMISSION_DENIED
        }
        if (capability.exerciseSupport == MeasurementCapability.Support.UNSUPPORTED &&
            capability.rawHardwareState == MeasurementCapability.RawHardwareState.ABSENT &&
            capability.vendorState != MeasurementCapability.VendorState.AVAILABLE_ENTITLED
        ) {
            return MetricAvailabilityUi.UNSUPPORTED
        }
        return when (capability.liveAvailability) {
            MeasurementCapability.LiveAvailability.AVAILABLE ->
                MetricAvailabilityUi.AVAILABLE

            MeasurementCapability.LiveAvailability.ACQUIRING ->
                MetricAvailabilityUi.ACQUIRING

            MeasurementCapability.LiveAvailability.UNAVAILABLE_DEVICE_OFF_BODY ->
                MetricAvailabilityUi.OFF_BODY

            MeasurementCapability.LiveAvailability.NO_GNSS ->
                MetricAvailabilityUi.NO_LOCATION

            MeasurementCapability.LiveAvailability.UNAVAILABLE ->
                MetricAvailabilityUi.UNAVAILABLE

            MeasurementCapability.LiveAvailability.UNKNOWN ->
                if (capability.exerciseSupport == MeasurementCapability.Support.UNSUPPORTED) {
                    MetricAvailabilityUi.UNSUPPORTED
                } else {
                    MetricAvailabilityUi.CHECKING
                }
        }
    }

    private fun sources(capability: MeasurementCapability?): List<String> =
        capability
            ?.activeProvenance
            ?.sortedBy { it.ordinal }
            ?.map(::sourceName)
            .orEmpty()

    private fun sourceName(source: MeasurementProvenance): String =
        when (source) {
            MeasurementProvenance.HEALTH_SERVICES -> "Health Services"
            MeasurementProvenance.RAW_PLATFORM -> "Watch sensor"
            MeasurementProvenance.VENDOR -> "Vendor sensor"
            MeasurementProvenance.DERIVED -> "Derived"
        }
}
