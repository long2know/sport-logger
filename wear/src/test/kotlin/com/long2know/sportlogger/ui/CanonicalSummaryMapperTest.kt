package com.long2know.sportlogger.ui

import com.long2know.sportlogger.persistence.watch.WatchActivityRepository
import com.long2know.sportlogger.recording.CompletedRecordingSnapshot
import com.long2know.utilities.contract.CanonicalPayload
import com.long2know.utilities.contract.CanonicalTransfers
import com.long2know.utilities.contract.proto.ActivityRecord
import com.long2know.utilities.contract.proto.ActivitySummary
import com.long2know.utilities.contract.proto.ActivityType
import com.long2know.utilities.contract.proto.AggregationSemantics
import com.long2know.utilities.contract.proto.CapabilityState
import com.long2know.utilities.contract.proto.DoubleMeasurement
import com.long2know.utilities.contract.proto.LiveAvailability
import com.long2know.utilities.contract.proto.Lap
import com.long2know.utilities.contract.proto.LapTrigger
import com.long2know.utilities.contract.proto.LongMeasurement
import com.long2know.utilities.contract.proto.MeasurementStatus
import com.long2know.utilities.contract.proto.PermissionState
import com.long2know.utilities.contract.proto.Quality
import com.long2know.utilities.contract.proto.SourceKind
import com.long2know.utilities.contract.proto.SourceMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class CanonicalSummaryMapperTest {
    @Test
    fun canonicalZeroSummaryValuesRemainPresent() {
        val summary = ActivitySummary.newBuilder()
            .setDistanceM(
                DoubleMeasurement.newBuilder()
                    .setValue(0.0)
                    .setStatus(status("DISTANCE_TOTAL", "meters")),
            )
            .setSteps(
                LongMeasurement.newBuilder()
                    .setValue(0L)
                    .setStatus(status("STEPS_TOTAL", "count")),
            )
            .setAverageHeartRateBpm(
                DoubleMeasurement.newBuilder()
                    .setValue(0.0)
                    .setStatus(status("HEART_RATE_BPM", "beats/minute")),
            )
            .build()

        val mapped = CanonicalSummaryMapper.map(
            presentation(
                summary,
                WatchActivityRepository.OUTBOX_READY,
            ),
        )

        assertEquals(0.0, mapped.distanceMeters!!, 0.0)
        assertEquals(0L, mapped.steps)
        assertEquals(0.0, mapped.averageHeartRateBpm!!, 0.0)
        assertNull(mapped.maximumHeartRateBpm)
        assertEquals(1, mapped.lapCount)
        assertEquals(SyncUiState.QUEUED, mapped.syncState)
    }

    @Test
    fun canonicalStatesMapWithoutTreatingQueueAsSync() {
        val empty = ActivitySummary.getDefaultInstance()

        assertEquals(
            SyncUiState.QUEUED,
            CanonicalSummaryMapper.map(
                presentation(empty, WatchActivityRepository.OUTBOX_READY),
            ).syncState,
        )
        assertEquals(
            SyncUiState.TRANSFERRING,
            CanonicalSummaryMapper.map(
                presentation(
                    empty,
                    WatchActivityRepository.OUTBOX_AWAITING_COMMIT_ACK,
                ),
            ).syncState,
        )
        assertEquals(
            SyncUiState.SYNCED,
            CanonicalSummaryMapper.map(
                presentation(empty, WatchActivityRepository.OUTBOX_COMMITTED),
            ).syncState,
        )
        assertEquals(
            SyncUiState.FAILED,
            CanonicalSummaryMapper.map(
                presentation(empty, WatchActivityRepository.OUTBOX_RETRYABLE),
            ).syncState,
        )
    }

    @Test
    fun serviceCompletionSnapshotRestoresCommittedSummary() {
        val transferId = UUID.randomUUID().toString()
        val mapped = CanonicalSummaryMapper.fromCompleted(
            CompletedRecordingSnapshot(
                activity(ActivitySummary.getDefaultInstance()),
                transferId,
                WatchActivityRepository.OUTBOX_COMMITTED,
                3L,
                90_000L,
            ),
        )

        assertEquals(transferId, mapped.transferId)
        assertEquals(ActivityChoice.WALKING, mapped.activity)
        assertEquals(1, mapped.lapCount)
        assertEquals(SyncUiState.SYNCED, mapped.syncState)
        assertEquals(3L, mapped.attemptCount)
    }

    private fun presentation(
        summary: ActivitySummary,
        state: String,
    ): CanonicalOutboxPresentation {
        val transferId = UUID.randomUUID().toString()
        val activity = activity(summary)
        val transfer = CanonicalTransfers.activityTransfer(
            transferId,
            CanonicalPayload.create(activity),
        )
        return CanonicalOutboxPresentation(
            transferId = transferId,
            payload = transfer.toByteArray(),
            canonicalState = state,
            attemptCount = 2L,
        )
    }

    private fun activity(summary: ActivitySummary): ActivityRecord {
        val sessionId = UUID.randomUUID().toString()
        val lapId = UUID.randomUUID().toString()
        val activity = ActivityRecord.newBuilder()
            .setSessionId(sessionId)
            .setWatchRevision(1L)
            .setActivityType(ActivityType.ACTIVITY_TYPE_WALKING)
            .setStartTimeUtcMs(1_000L)
            .setEndTimeUtcMs(61_000L)
            .setElapsedDurationMs(60_000L)
            .setActiveDurationMs(58_000L)
            .setSummary(summary)
            .addLaps(
                Lap.newBuilder()
                    .setLapId(lapId)
                    .setIndex(1)
                    .setStartTimeUtcMs(1_000L)
                    .setEndTimeUtcMs(61_000L)
                    .setElapsedDurationMs(60_000L)
                    .setActiveDurationMs(58_000L)
                    .setTrigger(LapTrigger.LAP_TRIGGER_MANUAL),
            )
            .setSource(
                SourceMetadata.newBuilder()
                    .setKind(SourceKind.SOURCE_KIND_HEALTH_SERVICES)
                    .setProvider("sportlogger")
                    .setAppContractVersion(1),
            )
            .build()
        return activity
    }

    private fun status(
        identifier: String,
        unit: String,
    ): MeasurementStatus =
        MeasurementStatus.newBuilder()
            .setMetricIdentifier(identifier)
            .setPublicUnit(unit)
            .setAggregation(AggregationSemantics.AGGREGATION_SEMANTICS_CUMULATIVE)
            .setExerciseCapability(CapabilityState.CAPABILITY_STATE_SUPPORTED)
            .setRuntimePermission(PermissionState.PERMISSION_STATE_GRANTED)
            .setRawHardware(CapabilityState.CAPABILITY_STATE_SUPPORTED)
            .setVendorCapability(CapabilityState.CAPABILITY_STATE_UNKNOWN)
            .setLiveAvailability(LiveAvailability.LIVE_AVAILABILITY_AVAILABLE)
            .setSource(
                SourceMetadata.newBuilder()
                    .setKind(SourceKind.SOURCE_KIND_HEALTH_SERVICES)
                    .setProvider("sportlogger")
                    .setAppContractVersion(1),
            )
            .setQuality(Quality.QUALITY_VALID)
            .build()
}
