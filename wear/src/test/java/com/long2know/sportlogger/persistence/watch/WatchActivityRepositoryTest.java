package com.long2know.sportlogger.persistence.watch;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.google.protobuf.ByteString;
import com.long2know.utilities.contract.CanonicalPayload;
import com.long2know.utilities.contract.CanonicalTransfers;
import com.long2know.utilities.contract.proto.AckResult;
import com.long2know.utilities.contract.proto.ActivityRecord;
import com.long2know.utilities.contract.proto.ActivityTransferFile;
import com.long2know.utilities.contract.proto.ActivityType;
import com.long2know.utilities.contract.proto.AggregationSemantics;
import com.long2know.utilities.contract.proto.CapabilityState;
import com.long2know.utilities.contract.proto.CommitAck;
import com.long2know.utilities.contract.proto.DoubleMeasurement;
import com.long2know.utilities.contract.proto.LiveAvailability;
import com.long2know.utilities.contract.proto.MeasurementSample;
import com.long2know.utilities.contract.proto.MeasurementStatus;
import com.long2know.utilities.contract.proto.PermissionState;
import com.long2know.utilities.contract.proto.Position;
import com.long2know.utilities.contract.proto.PositionMeasurement;
import com.long2know.utilities.contract.proto.Quality;
import com.long2know.utilities.contract.proto.SourceKind;
import com.long2know.utilities.contract.proto.SourceMetadata;
import com.long2know.utilities.contract.proto.TombstoneOrigin;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.CompletionException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 26)
public class WatchActivityRepositoryTest {
    private static final String SESSION_ID = "9923d605-7902-5f86-ab5a-7924a4104ae5";
    private static final String TRANSFER_ID = "d3c6f7da-c7d3-4e50-b3dc-18ddcc2a4322";
    private static final long LEASE = 41L;

    private WatchDatabase database;
    private WatchActivityRepository repository;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        database = Room.inMemoryDatabaseBuilder(context, WatchDatabase.class)
                .allowMainThreadQueries()
                .build();
        repository = new WatchActivityRepository(database, Runnable::run);
    }

    @After
    public void tearDown() {
        database.close();
    }

    @Test
    public void startWriteFinishPersistsOneAtomicCompletedRevisionAndOutbox() {
        MeasurementSample sample = sample(1L, 1_500L, 0D, 0D, 145D);
        repository.startSession(
                        WatchActivityRepository.StartRequest
                                .builder(SESSION_ID, LEASE, 1_000L)
                                .activityType(ActivityType.ACTIVITY_TYPE_RUNNING)
                                .timeZone("America/Los_Angeles", -420)
                                .monotonicAnchor("boot-a", 50_000L)
                                .build())
                .join();
        assertTrue(repository.writeSample(SESSION_ID, LEASE, sample).join());

        WatchActivityRepository.CompletionResult result = repository.finishSession(
                        SESSION_ID,
                        LEASE,
                        activity(1L, sample),
                        TRANSFER_ID,
                        2_100L)
                .join();

        assertTrue(result.isCreated());
        assertEquals(
                WatchActivityRepository.LifecycleState.COMPLETED,
                result.getSession().getState());
        assertEquals(0L, result.getSession().getLeaseToken());
        assertEquals(1L, result.getSession().getSampleCount());
        assertEquals(1L, result.getSession().getRoutePointCount());
        assertEquals(1L, result.getSession().getHeartRateSampleCount());
        assertEquals(WatchActivityRepository.OUTBOX_READY, result.getOutbox().getState());
        assertEquals(1, repository.readyOutbox(10).join().size());
        assertArrayEquals(
                result.getOutbox().getPayload(),
                repository.readyOutbox(10).join().get(0).getPayload());
        assertEquals(
                SESSION_ID,
                CanonicalTransfers.parseActivityTransfer(
                                result.getOutbox().getPayload())
                        .getPayload()
                        .getActivity()
                        .getSessionId());
    }

    @Test
    public void staleLeaseCannotAppendOrTransition() {
        repository.startSession(
                        WatchActivityRepository.StartRequest
                                .builder(SESSION_ID, LEASE, 1_000L)
                                .build())
                .join();

        assertFutureCode(
                "SESSION_FENCE_REJECTED",
                () -> repository.writeSample(
                                SESSION_ID,
                                LEASE + 1L,
                                sample(1L, 1_500L, 47D, -122D, 140D))
                        .join());
        assertFutureCode(
                "SESSION_FENCE_REJECTED",
                () -> repository.transition(
                                SESSION_ID,
                                LEASE + 1L,
                                WatchActivityRepository.LifecycleState.PAUSED,
                                "boot-a",
                                null,
                                100L,
                                null,
                                null,
                                1_600L)
                        .join());
        assertEquals(
                WatchActivityRepository.LifecycleState.RECORDING,
                repository.session(SESSION_ID).join().getState());
    }

    @Test
    public void identicalStartReplayReturnsExistingSessionAndConflictIsRejected() {
        WatchActivityRepository.StartRequest request =
                WatchActivityRepository.StartRequest
                        .builder(SESSION_ID, LEASE, 1_000L)
                        .activityType(ActivityType.ACTIVITY_TYPE_RUNNING)
                        .timeZone("America/Los_Angeles", -420)
                        .monotonicAnchor("boot-a", 50_000L)
                        .build();

        WatchActivityRepository.SessionSnapshot first =
                repository.startSession(request).join();
        WatchActivityRepository.SessionSnapshot replay =
                repository.startSession(request).join();

        assertEquals(first.getSessionId(), replay.getSessionId());
        assertEquals(first.getLeaseToken(), replay.getLeaseToken());
        assertFutureCode(
                "START_REPLAY_CONFLICT",
                () -> repository.startSession(
                                WatchActivityRepository.StartRequest
                                        .builder(SESSION_ID, LEASE, 1_000L)
                                        .activityType(ActivityType.ACTIVITY_TYPE_CYCLING)
                                        .timeZone("America/Los_Angeles", -420)
                                        .monotonicAnchor("boot-a", 50_000L)
                                        .build())
                        .join());
    }

    @Test
    public void conflictingFinalSampleRollsBackCompletionAndOutbox() {
        repository.startSession(
                        WatchActivityRepository.StartRequest
                                .builder(SESSION_ID, LEASE, 1_000L)
                                .build())
                .join();
        repository.writeSample(
                        SESSION_ID,
                        LEASE,
                        sample(1L, 1_500L, 47D, -122D, 140D))
                .join();

        assertFutureCode(
                "SAMPLE_SEQUENCE_CONFLICT",
                () -> repository.finishSession(
                                SESSION_ID,
                                LEASE,
                                activity(
                                        1L,
                                        sample(1L, 1_500L, 48D, -123D, 141D)),
                                TRANSFER_ID,
                                2_100L)
                        .join());

        WatchActivityRepository.SessionSnapshot state =
                repository.session(SESSION_ID).join();
        assertEquals(WatchActivityRepository.LifecycleState.RECORDING, state.getState());
        assertEquals(LEASE, state.getLeaseToken());
        assertTrue(repository.readyOutbox(10).join().isEmpty());
    }

    @Test
    public void discardPersistsTombstoneBeforeEndingOwnership() {
        repository.startSession(
                        WatchActivityRepository.StartRequest
                                .builder(SESSION_ID, LEASE, 1_000L)
                                .build())
                .join();

        repository.discardSession(
                        SESSION_ID,
                        LEASE,
                        CanonicalTransfers.tombstone(
                                TRANSFER_ID,
                                SESSION_ID,
                                1L,
                                0L,
                                TombstoneOrigin.TOMBSTONE_ORIGIN_WATCH,
                                1_500L),
                        1_500L)
                .join();

        WatchActivityRepository.SessionSnapshot state =
                repository.session(SESSION_ID).join();
        assertEquals(WatchActivityRepository.LifecycleState.DISCARDED, state.getState());
        assertEquals(0L, state.getLeaseToken());
        assertNotNull(database.watchDao().tombstone(SESSION_ID));
    }

    @Test
    public void matchingExactCommitAckIsTransactionalAndDuplicateSafe() {
        repository.startSession(
                        WatchActivityRepository.StartRequest
                                .builder(SESSION_ID, LEASE, 1_000L)
                                .build())
                .join();
        WatchActivityRepository.OutboxItem outbox = repository.finishSession(
                        SESSION_ID,
                        LEASE,
                        activity(1L, sample(1L, 1_500L, 47D, -122D, 145D)),
                        TRANSFER_ID,
                        2_100L)
                .join()
                .getOutbox();
        repository.transitionOutbox(
                        TRANSFER_ID,
                        WatchActivityRepository.OUTBOX_READY,
                        WatchActivityRepository.OUTBOX_SENDING,
                        2_200L,
                        null,
                        null)
                .join();
        repository.transitionOutbox(
                        TRANSFER_ID,
                        WatchActivityRepository.OUTBOX_SENDING,
                        WatchActivityRepository.OUTBOX_AWAITING_COMMIT_ACK,
                        null,
                        null,
                        null)
                .join();
        ActivityTransferFile transfer =
                CanonicalTransfers.parseActivityTransfer(outbox.getPayload()).getTransfer();
        byte[] acknowledgement = CanonicalTransfers.commitAck(
                        transfer,
                        1L,
                        AckResult.ACK_RESULT_ACCEPTED,
                        null,
                        null)
                .toByteArray();

        WatchActivityRepository.AckAcceptance first =
                repository.acceptCommitAck(acknowledgement, 2_300L).join();
        WatchActivityRepository.AckAcceptance duplicate =
                repository.acceptCommitAck(acknowledgement, 2_400L).join();

        assertTrue(first.isCommittedNow());
        assertFalse(duplicate.isCommittedNow());
        assertEquals(
                WatchActivityRepository.OUTBOX_COMMITTED,
                repository.outbox(TRANSFER_ID).join().getState());
        assertArrayEquals(
                acknowledgement,
                repository.outbox(TRANSFER_ID).join().getCommitAckPayload());
        assertTrue(repository.pendingOutbox(10).join().isEmpty());
    }

    @Test
    public void processRestartReclaimsSendingAndTombstoneAckCommitsExactOutbox() {
        repository.startSession(
                        WatchActivityRepository.StartRequest
                                .builder(SESSION_ID, LEASE, 1_000L)
                                .build())
                .join();
        repository.finishSession(
                        SESSION_ID,
                        LEASE,
                        activity(1L, sample(1L, 1_500L, 47D, -122D, 145D)),
                        TRANSFER_ID,
                        2_100L)
                .join();
        repository.transitionOutbox(
                        TRANSFER_ID,
                        WatchActivityRepository.OUTBOX_READY,
                        WatchActivityRepository.OUTBOX_SENDING,
                        2_200L,
                        null,
                        null)
                .join();

        assertEquals(
                WatchActivityRepository.OUTBOX_RETRYABLE,
                repository.reclaimPendingOutbox(10, 2_300L)
                        .join()
                        .get(0)
                        .getState());

        String discardSession = "697c452d-c952-5ba6-9476-1b0838e0a50d";
        String tombstoneTransfer = "5c2c3a93-2d5f-4432-991c-54c092413099";
        repository.startSession(
                        WatchActivityRepository.StartRequest
                                .builder(discardSession, 99L, 3_000L)
                                .build())
                .join();
        repository.discardSession(
                        discardSession,
                        99L,
                        CanonicalTransfers.tombstone(
                                tombstoneTransfer,
                                discardSession,
                                1L,
                                0L,
                                TombstoneOrigin.TOMBSTONE_ORIGIN_WATCH,
                                3_500L),
                        3_500L)
                .join();
        WatchActivityRepository.TombstoneOutboxItem tombstone =
                repository.tombstoneOutbox(tombstoneTransfer).join();
        byte[] ack = CommitAck.newBuilder()
                .setSchemaVersion(1)
                .setTransferId(tombstoneTransfer)
                .setSessionId(discardSession)
                .setAcceptedSessionRevision(0L)
                .setPayloadSha256(ByteString.copyFrom(hexBytes(
                        tombstone.getPayloadSha256())))
                .setResult(AckResult.ACK_RESULT_ACCEPTED)
                .setDeleteRevision(1L)
                .setTombstoneOrigin(TombstoneOrigin.TOMBSTONE_ORIGIN_WATCH)
                .build()
                .toByteArray();

        assertTrue(repository.acceptTombstoneCommitAck(ack, 3_600L)
                .join()
                .isCommittedNow());
        assertFalse(repository.acceptTombstoneCommitAck(ack, 3_700L)
                .join()
                .isCommittedNow());
        assertTrue(repository.pendingTombstoneOutbox(10).join().isEmpty());
    }

    @Test
    public void reservedCompletionAndPersistedSamplesSurviveRepositoryRecreation() {
        MeasurementSample finalSample =
                sample(1L, 1_500L, 47D, -122D, 145D);
        repository.startSession(
                        WatchActivityRepository.StartRequest
                                .builder(SESSION_ID, LEASE, 1_000L)
                                .build())
                .join();
        repository.writeSample(SESSION_ID, LEASE, finalSample).join();
        WatchActivityRepository.SessionSnapshot reserved =
                repository.reserveCompletion(
                                SESSION_ID,
                                LEASE,
                                1L,
                                TRANSFER_ID,
                                1_900L)
                        .join();
        assertEquals(TRANSFER_ID, reserved.getCompletionTransferId());
        assertEquals(Long.valueOf(1L), reserved.getCompletionRevision());

        WatchActivityRepository replacement =
                new WatchActivityRepository(database, Runnable::run);
        assertEquals(1, replacement.samples(SESSION_ID).join().size());
        WatchActivityRepository.CompletionResult completed =
                replacement.finishSessionFromPersistedSamples(
                                SESSION_ID,
                                LEASE,
                                activity(1L, finalSample)
                                        .toBuilder()
                                        .clearSamples()
                                        .build(),
                                2_100L)
                        .join();

        assertEquals(TRANSFER_ID, completed.getOutbox().getTransferId());
        assertEquals(1L, completed.getSession().getSampleCount());
        assertEquals(
                TRANSFER_ID,
                replacement.outboxForSessionRevision(SESSION_ID, 1L)
                        .join()
                        .getTransferId());
    }

    @Test
    public void incomingPhoneTombstoneSuppressesCoveredActivityAndRepeatsExactAck() {
        repository.startSession(
                        WatchActivityRepository.StartRequest
                                .builder(SESSION_ID, LEASE, 1_000L)
                                .build())
                .join();
        repository.finishSession(
                        SESSION_ID,
                        LEASE,
                        activity(1L, sample(1L, 1_500L, 47D, -122D, 145D)),
                        TRANSFER_ID,
                        2_100L)
                .join();
        String phoneTombstoneTransfer = "e276dff6-10b5-48a7-a9ed-9e0b5497becd";
        byte[] tombstone = CanonicalTransfers.tombstone(
                        phoneTombstoneTransfer,
                        SESSION_ID,
                        1L,
                        1L,
                        TombstoneOrigin.TOMBSTONE_ORIGIN_PHONE,
                        2_200L)
                .toByteArray();

        WatchActivityRepository.TombstoneReceiveResult first =
                repository.acceptTombstoneTransfer(tombstone, 2_300L).join();
        WatchActivityRepository.TombstoneReceiveResult duplicate =
                repository.acceptTombstoneTransfer(tombstone, 2_400L).join();

        assertTrue(first.isCreated());
        assertFalse(duplicate.isCreated());
        assertArrayEquals(
                first.getExactAcknowledgementBytes(),
                duplicate.getExactAcknowledgementBytes());
        assertEquals(
                WatchActivityRepository.LifecycleState.DISCARDED,
                repository.session(SESSION_ID).join().getState());
        assertEquals(
                "TOMBSTONED",
                repository.outbox(TRANSFER_ID).join().getState());
        assertTrue(repository.pendingOutbox(10).join().isEmpty());
    }

    private static ActivityRecord activity(long revision, MeasurementSample sample) {
        return ActivityRecord.newBuilder()
                .setSessionId(SESSION_ID)
                .setWatchRevision(revision)
                .setActivityType(ActivityType.ACTIVITY_TYPE_RUNNING)
                .setStartTimeUtcMs(1_000L)
                .setEndTimeUtcMs(2_000L)
                .setElapsedDurationMs(1_000L)
                .setActiveDurationMs(900L)
                .setTimeZoneId("America/Los_Angeles")
                .setUtcOffsetMinutesAtStart(-420)
                .setSource(source(SourceKind.SOURCE_KIND_HEALTH_SERVICES))
                .addSamples(sample)
                .build();
    }

    private static MeasurementSample sample(
            long sequence,
            long timestamp,
            double latitude,
            double longitude,
            double heartRate) {
        return MeasurementSample.newBuilder()
                .setSequence(sequence)
                .setTimestampUtcMs(timestamp)
                .setMonotonicTimeMs(50_500L)
                .setBootId("boot-a")
                .setSessionElapsedDurationMs(500L)
                .setSessionActiveDurationMs(450L)
                .setLocation(PositionMeasurement.newBuilder()
                        .setValue(Position.newBuilder()
                                .setLatitudeDegrees(latitude)
                                .setLongitudeDegrees(longitude))
                        .setStatus(status("LOCATION", "degrees")))
                .setHeartRateBpm(DoubleMeasurement.newBuilder()
                        .setValue(heartRate)
                        .setStatus(status("HEART_RATE_BPM", "beats/minute")))
                .build();
    }

    private static MeasurementStatus status(String identifier, String unit) {
        return MeasurementStatus.newBuilder()
                .setMetricIdentifier(identifier)
                .setPublicUnit(unit)
                .setAggregation(AggregationSemantics.AGGREGATION_SEMANTICS_INSTANTANEOUS)
                .setExerciseCapability(CapabilityState.CAPABILITY_STATE_SUPPORTED)
                .setRuntimePermission(PermissionState.PERMISSION_STATE_GRANTED)
                .setRawHardware(CapabilityState.CAPABILITY_STATE_SUPPORTED)
                .setVendorCapability(CapabilityState.CAPABILITY_STATE_UNKNOWN)
                .setLiveAvailability(LiveAvailability.LIVE_AVAILABILITY_AVAILABLE)
                .setSource(source(SourceKind.SOURCE_KIND_HEALTH_SERVICES))
                .setQuality(Quality.QUALITY_VALID)
                .build();
    }

    private static SourceMetadata source(SourceKind kind) {
        return SourceMetadata.newBuilder()
                .setKind(kind)
                .setProvider("androidx.health.services")
                .setAppContractVersion(1)
                .build();
    }

    private static void assertFutureCode(String code, Runnable operation) {
        try {
            operation.run();
            fail("Expected " + code);
        } catch (CompletionException expected) {
            assertTrue(expected.getCause()
                    instanceof com.long2know.utilities.contract.CanonicalContractException);
            assertEquals(
                    code,
                    ((com.long2know.utilities.contract.CanonicalContractException)
                            expected.getCause()).getCode());
        }
    }

    private static byte[] hexBytes(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int index = 0; index < value.length(); index += 2) {
            bytes[index / 2] = (byte) Integer.parseInt(
                    value.substring(index, index + 2),
                    16);
        }
        return bytes;
    }
}
