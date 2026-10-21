package com.long2know.sportlogger.ui

import androidx.annotation.StringRes
import androidx.health.services.client.data.ExerciseType
import com.long2know.sportlogger.R
import com.long2know.sportlogger.acquisition.RouteStatus
import com.long2know.sportlogger.acquisition.healthservices.HealthServicesExerciseSnapshot
import com.long2know.sportlogger.recording.RecordingAuthority
import com.long2know.sportlogger.recording.RecordingState
import com.long2know.utilities.contract.proto.ActivityType

enum class ActivityChoice(
    val canonicalType: ActivityType,
    val healthServicesType: ExerciseType,
    @param:StringRes val labelRes: Int,
) {
    RUNNING(
        ActivityType.ACTIVITY_TYPE_RUNNING,
        ExerciseType.RUNNING,
        R.string.activity_running,
    ),
    WALKING(
        ActivityType.ACTIVITY_TYPE_WALKING,
        ExerciseType.WALKING,
        R.string.activity_walking,
    ),
    CYCLING(
        ActivityType.ACTIVITY_TYPE_CYCLING,
        ExerciseType.BIKING,
        R.string.activity_cycling,
    ),
    HIKING(
        ActivityType.ACTIVITY_TYPE_HIKING,
        ExerciseType.HIKING,
        R.string.activity_hiking,
    ),
    STRENGTH(
        ActivityType.ACTIVITY_TYPE_STRENGTH,
        ExerciseType.STRENGTH_TRAINING,
        R.string.activity_strength,
    ),
    SWIMMING(
        ActivityType.ACTIVITY_TYPE_SWIMMING,
        ExerciseType.SWIMMING_POOL,
        R.string.activity_swimming,
    ),
    OTHER(
        ActivityType.ACTIVITY_TYPE_OTHER,
        ExerciseType.WORKOUT,
        R.string.activity_other,
    );

    companion object {
        fun fromCanonical(type: ActivityType): ActivityChoice =
            entries.firstOrNull { it.canonicalType == type } ?: OTHER
    }
}

enum class ServiceConnectionState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
}

enum class ActivitySupportState {
    CHECKING,
    SUPPORTED,
    LIMITED,
    UNSUPPORTED,
}

enum class MetricId {
    HEART_RATE,
    STEPS,
    DISTANCE,
    LOCATION,
}

enum class MetricAvailabilityUi {
    CHECKING,
    AVAILABLE,
    ACQUIRING,
    PERMISSION_DENIED,
    UNSUPPORTED,
    OFF_BODY,
    NO_LOCATION,
    UNAVAILABLE,
}

sealed interface MetricValueUi {
    data class Decimal(val value: Double) : MetricValueUi

    data class Count(val value: Long) : MetricValueUi

    data class Coordinates(
        val latitude: Double,
        val longitude: Double,
    ) : MetricValueUi
}

data class MetricUi(
    val id: MetricId,
    val value: MetricValueUi? = null,
    val availability: MetricAvailabilityUi = MetricAvailabilityUi.CHECKING,
    val sources: List<String> = emptyList(),
)

data class LiveMetricsUi(
    val heartRate: MetricUi = MetricUi(MetricId.HEART_RATE),
    val steps: MetricUi = MetricUi(MetricId.STEPS),
    val distance: MetricUi = MetricUi(MetricId.DISTANCE),
    val location: MetricUi = MetricUi(MetricId.LOCATION),
)

data class RecordingUi(
    val state: RecordingState = RecordingState.IDLE,
    val activityId: Int = 0,
    val activity: ActivityChoice? = null,
    val authority: RecordingAuthority = RecordingAuthority.LEGACY_COMPATIBILITY,
    val activeDurationMillis: Long = 0L,
    val elapsedDurationMillis: Long = 0L,
    val currentLapIndex: Int = 0,
    val lastLapElapsedDurationMillis: Long? = null,
    val lastLapActiveDurationMillis: Long? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

enum class SyncUiState {
    QUEUED,
    TRANSFERRING,
    SYNCED,
    FAILED,
}

data class WorkoutSummaryUi(
    val transferId: String,
    val sessionId: String,
    val activity: ActivityChoice,
    val startTimeUtcMillis: Long,
    val activeDurationMillis: Long?,
    val elapsedDurationMillis: Long,
    val distanceMeters: Double?,
    val steps: Long?,
    val averageHeartRateBpm: Double?,
    val maximumHeartRateBpm: Double?,
    val lapCount: Int,
    val syncState: SyncUiState,
    val attemptCount: Long,
    val failureCode: String? = null,
)

enum class ConfirmationAction {
    STOP,
    DISCARD,
}

data class PermissionUiState(
    val healthPrerequisiteGranted: Boolean = false,
    val missingRequiredCount: Int = 0,
    val missingOptionalCount: Int = 0,
    val permanentlyDeniedCount: Int = 0,
) {
    val missingCount: Int
        get() = missingRequiredCount + missingOptionalCount

    val hasPermanentDenial: Boolean
        get() = permanentlyDeniedCount > 0
}

enum class WatchDestination {
    SETUP,
    LIVE,
    PAUSED,
    FINISHING,
    SUMMARY,
    CONFIRMATION,
}

data class WatchUiState(
    val serviceConnection: ServiceConnectionState = ServiceConnectionState.CONNECTING,
    val recording: RecordingUi = RecordingUi(),
    val acquisition: HealthServicesExerciseSnapshot =
        HealthServicesExerciseSnapshot.uninitialized(),
    val routeStatus: RouteStatus = RouteStatus.UNKNOWN,
    val metrics: LiveMetricsUi = LiveMetricsUi(),
    val selectedActivity: ActivityChoice = ActivityChoice.RUNNING,
    val activitySupport: ActivitySupportState = ActivitySupportState.CHECKING,
    val supportedActivities: Set<ActivityChoice> = emptySet(),
    val lapAvailable: Boolean = false,
    val lapInFlight: Boolean = false,
    val permissions: PermissionUiState = PermissionUiState(),
    val summary: WorkoutSummaryUi? = null,
    val summaryDismissed: Boolean = false,
    val confirmation: ConfirmationAction? = null,
    val ambient: Boolean = false,
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    val destination: WatchDestination
        get() {
            if (confirmation != null) {
                return WatchDestination.CONFIRMATION
            }
            return when (recording.state) {
                RecordingState.STARTING,
                RecordingState.RECORDING,
                -> WatchDestination.LIVE

                RecordingState.PAUSED,
                RecordingState.INTERRUPTED,
                -> WatchDestination.PAUSED

                RecordingState.STOPPING,
                RecordingState.DISCARDING,
                RecordingState.EXPORT_PENDING,
                -> WatchDestination.FINISHING

                RecordingState.EXPORT_QUEUED ->
                    if (summary == null) {
                        WatchDestination.FINISHING
                    } else if (summaryDismissed) {
                        WatchDestination.SETUP
                    } else {
                        WatchDestination.SUMMARY
                    }

                RecordingState.IDLE,
                RecordingState.ERROR,
                -> if (summary != null && !summaryDismissed) {
                    WatchDestination.SUMMARY
                } else {
                    WatchDestination.SETUP
                }
            }
        }
}

data class WatchLayoutPolicy(
    val horizontalPaddingDp: Int,
    val topPaddingDp: Int,
    val bottomPaddingDp: Int,
) {
    companion object {
        fun forScreen(isRound: Boolean, widthDp: Int): WatchLayoutPolicy {
            val compact = widthDp < 200
            return if (isRound) {
                WatchLayoutPolicy(
                    horizontalPaddingDp = if (compact) 18 else 22,
                    topPaddingDp = if (compact) 26 else 30,
                    bottomPaddingDp = if (compact) 30 else 34,
                )
            } else {
                WatchLayoutPolicy(
                    horizontalPaddingDp = if (compact) 10 else 14,
                    topPaddingDp = 18,
                    bottomPaddingDp = 20,
                )
            }
        }
    }
}
