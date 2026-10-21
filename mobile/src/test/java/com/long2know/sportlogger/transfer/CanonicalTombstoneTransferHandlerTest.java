package com.long2know.sportlogger.transfer;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository;
import com.long2know.sportlogger.persistence.phone.PhoneDatabase;
import com.long2know.utilities.contract.CanonicalContractException;
import com.long2know.utilities.contract.CanonicalPayload;
import com.long2know.utilities.contract.CanonicalTransfers;
import com.long2know.utilities.contract.proto.AckResult;
import com.long2know.utilities.contract.proto.ActivityRecord;
import com.long2know.utilities.contract.proto.ActivityType;
import com.long2know.utilities.contract.proto.SourceKind;
import com.long2know.utilities.contract.proto.SourceMetadata;
import com.long2know.utilities.contract.proto.TombstoneOrigin;
import com.long2know.utilities.contract.proto.TombstoneRecord;
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 26)
public class CanonicalTombstoneTransferHandlerTest {
    private static final String NODE_ID = "watch-node";
    private static final String SESSION_ID = "c6a5eac4-5569-4de6-9714-7885cf518ab6";
    private static final String ACTIVITY_TRANSFER_ID =
            "4d1c3081-fd19-41dd-8f9d-e4756fd69795";
    private static final String RESURRECTION_TRANSFER_ID =
            "3b361b39-56aa-407d-9389-bc46f620f215";
    private static final String TOMBSTONE_TRANSFER_ID =
            "59c7344b-8a41-4bea-acdb-a79430e14dc7";

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
    public void committedTombstoneSurvivesLostAckAndPreventsResurrection() {
        byte[] activity = activityTransfer(ACTIVITY_TRANSFER_ID, 1L);
        repository.acceptActivityTransfer(activity, 2_000L);
        assertEquals(1, repository.summaries(0, 10).size());
        TombstoneRecord tombstone = CanonicalTransfers.tombstone(
                TOMBSTONE_TRANSFER_ID,
                SESSION_ID,
                1L,
                1L,
                TombstoneOrigin.TOMBSTONE_ORIGIN_WATCH,
                3_000L);
        RecordingSender sender = new RecordingSender(repository, 1);
        CanonicalTombstoneTransferHandler handler =
                new CanonicalTombstoneTransferHandler(repository, sender);

        CanonicalTombstoneTransferHandler.Result first = handler.receive(
                NODE_ID,
                CanonicalSyncProtocol.tombstoneChannelPath(TOMBSTONE_TRANSFER_ID),
                tombstone.toByteArray(),
                3_100L);

        assertTrue(first.isRepositoryChanged());
        assertFalse(first.isAcknowledgementDelivered());
        assertEquals(AckResult.ACK_RESULT_ACCEPTED, first.getAcknowledgement().getResult());
        assertTrue(repository.summaries(0, 10).isEmpty());
        assertTrue(sender.deletedBeforeEverySend);

        CanonicalTombstoneTransferHandler.Result duplicate = handler.receive(
                NODE_ID,
                CanonicalSyncProtocol.tombstoneChannelPath(TOMBSTONE_TRANSFER_ID),
                tombstone.toByteArray(),
                3_200L);

        assertFalse(duplicate.isRepositoryChanged());
        assertTrue(duplicate.isAcknowledgementDelivered());
        assertArrayEquals(
                first.getExactAcknowledgementBytes(),
                duplicate.getExactAcknowledgementBytes());
        assertArrayEquals(
                first.getExactAcknowledgementBytes(),
                sender.delivered.get(0));

        CanonicalActivityTransferHandler.Result resurrection =
                new CanonicalActivityTransferHandler(
                        repository,
                        (nodeId, exactAck) -> {
                        })
                        .receive(
                                NODE_ID,
                                CanonicalSyncProtocol.activityChannelPath(
                                        RESURRECTION_TRANSFER_ID),
                                activityTransfer(RESURRECTION_TRANSFER_ID, 1L),
                                4_000L);
        assertEquals(
                AckResult.ACK_RESULT_TOMBSTONED,
                resurrection.getAcknowledgement().getResult());
        assertTrue(repository.summaries(0, 10).isEmpty());
    }

    @Test
    public void deterministicPhoneTombstoneWinsTieAndIncomingWatchDeleteIsStale() {
        repository.deleteLocal(
                SESSION_ID,
                2L,
                1L,
                "ff3618ef-136f-493c-bcc9-876279336d80",
                2_000L);
        TombstoneRecord watchTombstone = CanonicalTransfers.tombstone(
                TOMBSTONE_TRANSFER_ID,
                SESSION_ID,
                2L,
                1L,
                TombstoneOrigin.TOMBSTONE_ORIGIN_WATCH,
                2_100L);
        RecordingSender sender = new RecordingSender(repository, 0);

        CanonicalTombstoneTransferHandler.Result result =
                new CanonicalTombstoneTransferHandler(repository, sender)
                        .receive(
                                NODE_ID,
                                CanonicalSyncProtocol.tombstoneChannelPath(
                                        TOMBSTONE_TRANSFER_ID),
                                watchTombstone.toByteArray(),
                                2_200L);

        assertEquals(AckResult.ACK_RESULT_STALE, result.getAcknowledgement().getResult());
        assertTrue(result.isAcknowledgementDelivered());
        assertTrue(repository.summaries(0, 10).isEmpty());
    }

    @Test
    public void mismatchedPathCannotCreateReceiptOrSendAck() {
        TombstoneRecord tombstone = CanonicalTransfers.tombstone(
                TOMBSTONE_TRANSFER_ID,
                SESSION_ID,
                1L,
                0L,
                TombstoneOrigin.TOMBSTONE_ORIGIN_WATCH,
                3_000L);
        RecordingSender sender = new RecordingSender(repository, 0);
        CanonicalTombstoneTransferHandler handler =
                new CanonicalTombstoneTransferHandler(repository, sender);

        try {
            handler.receive(
                    NODE_ID,
                    CanonicalSyncProtocol.tombstoneChannelPath(
                            "3cbc347e-48aa-4d9b-83d6-fd36ea015aa2"),
                    tombstone.toByteArray(),
                    3_100L);
            fail("Expected path mismatch");
        } catch (CanonicalContractException expected) {
            assertEquals("TRANSFER_PATH_MISMATCH", expected.getCode());
        }
        assertEquals(0, sender.attemptCount);

        CanonicalTombstoneTransferHandler.Result accepted = handler.receive(
                NODE_ID,
                CanonicalSyncProtocol.tombstoneChannelPath(TOMBSTONE_TRANSFER_ID),
                tombstone.toByteArray(),
                3_200L);
        assertTrue(accepted.isRepositoryChanged());
    }

    private static byte[] activityTransfer(String transferId, long revision) {
        ActivityRecord activity = ActivityRecord.newBuilder()
                .setSessionId(SESSION_ID)
                .setWatchRevision(revision)
                .setActivityType(ActivityType.ACTIVITY_TYPE_RUNNING)
                .setStartTimeUtcMs(1_000L)
                .setEndTimeUtcMs(2_000L)
                .setElapsedDurationMs(1_000L)
                .setSource(SourceMetadata.newBuilder()
                        .setKind(SourceKind.SOURCE_KIND_HEALTH_SERVICES)
                        .setAppContractVersion(1))
                .build();
        return CanonicalTransfers.activityTransfer(
                        transferId,
                        CanonicalPayload.create(activity))
                .toByteArray();
    }

    private static final class RecordingSender
            implements CanonicalActivityTransferHandler.AcknowledgementSender {
        private final PhoneActivityRepository repository;
        private final List<byte[]> delivered = new ArrayList<>();
        private int failuresRemaining;
        private int attemptCount;
        private boolean deletedBeforeEverySend = true;

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
            deletedBeforeEverySend &= repository.summaries(0, 10).isEmpty();
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw new IOException("simulated lost tombstone ACK");
            }
            assertEquals(NODE_ID, destinationNodeId);
            delivered.add(exactAcknowledgementBytes.clone());
        }
    }
}
