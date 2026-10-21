package com.long2know.sportlogger.ui

import com.long2know.sportlogger.acquisition.ExerciseMetric
import com.long2know.sportlogger.acquisition.LiveMeasurementSnapshot
import com.long2know.sportlogger.acquisition.LocationSample
import com.long2know.sportlogger.acquisition.MeasurementCapability
import com.long2know.sportlogger.acquisition.MeasurementProvenance
import com.long2know.sportlogger.acquisition.RecordingPermissionPolicy
import com.long2know.sportlogger.acquisition.RouteStatus
import com.long2know.sportlogger.acquisition.healthservices.HealthServicesExerciseSnapshot
import com.long2know.sportlogger.recording.RecordingSnapshot
import com.long2know.utilities.contract.proto.ActivityType

object WatchUiMapper {
    fun permissions(state: RecordingPermissionPolicy.RuntimeState): PermissionUiState =
        PermissionUiState(
            healthPrerequisiteGranted = state.hasHealthForegroundPrerequisite(),
            missingRequiredCount = state.missingRequiredPermissions.size,
            missingOptionalCount = state.missingOptionalPermissions.size,
            permanentlyDeniedCount = state.permanentlyDeniedPermissions.size,
        )

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
        liveSnapshot: LiveMeasurementSnapshot,
        snapshot: HealthServicesExerciseSnapshot,
        routeStatus: RouteStatus,
    ): LiveMetricsUi {
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
                liveSnapshot.heartRateBpm?.let {
                    MetricValueUi.Decimal(it.value)
                },
                liveSnapshot.heartRateBpm?.provenance,
            ),
            steps = metric(
                MetricId.STEPS,
                steps,
                liveSnapshot.steps?.let {
                    MetricValueUi.Count(it.value)
                },
                liveSnapshot.steps?.provenance,
            ),
            distance = metric(
                MetricId.DISTANCE,
                distance,
                liveSnapshot.distanceMeters?.let {
                    MetricValueUi.Decimal(it.value)
                },
                liveSnapshot.distanceMeters?.provenance,
            ),
            location = locationMetric(
                location,
                routeStatus,
                liveSnapshot.location,
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
        liveValue: MetricValueUi?,
        provenance: MeasurementProvenance?,
    ): MetricUi {
        val capabilityAvailability = availability(capability)
        val availability = withoutSample(capabilityAvailability, liveValue != null)
        return MetricUi(
            id = id,
            value = if (capabilityAvailability == MetricAvailabilityUi.AVAILABLE) {
                liveValue
            } else {
                null
            },
            availability = availability,
            sources = if (capabilityAvailability == MetricAvailabilityUi.AVAILABLE &&
                liveValue != null &&
                provenance != null
            ) {
                listOf(sourceName(provenance))
            } else {
                emptyList()
            },
        )
    }

    private fun locationMetric(
        capability: MeasurementCapability?,
        routeStatus: RouteStatus,
        liveValue: LiveMeasurementSnapshot.Value<LocationSample>?,
    ): MetricUi {
        val routeAvailability = when (routeStatus) {
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
        val availability = withoutSample(routeAvailability, liveValue != null)
        return MetricUi(
            id = MetricId.LOCATION,
            value = if (routeAvailability == MetricAvailabilityUi.AVAILABLE &&
                liveValue != null
            ) {
                MetricValueUi.Coordinates(
                    liveValue.value.latitude,
                    liveValue.value.longitude,
                )
            } else {
                null
            },
            availability = availability,
            sources = if (routeAvailability == MetricAvailabilityUi.AVAILABLE &&
                liveValue != null
            ) {
                listOf(sourceName(liveValue.provenance))
            } else {
                emptyList()
            },
        )
    }

    private fun withoutSample(
        availability: MetricAvailabilityUi,
        hasValue: Boolean,
    ): MetricAvailabilityUi =
        if (!hasValue && availability == MetricAvailabilityUi.AVAILABLE) {
            MetricAvailabilityUi.ACQUIRING
        } else {
            availability
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

    private fun sourceName(source: MeasurementProvenance): String =
        when (source) {
            MeasurementProvenance.HEALTH_SERVICES -> "Health Services"
            MeasurementProvenance.RAW_PLATFORM -> "Watch sensor"
            MeasurementProvenance.VENDOR -> "Vendor sensor"
            MeasurementProvenance.DERIVED -> "Derived"
        }
}
