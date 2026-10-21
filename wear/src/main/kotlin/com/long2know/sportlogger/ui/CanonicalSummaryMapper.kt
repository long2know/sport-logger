package com.long2know.sportlogger.ui

import com.long2know.sportlogger.persistence.watch.WatchActivityRepository
import com.long2know.utilities.contract.CanonicalTransfers
import com.long2know.utilities.contract.proto.ActivityRecord
import com.long2know.utilities.contract.proto.ActivitySummary

data class CanonicalOutboxPresentation(
    val transferId: String,
    val payload: ByteArray,
    val canonicalState: String,
    val attemptCount: Long,
    val failureCode: String? = null,
)

object CanonicalSummaryMapper {
    fun map(source: CanonicalOutboxPresentation): WorkoutSummaryUi {
        val verified = CanonicalTransfers.parseActivityTransfer(source.payload)
        return mapActivity(
            activity = verified.payload.activity,
            transferId = source.transferId,
            canonicalState = source.canonicalState,
            attemptCount = source.attemptCount,
            failureCode = source.failureCode,
        )
    }

    fun fromCompleted(
        completed: WatchActivityRepository.CompletedActivity,
        failureCode: String? = null,
    ): WorkoutSummaryUi =
        mapActivity(
            activity = completed.activity,
            transferId = completed.outbox.transferId,
            canonicalState = completed.outbox.state,
            attemptCount = completed.outbox.attemptCount,
            failureCode = failureCode,
        )

    private fun mapActivity(
        activity: ActivityRecord,
        transferId: String,
        canonicalState: String,
        attemptCount: Long,
        failureCode: String?,
    ): WorkoutSummaryUi {
        val summary = if (activity.hasSummary()) activity.summary else null
        return WorkoutSummaryUi(
            transferId = transferId,
            sessionId = activity.sessionId,
            activity = ActivityChoice.fromCanonical(activity.activityType),
            startTimeUtcMillis = activity.startTimeUtcMs,
            activeDurationMillis =
                if (activity.hasActiveDurationMs()) activity.activeDurationMs else null,
            elapsedDurationMillis = activity.elapsedDurationMs,
            distanceMeters = summary?.distanceMeters(),
            steps = summary?.stepCount(),
            averageHeartRateBpm = summary?.averageHeartRate(),
            maximumHeartRateBpm = summary?.maximumHeartRate(),
            lapCount = activity.lapsCount,
            syncState = syncState(canonicalState),
            attemptCount = attemptCount,
            failureCode = failureCode,
        )
    }

    fun fromOutbox(
        item: WatchActivityRepository.OutboxItem,
        failureCode: String? = null,
    ): WorkoutSummaryUi =
        map(
            CanonicalOutboxPresentation(
                transferId = item.transferId,
                payload = item.payload,
                canonicalState = item.state,
                attemptCount = item.attemptCount,
                failureCode = failureCode,
            ),
        )

    private fun syncState(canonicalState: String): SyncUiState =
        when (canonicalState) {
            WatchActivityRepository.OUTBOX_READY -> SyncUiState.QUEUED
            WatchActivityRepository.OUTBOX_SENDING,
            WatchActivityRepository.OUTBOX_AWAITING_COMMIT_ACK,
            -> SyncUiState.TRANSFERRING

            WatchActivityRepository.OUTBOX_COMMITTED -> SyncUiState.SYNCED
            WatchActivityRepository.OUTBOX_RETRYABLE -> SyncUiState.FAILED
            else -> throw IllegalArgumentException("Unknown canonical outbox state $canonicalState")
        }

    private fun ActivitySummary.distanceMeters(): Double? =
        if (hasDistanceM() && distanceM.hasValue()) distanceM.value else null

    private fun ActivitySummary.stepCount(): Long? =
        if (hasSteps() && steps.hasValue()) steps.value else null

    private fun ActivitySummary.averageHeartRate(): Double? =
        if (hasAverageHeartRateBpm() && averageHeartRateBpm.hasValue()) {
            averageHeartRateBpm.value
        } else {
            null
        }

    private fun ActivitySummary.maximumHeartRate(): Double? =
        if (hasMaximumHeartRateBpm() && maximumHeartRateBpm.hasValue()) {
            maximumHeartRateBpm.value
        } else {
            null
        }
}
