package com.long2know.sportlogger.persistence.phone;

import android.content.Context;
import android.os.CancellationSignal;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.long2know.utilities.contract.CanonicalContractException;
import com.long2know.utilities.contract.CanonicalPayload;
import com.long2know.utilities.contract.CanonicalTransfers;
import com.long2know.utilities.contract.proto.AckResult;
import com.long2know.utilities.contract.proto.ActivityRecord;
import com.long2know.utilities.contract.proto.ActivitySummary;
import com.long2know.utilities.contract.proto.ActivityType;
import com.long2know.utilities.contract.proto.AggregationSemantics;
import com.long2know.utilities.contract.proto.CapabilityState;
import com.long2know.utilities.contract.proto.DoubleMeasurement;
import com.long2know.utilities.contract.proto.LiveAvailability;
import com.long2know.utilities.contract.proto.LongMeasurement;
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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 26)
public class PhoneActivityRepositoryTest {
    private static final String SESSION_ID = "9acfd923-cf13-58a8-a8a5-6eaa1128f7e7";
    private static final String TRANSFER_ID = "3cd7c9ed-c702-486d-b87a-681b218641c8";

    private Context context;
    private PhoneDatabase database;
    private PhoneActivityRepository repository;
    private String fileDatabaseName;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        database = Room.inMemoryDatabaseBuilder(context, PhoneDatabase.class)
                .allowMainThreadQueries()
                .build();
        repository = new PhoneActivityRepository(database);
    }

    @After
    public void tearDown() {
        if (database.isOpen()) {
            database.close();
        }
        if (fileDatabaseName != null) {
            context.deleteDatabase(fileDatabaseName);
        }
    }

    @Test
    public void acceptStoresImmutableRevisionSummaryDetailsPointsAndExactAck() {
        byte[] transfer = transfer(TRANSFER_ID, activity(1L, sample(1L, 0D, 0D, 145D)));

        PhoneActivityRepository.AcceptResult result =
                repository.acceptActivityTransfer(transfer, 3_000L);

        assertTrue(result.isCreated());
        assertEquals(AckResult.ACK_RESULT_ACCEPTED, result.getAcknowledgement().getResult());
        assertArrayEquals(
                result.getAcknowledgement().toByteArray(),
                result.getExactAcknowledgementBytes());
        List<PhoneActivityRepository.ActivitySummary> summaries =
                repository.summaries(0, 10);
        assertEquals(1, summaries.size());
        assertEquals(SESSION_ID, summaries.get(0).getSessionId());
        assertEquals(1L, summaries.get(0).getRoutePointCount());
        assertEquals(1L, summaries.get(0).getHeartRateSampleCount());
        assertEquals(321.125D, summaries.get(0).getDistanceM(), 0D);
        PhoneActivityRepository.ActivityDetail detail = repository.detail(SESSION_ID, 1L);
        assertNotNull(detail);
        assertEquals("America/Los_Angeles", detail.getActivity().getTimeZoneId());
        PhoneActivityRepository.PointPage points = repository.pointPage(
                SESSION_ID,
                1L,
                -1L,
                100,
                new CancellationSignal());
        assertEquals(1, points.getSamples().size());
        Position position = points.getSamples().get(0).getLocation().getValue();
        assertEquals(0D, position.getLatitudeDegrees(), 0D);
        assertEquals(0D, position.getLongitudeDegrees(), 0D);
        assertFalse(position.hasAltitudeM());
    }

    @Test
    public void duplicateReturnsExactStoredAckAndTransferIdConflictFails() {
        byte[] first = transfer(TRANSFER_ID, activity(1L, sample(1L, 47D, -122D, 145D)));
        PhoneActivityRepository.AcceptResult accepted =
                repository.acceptActivityTransfer(first, 3_000L);

        PhoneActivityRepository.AcceptResult duplicate =
                repository.acceptActivityTransfer(first, 4_000L);

        assertFalse(duplicate.isCreated());
        assertArrayEquals(
                accepted.getExactAcknowledgementBytes(),
                duplicate.getExactAcknowledgementBytes());

        byte[] conflicting = transfer(
                TRANSFER_ID,
                activity(1L, sample(1L, 48D, -123D, 146D)));
        assertCode(
                "TRANSFER_ID_REUSED",
                () -> repository.acceptActivityTransfer(conflicting, 5_000L));
    }

    @Test
    public void higherRevisionPreservesPhoneMetadataAndOlderRevisionRemainsReadable() {
        repository.acceptActivityTransfer(
                transfer(TRANSFER_ID, activity(1L, sample(1L, 47D, -122D, 145D))),
                3_000L);
        repository.setMetadata(
                SESSION_ID,
                "Morning run",
                "Felt controlled",
                Arrays.asList("easy", "rain"),
                3_500L);

        PhoneActivityRepository.AcceptResult update =
                repository.acceptActivityTransfer(
                        transfer(
                                "46b6425f-18e0-4de4-8f2c-5afc56ba6859",
                                activity(2L, sample(1L, 47.1D, -122.1D, 146D))),
                        4_000L);

        assertEquals(AckResult.ACK_RESULT_ACCEPTED, update.getAcknowledgement().getResult());
        assertEquals(2L, repository.summaries(0, 10).get(0).getRevision());
        assertEquals("Morning run", repository.metadata(SESSION_ID).getTitle());
        assertEquals(Arrays.asList("easy", "rain"), repository.metadata(SESSION_ID).getTags());
        assertNotNull(repository.detail(SESSION_ID, 1L));
        assertNotNull(repository.detail(SESSION_ID, 2L));
    }

    @Test
    public void atomicDeleteAllocatesNextObservedRevisionAndReplaysExactOperation() {
        repository.acceptActivityTransfer(
                transfer(TRANSFER_ID, activity(3L, sample(1L, 47D, -122D, 145D))),
                1_000L);

        PhoneActivityRepository.LocalDeleteResult first =
                repository.deleteLocal(SESSION_ID, 3L, 2_000L);
        PhoneActivityRepository.LocalDeleteResult replay =
                repository.deleteLocal(SESSION_ID, 3L, 3_000L);

        assertTrue(first.isCreated());
        assertFalse(replay.isCreated());
        assertEquals(4L, first.getTombstone().getDeleteRevision());
        assertEquals(3L, first.getTombstone().getCoveredSessionRevision());
        assertEquals(
                first.getTombstone().getTransferId(),
                replay.getTombstone().getTransferId());
        assertArrayEquals(first.getOutbox().getPayload(), replay.getOutbox().getPayload());
        assertEquals(1, repository.pendingTombstoneOutbox(10).size());
    }

    @Test
    public void atomicDeleteUsesWinningTombstoneAndCurrentHeadForNextRevision() {
        repository.acceptActivityTransfer(
                transfer(TRANSFER_ID, activity(5L, sample(1L, 47D, -122D, 145D))),
                1_000L);
        byte[] priorWatchDelete = CanonicalTransfers.tombstone(
                        "7d0df660-9a30-49bb-86a3-770f3cde787c",
                        SESSION_ID,
                        7L,
                        4L,
                        TombstoneOrigin.TOMBSTONE_ORIGIN_WATCH,
                        1_500L)
                .toByteArray();
        repository.acceptTombstoneTransfer(priorWatchDelete, 1_600L);

        PhoneActivityRepository.LocalDeleteResult result =
                repository.deleteLocal(SESSION_ID, 5L, 2_000L);

        assertEquals(8L, result.getTombstone().getDeleteRevision());
        assertEquals(5L, result.getTombstone().getCoveredSessionRevision());
        assertEquals(
                TombstoneOrigin.TOMBSTONE_ORIGIN_PHONE,
                result.getTombstone().getOrigin());
        assertTrue(repository.summaries(0, 10).isEmpty());
    }

    @Test
    public void staleDeleteExpectedRevisionCannotHideNewerAcceptedHead() {
        repository.acceptActivityTransfer(
                transfer(TRANSFER_ID, activity(1L, sample(1L, 47D, -122D, 145D))),
                1_000L);
        PhoneActivityRepository competingRepository =
                new PhoneActivityRepository(database);
        competingRepository.acceptActivityTransfer(
                transfer(
                        "2f9a51ae-a28d-4ed3-b470-a32136cac1cc",
                        activity(2L, sample(1L, 47.1D, -122.1D, 146D))),
                1_500L);

        assertCode(
                "DELETE_HEAD_CHANGED",
                () -> repository.deleteLocal(SESSION_ID, 1L, 2_000L));

        assertEquals(2L, repository.summaries(0, 10).get(0).getRevision());
        assertTrue(repository.pendingTombstoneOutbox(10).isEmpty());
    }

    @Test
    public void deleteReplayAfterDatabaseReopenReturnsExactStoredOperation() {
        database.close();
        fileDatabaseName = "phone-delete-replay-" + System.nanoTime() + ".db";
        database = Room.databaseBuilder(context, PhoneDatabase.class, fileDatabaseName)
                .allowMainThreadQueries()
                .build();
        repository = new PhoneActivityRepository(database);
        repository.acceptActivityTransfer(
                transfer(TRANSFER_ID, activity(3L, sample(1L, 47D, -122D, 145D))),
                1_000L);
        PhoneActivityRepository.LocalDeleteResult first =
                repository.deleteLocal(SESSION_ID, 3L, 2_000L);
        byte[] exactOutbox = first.getOutbox().getPayload();
        String transferId = first.getTombstone().getTransferId();

        database.close();
        database = Room.databaseBuilder(context, PhoneDatabase.class, fileDatabaseName)
                .allowMainThreadQueries()
                .build();
        repository = new PhoneActivityRepository(database);
        PhoneActivityRepository.LocalDeleteResult replay =
                repository.deleteLocal(SESSION_ID, 3L, 3_000L);

        assertFalse(replay.isCreated());
        assertEquals(transferId, replay.getTombstone().getTransferId());
        assertArrayEquals(exactOutbox, replay.getOutbox().getPayload());
        assertEquals(1, repository.pendingTombstoneOutbox(10).size());
    }

    @Test
    public void deleteUnsignedOverflowIsTypedAndLeavesHeadVisible() {
        repository.acceptActivityTransfer(
                transfer(TRANSFER_ID, activity(-1L, sample(1L, 47D, -122D, 145D))),
                1_000L);

        assertCode(
                "DELETE_REVISION_EXHAUSTED",
                () -> repository.deleteLocal(SESSION_ID, -1L, 2_000L));

        assertEquals(-1L, repository.summaries(0, 10).get(0).getRevision());
        assertTrue(repository.pendingTombstoneOutbox(10).isEmpty());
    }

    @Test
    public void deleteTransactionFailureRollsBackTombstoneOutboxAndHead() {
        repository.acceptActivityTransfer(
                transfer(TRANSFER_ID, activity(1L, sample(1L, 47D, -122D, 145D))),
                1_000L);
        PhoneActivityRepository failingRepository =
                new PhoneActivityRepository(
                        database,
                        tombstone -> {
                            throw new SimulatedDeleteFailure();
                        });

        try {
            failingRepository.deleteLocal(SESSION_ID, 1L, 2_000L);
            fail("Expected simulated delete failure");
        } catch (SimulatedDeleteFailure expected) {
            assertEquals(1L, repository.summaries(0, 10).get(0).getRevision());
            assertNull(database.phoneDao().tombstone(SESSION_ID));
            assertTrue(repository.pendingTombstoneOutbox(10).isEmpty());
        }
    }

    @Test
    public void remoteDeleteIsTypedAlreadyDeletedNotLocalReplay() {
        repository.acceptActivityTransfer(
                transfer(TRANSFER_ID, activity(1L, sample(1L, 47D, -122D, 145D))),
                1_000L);
        repository.acceptTombstoneTransfer(
                CanonicalTransfers.tombstone(
                                "5416cacf-c8fd-4755-8781-91c7f5a9cd63",
                                SESSION_ID,
                                2L,
                                1L,
                                TombstoneOrigin.TOMBSTONE_ORIGIN_WATCH,
                                1_500L)
                        .toByteArray(),
                1_600L);

        assertCode(
                "DELETE_ALREADY_DELETED",
                () -> repository.deleteLocal(SESSION_ID, 1L, 2_000L));
        assertTrue(repository.pendingTombstoneOutbox(10).isEmpty());
    }

    @Test
    public void explicitLowLevelDeleteRejectsInvalidOrdering() {
        repository.acceptActivityTransfer(
                transfer(TRANSFER_ID, activity(3L, sample(1L, 47D, -122D, 145D))),
                1_000L);

        assertCode(
                "DELETE_REVISION_INVALID",
                () -> repository.deleteLocal(
                        SESSION_ID,
                        3L,
                        3L,
                        "073fe958-93f7-4715-bdf1-c45846390b57",
                        2_000L));

        assertEquals(3L, repository.summaries(0, 10).get(0).getRevision());
        assertTrue(repository.pendingTombstoneOutbox(10).isEmpty());
    }

    @Test
    public void coveredRevisionIsAcknowledgedAsTombstonedWithoutResurrection() {
        repository.deleteLocal(
                SESSION_ID,
                4L,
                3L,
                "bfe6b015-2188-48be-8a17-bb1e2e676604",
                2_000L);

        PhoneActivityRepository.AcceptResult result =
                repository.acceptActivityTransfer(
                        transfer(TRANSFER_ID, activity(3L, sample(1L, 47D, -122D, 145D))),
                        3_000L);

        assertEquals(AckResult.ACK_RESULT_TOMBSTONED, result.getAcknowledgement().getResult());
        assertTrue(repository.summaries(0, 10).isEmpty());
        assertNull(repository.detail(SESSION_ID, 3L));
    }

    @Test
    public void connectorLedgerRetainsEveryRemoteIdAndRequiresLeaseCAS() {
        repository.acceptActivityTransfer(
                transfer(TRANSFER_ID, activity(1L, sample(1L, 47D, -122D, 145D))),
                900L);
        PhoneActivityRepository.ConnectorKey key =
                new PhoneActivityRepository.ConnectorKey(
                        SESSION_ID,
                        1L,
                        "endurain",
                        "https://endurain.example",
                        "connection-a");
        repository.prepareConnectorDelivery(
                key,
                "credential-ref-a",
                500L,
                repeat("ab", 32),
                1_000L);

        assertTrue(repository.claimConnectorDelivery(
                key,
                PhoneActivityRepository.ConnectorState.PENDING,
                91L,
                "worker-a",
                5_000L,
                1_100L));
        PhoneActivityRepository.ConnectorDelivery first =
                repository.recordConnectorSuccess(key, 91L, Collections.singletonList(10L), 1_200L);
        assertEquals(PhoneActivityRepository.ConnectorState.DELIVERED, first.getState());
        assertEquals(Collections.singletonList(10L), first.getRemoteIds());

        assertFalse(repository.claimConnectorDelivery(
                key,
                PhoneActivityRepository.ConnectorState.PENDING,
                92L,
                "worker-b",
                6_000L,
                1_300L));
        PhoneActivityRepository.ConnectorDelivery second =
                repository.reconcileRemoteIds(key, Arrays.asList(10L, 11L), 1_600L);
        assertEquals(PhoneActivityRepository.ConnectorState.DUPLICATE_REVIEW, second.getState());
        assertEquals(Arrays.asList(10L, 11L), second.getRemoteIds());
        repository.markConnectorDeleteUnsupported(key, 1_700L);
        assertTrue(repository.connectorDelivery(key).isDeleteUnsupported());
    }

    @Test
    public void connectorDeliveryExposesPersistedAmbiguousAndDefinitiveFailuresAfterRestart() {
        repository.acceptActivityTransfer(
                transfer(TRANSFER_ID, activity(1L, sample(1L, 47D, -122D, 145D))),
                900L);
        PhoneActivityRepository.ConnectorKey key =
                new PhoneActivityRepository.ConnectorKey(
                        SESSION_ID,
                        1L,
                        "endurain",
                        "https://endurain.example",
                        "connection-status");
        repository.prepareConnectorDelivery(
                key,
                "credential-ref-a",
                500L,
                repeat("cd", 32),
                1_000L);
        assertTrue(repository.claimConnectorDelivery(
                key,
                PhoneActivityRepository.ConnectorState.PENDING,
                101L,
                "worker-a",
                5_000L,
                1_100L));
        repository.transitionConnectorDelivery(
                key,
                101L,
                PhoneActivityRepository.ConnectorState.PENDING,
                PhoneActivityRepository.ConnectorState.UNKNOWN_DELIVERY,
                null,
                "DROPPED_RESPONSE",
                "The provider may have accepted the upload",
                1_200L);

        PhoneActivityRepository restarted = new PhoneActivityRepository(database);
        PhoneActivityRepository.ConnectorDelivery ambiguous =
                restarted.connectorDelivery(key);
        assertEquals(
                PhoneActivityRepository.ConnectorState.UNKNOWN_DELIVERY,
                ambiguous.getState());
        assertEquals("DROPPED_RESPONSE", ambiguous.getLastFailureCode());
        assertEquals(
                "The provider may have accepted the upload",
                ambiguous.getLastFailureMessage());

        restarted.authorizeDuplicateRiskRetry(key, 1_300L);
        assertTrue(restarted.claimConnectorDelivery(
                key,
                PhoneActivityRepository.ConnectorState.PENDING,
                102L,
                "worker-b",
                6_000L,
                1_400L));
        restarted.transitionConnectorDelivery(
                key,
                102L,
                PhoneActivityRepository.ConnectorState.PENDING,
                PhoneActivityRepository.ConnectorState.FAILED,
                null,
                "UNSUPPORTED_ACTIVITY",
                "The provider rejected this activity permanently",
                1_500L);

        PhoneActivityRepository.ConnectorDelivery definitive =
                new PhoneActivityRepository(database).connectorDelivery(key);
        assertEquals(
                PhoneActivityRepository.ConnectorState.FAILED,
                definitive.getState());
        assertEquals("UNSUPPORTED_ACTIVITY", definitive.getLastFailureCode());
        assertEquals(
                "The provider rejected this activity permanently",
                definitive.getLastFailureMessage());
    }

    @Test
    public void expiredClaimRecoversAsUnknownWithoutTouchingActiveOrUnclaimedRows() {
        repository.acceptActivityTransfer(
                transfer(TRANSFER_ID, activity(1L, sample(1L, 47D, -122D, 145D))),
                900L);
        PhoneActivityRepository.ConnectorKey claimedKey =
                new PhoneActivityRepository.ConnectorKey(
                        SESSION_ID,
                        1L,
                        "endurain",
                        "https://endurain.example",
                        "claimed-connection");
        repository.prepareConnectorDelivery(
                claimedKey,
                "credential-ref-a",
                500L,
                repeat("ef", 32),
                1_000L);
        assertTrue(repository.claimConnectorDelivery(
                claimedKey,
                PhoneActivityRepository.ConnectorState.PENDING,
                201L,
                "worker-a",
                1_500L,
                1_100L));

        assertFalse(repository.recoverExpiredConnectorClaimAsUnknown(
                claimedKey,
                1_499L));
        PhoneActivityRepository.ConnectorDelivery active =
                repository.connectorDelivery(claimedKey);
        assertEquals(PhoneActivityRepository.ConnectorState.PENDING, active.getState());
        assertNull(active.getLastFailureCode());

        assertTrue(repository.recoverExpiredConnectorClaimAsUnknown(
                claimedKey,
                1_500L));
        PhoneActivityRepository.ConnectorDelivery recovered =
                new PhoneActivityRepository(database).connectorDelivery(claimedKey);
        assertEquals(
                PhoneActivityRepository.ConnectorState.UNKNOWN_DELIVERY,
                recovered.getState());
        assertEquals(
                "PROCESS_RESTART_AMBIGUOUS",
                recovered.getLastFailureCode());
        assertEquals(
                "Recovered expired claimed delivery after process restart",
                recovered.getLastFailureMessage());
        assertNull(recovered.getRetryNotBeforeUtcMs());
        assertFalse(repository.recoverExpiredConnectorClaimAsUnknown(
                claimedKey,
                2_000L));

        PhoneActivityRepository.ConnectorKey unclaimedKey =
                new PhoneActivityRepository.ConnectorKey(
                        SESSION_ID,
                        1L,
                        "endurain",
                        "https://endurain.example",
                        "unclaimed-connection");
        repository.prepareConnectorDelivery(
                unclaimedKey,
                "credential-ref-b",
                500L,
                repeat("12", 32),
                1_000L);
        assertFalse(repository.recoverExpiredConnectorClaimAsUnknown(
                unclaimedKey,
                5_000L));
        PhoneActivityRepository.ConnectorDelivery unclaimed =
                repository.connectorDelivery(unclaimedKey);
        assertEquals(
                PhoneActivityRepository.ConnectorState.PENDING,
                unclaimed.getState());
        assertNull(unclaimed.getLastFailureCode());
    }

    @Test
    public void phoneOutboxAndIncomingWatchTombstoneUseExactDuplicateSafeAcks() {
        repository.acceptActivityTransfer(
                transfer(TRANSFER_ID, activity(1L, sample(1L, 47D, -122D, 145D))),
                1_000L);
        String phoneDeleteTransfer = "528b5fc9-86dd-4b57-84a4-461fa29e59ec";
        repository.deleteLocal(
                SESSION_ID,
                2L,
                1L,
                phoneDeleteTransfer,
                2_000L);
        PhoneActivityRepository.TombstoneOutboxItem outbox =
                repository.tombstoneOutbox(phoneDeleteTransfer);
        repository.transitionTombstoneOutbox(
                phoneDeleteTransfer,
                PhoneActivityRepository.TOMBSTONE_READY,
                PhoneActivityRepository.TOMBSTONE_SENDING,
                2_100L,
                null,
                null);
        repository.transitionTombstoneOutbox(
                phoneDeleteTransfer,
                PhoneActivityRepository.TOMBSTONE_SENDING,
                PhoneActivityRepository.TOMBSTONE_AWAITING_COMMIT_ACK,
                null,
                null,
                null);
        byte[] phoneAck = CanonicalTransfers.tombstoneCommitAck(
                        CanonicalTransfers.parseTombstone(outbox.getPayload()),
                        AckResult.ACK_RESULT_STALE,
                        null,
                        null)
                .toByteArray();

        assertTrue(repository.acceptTombstoneCommitAck(phoneAck, 2_200L)
                .isCommittedNow());
        assertFalse(repository.acceptTombstoneCommitAck(phoneAck, 2_300L)
                .isCommittedNow());
        assertTrue(repository.pendingTombstoneOutbox(10).isEmpty());

        String watchDeleteTransfer = "aa880852-2437-4539-9c52-c0080b1c800f";
        byte[] incoming = CanonicalTransfers.tombstone(
                        watchDeleteTransfer,
                        SESSION_ID,
                        2L,
                        1L,
                        TombstoneOrigin.TOMBSTONE_ORIGIN_WATCH,
                        3_000L)
                .toByteArray();
        PhoneActivityRepository.TombstoneReceiveResult first =
                repository.acceptTombstoneTransfer(incoming, 3_100L);
        PhoneActivityRepository.TombstoneReceiveResult duplicate =
                repository.acceptTombstoneTransfer(incoming, 3_200L);

        assertTrue(first.isCreated());
        assertFalse(duplicate.isCreated());
        assertArrayEquals(
                first.getExactAcknowledgementBytes(),
                duplicate.getExactAcknowledgementBytes());
        assertEquals(AckResult.ACK_RESULT_STALE,
                first.getAcknowledgement().getResult());
        assertTrue(repository.summaries(0, 10).isEmpty());
    }

    private static byte[] transfer(String transferId, ActivityRecord activity) {
        return CanonicalTransfers.activityTransfer(
                        transferId,
                        CanonicalPayload.create(activity))
                .toByteArray();
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
                .setSummary(ActivitySummary.newBuilder()
                        .setDistanceM(DoubleMeasurement.newBuilder()
                                .setValue(321.125D)
                                .setStatus(status("DISTANCE_TOTAL", "metres")))
                        .setSteps(LongMeasurement.newBuilder()
                                .setValue(400L)
                                .setStatus(status("STEPS_TOTAL", "count"))))
                .setSource(SourceMetadata.newBuilder()
                        .setKind(SourceKind.SOURCE_KIND_HEALTH_SERVICES)
                        .setProvider("androidx.health.services")
                        .setManufacturer("Example")
                        .setModel("Watch")
                        .setAppContractVersion(1))
                .addSamples(sample)
                .build();
    }

    private static MeasurementSample sample(
            long sequence,
            double latitude,
            double longitude,
            double heartRate) {
        return MeasurementSample.newBuilder()
                .setSequence(sequence)
                .setTimestampUtcMs(1_500L)
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
                .setAggregation(
                        identifier.endsWith("_TOTAL")
                                ? AggregationSemantics.AGGREGATION_SEMANTICS_CUMULATIVE
                                : AggregationSemantics.AGGREGATION_SEMANTICS_INSTANTANEOUS)
                .setExerciseCapability(CapabilityState.CAPABILITY_STATE_SUPPORTED)
                .setRuntimePermission(PermissionState.PERMISSION_STATE_GRANTED)
                .setRawHardware(CapabilityState.CAPABILITY_STATE_SUPPORTED)
                .setVendorCapability(CapabilityState.CAPABILITY_STATE_UNKNOWN)
                .setLiveAvailability(LiveAvailability.LIVE_AVAILABILITY_AVAILABLE)
                .setSource(SourceMetadata.newBuilder()
                        .setKind(SourceKind.SOURCE_KIND_HEALTH_SERVICES)
                        .setAppContractVersion(1))
                .setQuality(Quality.QUALITY_VALID)
                .build();
    }

    private static void assertCode(String code, Runnable operation) {
        try {
            operation.run();
            fail("Expected " + code);
        } catch (CanonicalContractException expected) {
            assertEquals(code, expected.getCode());
        }
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }

    private static final class SimulatedDeleteFailure extends RuntimeException {
    }
}
