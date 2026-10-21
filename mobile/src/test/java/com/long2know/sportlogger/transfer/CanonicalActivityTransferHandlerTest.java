package com.long2know.sportlogger.transfer;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.google.protobuf.ByteString;
import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository;
import com.long2know.sportlogger.persistence.phone.PhoneDatabase;
import com.long2know.utilities.contract.CanonicalContractException;
import com.long2know.utilities.contract.CanonicalPayload;
import com.long2know.utilities.contract.CanonicalTransfers;
import com.long2know.utilities.contract.proto.AckResult;
import com.long2know.utilities.contract.proto.ActivityRecord;
import com.long2know.utilities.contract.proto.ActivityTransferFile;
import com.long2know.utilities.contract.proto.ActivityType;
import com.long2know.utilities.contract.proto.AggregationSemantics;
import com.long2know.utilities.contract.proto.CapabilityState;
import com.long2know.utilities.contract.proto.DoubleMeasurement;
import com.long2know.utilities.contract.proto.LiveAvailability;
import com.long2know.utilities.contract.proto.MeasurementSample;
import com.long2know.utilities.contract.proto.MeasurementStatus;
import com.long2know.utilities.contract.proto.PermissionState;
import com.long2know.utilities.contract.proto.Quality;
import com.long2know.utilities.contract.proto.SourceKind;
import com.long2know.utilities.contract.proto.SourceMetadata;
import com.long2know.utilities.transfer.CanonicalSyncProtocol;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.ArrayList;
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
public class CanonicalActivityTransferHandlerTest {
    private static final String NODE_ID = "watch-node";
    private static final String SESSION_ID = "83f4e1b9-6069-48ac-9478-c149d48de620";
    private static final String TRANSFER_ID = "2fc5f0a5-029b-45f8-9c4d-3f38dcf4e152";
    private static final String SECOND_TRANSFER_ID =
            "eb9127ce-4cbf-4f68-a89e-b6c4538d802f";

    private PhoneDatabase database;
    private PhoneActivityRepository repository;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        database = Room.inMemoryDatabaseBuilder(context, PhoneDatabase.class)
                .allowMainThreadQueries()
                .build();
        repository = new PhoneActivityRepository(database);
    }

    @After
    public void tearDown() {
        database.close();
    }

    @Test
    public void commitPrecedesExactAckAndDuplicateRecoversLostAck() {
        byte[] transfer = transfer(TRANSFER_ID, activity(1L, 145D));
        RecordingSender sender = new RecordingSender(repository, 1);
        CanonicalActivityTransferHandler handler =
                new CanonicalActivityTransferHandler(repository, sender);

        CanonicalActivityTransferHandler.Result first = handler.receive(
                NODE_ID,
                CanonicalSyncProtocol.activityChannelPath(TRANSFER_ID),
                transfer,
                3_000L);

        assertTrue(first.isRepositoryChanged());
        assertFalse(first.isAcknowledgementDelivered());
        assertNotNull(first.getAcknowledgementDeliveryFailure());
        assertEquals(1, repository.summaries(0, 10).size());
        assertEquals(AckResult.ACK_RESULT_ACCEPTED, first.getAcknowledgement().getResult());
        assertTrue(sender.persistedBeforeEverySend);

        CanonicalActivityTransferHandler.Result duplicate = handler.receive(
                NODE_ID,
                CanonicalSyncProtocol.activityChannelPath(TRANSFER_ID),
                transfer,
                4_000L);

        assertFalse(duplicate.isRepositoryChanged());
        assertTrue(duplicate.isAcknowledgementDelivered());
        assertArrayEquals(
                first.getExactAcknowledgementBytes(),
                duplicate.getExactAcknowledgementBytes());
        assertArrayEquals(
                first.getExactAcknowledgementBytes(),
                sender.delivered.get(0));
        assertEquals(1, repository.summaries(0, 10).size());
        assertTrue(sender.persistedBeforeEverySend);
    }

    @Test
    public void staleRevisionIsTypedAndCannotDowngradePhoneHead() {
        RecordingSender sender = new RecordingSender(repository, 0);
        CanonicalActivityTransferHandler handler =
                new CanonicalActivityTransferHandler(repository, sender);
        handler.receive(
                NODE_ID,
                CanonicalSyncProtocol.activityChannelPath(TRANSFER_ID),
                transfer(TRANSFER_ID, activity(2L, 150D)),
                3_000L);

        CanonicalActivityTransferHandler.Result stale = handler.receive(
                NODE_ID,
                CanonicalSyncProtocol.activityChannelPath(SECOND_TRANSFER_ID),
                transfer(SECOND_TRANSFER_ID, activity(1L, 145D)),
                4_000L);

        assertEquals(AckResult.ACK_RESULT_STALE, stale.getAcknowledgement().getResult());
        assertEquals(2L, stale.getAcknowledgement().getAcceptedSessionRevision());
        assertEquals(2L, repository.summaries(0, 10).get(0).getRevision());
        assertNull(repository.detail(SESSION_ID, 1L));
    }

    @Test
    public void coveredTombstoneReturnsTypedAckWithoutResurrection() {
        repository.deleteLocal(
                SESSION_ID,
                4L,
                3L,
                "30b555ea-5c21-47fe-a011-95137f98931b",
                2_000L);
        RecordingSender sender = new RecordingSender(repository, 0);

        CanonicalActivityTransferHandler.Result result =
                new CanonicalActivityTransferHandler(repository, sender)
                        .receive(
                                NODE_ID,
                                CanonicalSyncProtocol.activityChannelPath(TRANSFER_ID),
                                transfer(TRANSFER_ID, activity(3L, 145D)),
                                3_000L);

        assertEquals(
                AckResult.ACK_RESULT_TOMBSTONED,
                result.getAcknowledgement().getResult());
        assertTrue(result.getAcknowledgement().hasDeleteRevision());
        assertEquals(4L, result.getAcknowledgement().getDeleteRevision());
        assertTrue(repository.summaries(0, 10).isEmpty());
        assertNull(repository.detail(SESSION_ID, 3L));
    }

    @Test
    public void lengthHashAndPathMismatchNeverPersistOrAck() throws Exception {
        RecordingSender sender = new RecordingSender(repository, 0);
        CanonicalActivityTransferHandler handler =
                new CanonicalActivityTransferHandler(repository, sender);
        ActivityTransferFile valid = ActivityTransferFile.parseFrom(
                transfer(TRANSFER_ID, activity(1L, 145D)));

        assertCode(
                "TRANSFER_LENGTH_MISMATCH",
                () -> handler.receive(
                        NODE_ID,
                        CanonicalSyncProtocol.activityChannelPath(TRANSFER_ID),
                        valid.toBuilder()
                                .setPayloadLength(valid.getPayloadLength() + 1L)
                                .build()
                                .toByteArray(),
                        3_000L));
        assertCode(
                "TRANSFER_HASH_MISMATCH",
                () -> handler.receive(
                        NODE_ID,
                        CanonicalSyncProtocol.activityChannelPath(TRANSFER_ID),
                        valid.toBuilder()
                                .setPayloadSha256(ByteString.copyFrom(new byte[32]))
                                .build()
                                .toByteArray(),
                        3_000L));
        assertCode(
                "TRANSFER_PATH_MISMATCH",
                () -> handler.receive(
                        NODE_ID,
                        CanonicalSyncProtocol.activityChannelPath(SECOND_TRANSFER_ID),
                        valid.toByteArray(),
                        3_000L));

        assertTrue(repository.summaries(0, 10).isEmpty());
        assertTrue(sender.delivered.isEmpty());
        assertEquals(0, sender.attemptCount);
    }

    @Test
    public void storedAckSurvivesRepositoryAndDatabaseRestart() {
        database.close();
        Context context = ApplicationProvider.getApplicationContext();
        String databaseName = "phone-sync-process-death-" + System.nanoTime() + ".db";
        context.deleteDatabase(databaseName);
        byte[] transfer = transfer(TRANSFER_ID, activity(1L, 145D));
        byte[] firstAck;

        PhoneDatabase firstDatabase = Room.databaseBuilder(
                        context,
                        PhoneDatabase.class,
                        databaseName)
                .allowMainThreadQueries()
                .build();
        try {
            PhoneActivityRepository firstRepository =
                    new PhoneActivityRepository(firstDatabase);
            CanonicalActivityTransferHandler.Result accepted =
                    new CanonicalActivityTransferHandler(
                            firstRepository,
                            new RecordingSender(firstRepository, 1))
                            .receive(
                                    NODE_ID,
                                    CanonicalSyncProtocol.activityChannelPath(TRANSFER_ID),
                                    transfer,
                                    3_000L);
            assertFalse(accepted.isAcknowledgementDelivered());
            firstAck = accepted.getExactAcknowledgementBytes();
        } finally {
            firstDatabase.close();
        }

        PhoneDatabase restartedDatabase = Room.databaseBuilder(
                        context,
                        PhoneDatabase.class,
                        databaseName)
                .allowMainThreadQueries()
                .build();
        try {
            PhoneActivityRepository restartedRepository =
                    new PhoneActivityRepository(restartedDatabase);
            RecordingSender restartedSender =
                    new RecordingSender(restartedRepository, 0);
            CanonicalActivityTransferHandler.Result duplicate =
                    new CanonicalActivityTransferHandler(
                            restartedRepository,
                            restartedSender)
                            .receive(
                                    NODE_ID,
                                    CanonicalSyncProtocol.activityChannelPath(TRANSFER_ID),
                                    transfer,
                                    4_000L);
            assertFalse(duplicate.isRepositoryChanged());
            assertArrayEquals(firstAck, duplicate.getExactAcknowledgementBytes());
            assertArrayEquals(firstAck, restartedSender.delivered.get(0));
            assertEquals(1, restartedRepository.summaries(0, 10).size());
        } finally {
            restartedDatabase.close();
            context.deleteDatabase(databaseName);
        }

        database = Room.inMemoryDatabaseBuilder(context, PhoneDatabase.class)
                .allowMainThreadQueries()
                .build();
        repository = new PhoneActivityRepository(database);
    }

    private static byte[] transfer(String transferId, ActivityRecord activity) {
        return CanonicalTransfers.activityTransfer(
                        transferId,
                        CanonicalPayload.create(activity))
                .toByteArray();
    }

    private static ActivityRecord activity(long revision, double heartRate) {
        return ActivityRecord.newBuilder()
                .setSessionId(SESSION_ID)
                .setWatchRevision(revision)
                .setActivityType(ActivityType.ACTIVITY_TYPE_RUNNING)
                .setStartTimeUtcMs(1_000L)
                .setEndTimeUtcMs(2_000L)
                .setElapsedDurationMs(1_000L)
                .setActiveDurationMs(900L)
                .setSource(SourceMetadata.newBuilder()
                        .setKind(SourceKind.SOURCE_KIND_HEALTH_SERVICES)
                        .setProvider("androidx.health.services")
                        .setAppContractVersion(1))
                .addSamples(MeasurementSample.newBuilder()
                        .setSequence(1L)
                        .setTimestampUtcMs(1_500L)
                        .setHeartRateBpm(DoubleMeasurement.newBuilder()
                                .setValue(heartRate)
                                .setStatus(status())))
                .build();
    }

    private static MeasurementStatus status() {
        return MeasurementStatus.newBuilder()
                .setMetricIdentifier("HEART_RATE_BPM")
                .setPublicUnit("beats/minute")
                .setAggregation(AggregationSemantics.AGGREGATION_SEMANTICS_INSTANTANEOUS)
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

    private static final class RecordingSender
            implements CanonicalActivityTransferHandler.AcknowledgementSender {
        private final PhoneActivityRepository repository;
        private final List<byte[]> delivered = new ArrayList<>();
        private int failuresRemaining;
        private int attemptCount;
        private boolean persistedBeforeEverySend = true;

        private RecordingSender(
                PhoneActivityRepository repository,
                int failuresRemaining) {
            this.repository = repository;
            this.failuresRemaining = failuresRemaining;
        }

        @Override
        public void send(String destinationNodeId, byte[] exactAcknowledgementBytes)
                throws Exception {
            attemptCount++;
            persistedBeforeEverySend &= repository.detail(SESSION_ID, 1L) != null
                    || !repository.summaries(0, 10).isEmpty();
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw new IOException("simulated lost commit ACK");
            }
            assertEquals(NODE_ID, destinationNodeId);
            delivered.add(exactAcknowledgementBytes.clone());
        }
    }
}
