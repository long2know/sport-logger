package com.long2know.sportlogger.sync;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.long2know.sportlogger.persistence.watch.WatchActivityRepository;
import com.long2know.sportlogger.persistence.watch.WatchDatabase;
import com.long2know.utilities.contract.CanonicalContractException;
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
public class CanonicalWatchTombstoneTransferHandlerTest {
    private static final String NODE_ID = "phone-node";
    private static final String SESSION_ID = "a12f260c-a2d0-43bb-a57c-ddb8b4275461";
    private static final String ACTIVITY_TRANSFER_ID =
            "99b5a89d-a00a-4cd4-aa03-3badf430e5ce";
    private static final String TOMBSTONE_TRANSFER_ID =
            "2ee28199-6f76-4281-a967-1cecd5a0f82f";
    private static final long LEASE = 57L;

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
    public void phoneDeleteCommitsBeforeAckAndSuppressesCoveredWatchOutbox() {
        repository.startSession(
                        WatchActivityRepository.StartRequest
                                .builder(SESSION_ID, LEASE, 1_000L)
                                .activityType(ActivityType.ACTIVITY_TYPE_RUNNING)
                                .build())
                .join();
        repository.finishSession(
                        SESSION_ID,
                        LEASE,
                        ActivityRecord.newBuilder()
                                .setSessionId(SESSION_ID)
                                .setWatchRevision(1L)
                                .setActivityType(ActivityType.ACTIVITY_TYPE_RUNNING)
                                .setStartTimeUtcMs(1_000L)
                                .setEndTimeUtcMs(2_000L)
                                .setElapsedDurationMs(1_000L)
                                .setSource(SourceMetadata.newBuilder()
                                        .setKind(SourceKind.SOURCE_KIND_HEALTH_SERVICES)
                                        .setAppContractVersion(1))
                                .build(),
                        ACTIVITY_TRANSFER_ID,
                        2_100L)
                .join();
        TombstoneRecord tombstone = CanonicalTransfers.tombstone(
                TOMBSTONE_TRANSFER_ID,
                SESSION_ID,
                1L,
                1L,
                TombstoneOrigin.TOMBSTONE_ORIGIN_PHONE,
                3_000L);
        RecordingSender sender = new RecordingSender(repository, 1);
        CanonicalWatchTombstoneTransferHandler handler =
                new CanonicalWatchTombstoneTransferHandler(repository, sender);

        CanonicalWatchTombstoneTransferHandler.Result first = handler.receive(
                NODE_ID,
                CanonicalSyncProtocol.tombstoneChannelPath(TOMBSTONE_TRANSFER_ID),
                tombstone.toByteArray(),
                3_100L);

        assertTrue(first.isRepositoryChanged());
        assertFalse(first.isAcknowledgementDelivered());
        assertEquals(AckResult.ACK_RESULT_ACCEPTED, first.getAcknowledgement().getResult());
        assertEquals(
                WatchActivityRepository.LifecycleState.DISCARDED,
                repository.session(SESSION_ID).join().getState());
        assertEquals(
                "TOMBSTONED",
                repository.outbox(ACTIVITY_TRANSFER_ID).join().getState());
        assertEquals(
                WatchSyncEvents.DisplayState.SYNCED,
                WatchSyncEvents.displayState("TOMBSTONED"));
        assertTrue(repository.pendingOutbox(10).join().isEmpty());
        assertTrue(sender.suppressedBeforeEverySend);

        CanonicalWatchTombstoneTransferHandler.Result duplicate = handler.receive(
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
    }

    @Test
    public void mismatchedPathCannotApplyPhoneDeleteOrSendAck() {
        TombstoneRecord tombstone = CanonicalTransfers.tombstone(
                TOMBSTONE_TRANSFER_ID,
                SESSION_ID,
                1L,
                0L,
                TombstoneOrigin.TOMBSTONE_ORIGIN_PHONE,
                3_000L);
        RecordingSender sender = new RecordingSender(repository, 0);
        CanonicalWatchTombstoneTransferHandler handler =
                new CanonicalWatchTombstoneTransferHandler(repository, sender);

        try {
            handler.receive(
                    NODE_ID,
                    CanonicalSyncProtocol.tombstoneChannelPath(
                            "90d4468b-cfeb-4b6b-94cf-32a25c573895"),
                    tombstone.toByteArray(),
                    3_100L);
            fail("Expected path mismatch");
        } catch (CanonicalContractException expected) {
            assertEquals("TRANSFER_PATH_MISMATCH", expected.getCode());
        }
        assertEquals(0, sender.attemptCount);

        CanonicalWatchTombstoneTransferHandler.Result accepted = handler.receive(
                NODE_ID,
                CanonicalSyncProtocol.tombstoneChannelPath(TOMBSTONE_TRANSFER_ID),
                tombstone.toByteArray(),
                3_200L);
        assertTrue(accepted.isRepositoryChanged());
    }

    private static final class RecordingSender
            implements CanonicalWatchTombstoneTransferHandler.AcknowledgementSender {
        private final WatchActivityRepository repository;
        private final List<byte[]> delivered = new ArrayList<>();
        private int failuresRemaining;
        private int attemptCount;
        private boolean suppressedBeforeEverySend = true;

        private RecordingSender(
                WatchActivityRepository repository,
                int failuresRemaining) {
            this.repository = repository;
            this.failuresRemaining = failuresRemaining;
        }

        @Override
        public void send(String destinationNodeId, byte[] exactAcknowledgementBytes)
                throws Exception {
            attemptCount++;
            WatchActivityRepository.OutboxItem outbox =
                    repository.outbox(ACTIVITY_TRANSFER_ID).join();
            suppressedBeforeEverySend &= outbox == null
                    || "TOMBSTONED".equals(outbox.getState());
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw new IOException("simulated lost phone tombstone ACK");
            }
            assertEquals(NODE_ID, destinationNodeId);
            delivered.add(exactAcknowledgementBytes.clone());
        }
    }
}
