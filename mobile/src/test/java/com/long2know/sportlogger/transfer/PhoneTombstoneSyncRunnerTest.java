package com.long2know.sportlogger.transfer;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.google.protobuf.ByteString;
import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository;
import com.long2know.sportlogger.persistence.phone.PhoneDatabase;
import com.long2know.utilities.contract.CanonicalContractException;
import com.long2know.utilities.contract.CanonicalTransfers;
import com.long2know.utilities.contract.proto.AckResult;
import com.long2know.utilities.contract.proto.CommitAck;
import com.long2know.utilities.contract.proto.TombstoneOrigin;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 26)
public class PhoneTombstoneSyncRunnerTest {
    private static final String NODE_ID = "watch-node";
    private static final String SESSION_ID = "f37dca54-d36f-4de2-b62e-b36e8d3f32b2";
    private static final String TRANSFER_ID = "f1edb914-e5c0-4f9e-894c-bd2e5ec939cd";

    private PhoneDatabase database;
    private PhoneActivityRepository repository;
    private MutableNodes nodes;
    private RecordingSender sender;
    private RecordingEvents events;
    private AtomicLong clock;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        database = Room.inMemoryDatabaseBuilder(context, PhoneDatabase.class)
                .allowMainThreadQueries()
                .build();
        repository = new PhoneActivityRepository(database);
        nodes = new MutableNodes();
        sender = new RecordingSender();
        events = new RecordingEvents();
        clock = new AtomicLong(10_000L);
    }

    @After
    public void tearDown() {
        database.close();
    }

    @Test
    public void offlineRetryAndExactAckDriveCanonicalPhoneDeleteStates() {
        PhoneActivityRepository.TombstoneOutboxItem ready = deletedActivity();

        PhoneTombstoneSyncRunner.Result offline = runner().run(false);

        assertEquals(1, offline.getFailureCount());
        assertEquals(
                PhoneActivityRepository.TOMBSTONE_RETRYABLE,
                repository.tombstoneOutbox(TRANSFER_ID).getState());
        assertEquals(
                PhoneSyncEvents.DisplayState.FAILED,
                PhoneSyncEvents.displayState(
                        repository.tombstoneOutbox(TRANSFER_ID).getState()));

        nodes.nodeIds = Collections.singletonList(NODE_ID);
        clock.set(11_000L);
        PhoneTombstoneSyncRunner.Result connected = runner().run(false);

        assertEquals(1, connected.getSentCount());
        assertArrayEquals(ready.getPayload(), sender.payloads.get(0));
        assertEquals(
                PhoneActivityRepository.TOMBSTONE_AWAITING_COMMIT_ACK,
                repository.tombstoneOutbox(TRANSFER_ID).getState());
        assertEquals(
                PhoneSyncEvents.DisplayState.TRANSFERRING,
                PhoneSyncEvents.displayState(
                        repository.tombstoneOutbox(TRANSFER_ID).getState()));

        byte[] acknowledgement = CanonicalTransfers.tombstoneCommitAck(
                        CanonicalTransfers.parseTombstone(ready.getPayload()),
                        AckResult.ACK_RESULT_ACCEPTED,
                        null,
                        null)
                .toByteArray();
        PhoneTombstoneCommitAckHandler.Result accepted =
                ackHandler().receive(acknowledgement, 12_000L);

        assertTrue(accepted.isCommittedNow());
        assertEquals(
                PhoneActivityRepository.TOMBSTONE_COMMITTED,
                repository.tombstoneOutbox(TRANSFER_ID).getState());
        assertEquals(
                PhoneSyncEvents.DisplayState.SYNCED,
                PhoneSyncEvents.displayState(
                        repository.tombstoneOutbox(TRANSFER_ID).getState()));
        assertEquals(
                asList(
                        PhoneActivityRepository.TOMBSTONE_RETRYABLE,
                        PhoneActivityRepository.TOMBSTONE_SENDING,
                        PhoneActivityRepository.TOMBSTONE_AWAITING_COMMIT_ACK,
                        PhoneActivityRepository.TOMBSTONE_COMMITTED),
                events.states);
    }

    @Test
    public void lostAckResendsSameTombstoneAndDuplicateAckIsIdempotent() {
        PhoneActivityRepository.TombstoneOutboxItem ready = deletedActivity();
        nodes.nodeIds = Collections.singletonList(NODE_ID);

        runner().run(false);
        clock.set(11_000L);
        runner().run(false);

        assertEquals(2, sender.payloads.size());
        assertArrayEquals(sender.payloads.get(0), sender.payloads.get(1));
        assertEquals(2L, repository.tombstoneOutbox(TRANSFER_ID).getAttemptCount());

        byte[] acknowledgement = CanonicalTransfers.tombstoneCommitAck(
                        CanonicalTransfers.parseTombstone(ready.getPayload()),
                        AckResult.ACK_RESULT_DUPLICATE,
                        null,
                        null)
                .toByteArray();
        PhoneTombstoneCommitAckHandler.Result first =
                ackHandler().receive(acknowledgement, 12_000L);
        PhoneTombstoneCommitAckHandler.Result duplicate =
                ackHandler().receive(acknowledgement, 13_000L);

        assertTrue(first.isCommittedNow());
        assertFalse(duplicate.isCommittedNow());
        assertArrayEquals(
                acknowledgement,
                repository.tombstoneOutbox(TRANSFER_ID).getCommitAckPayload());
    }

    @Test
    public void typedStaleTombstoneAckTerminatesLosingPhoneDelete() {
        PhoneActivityRepository.TombstoneOutboxItem ready = deletedActivity();
        nodes.nodeIds = Collections.singletonList(NODE_ID);
        runner().run(false);
        byte[] staleAck = CanonicalTransfers.tombstoneCommitAck(
                        CanonicalTransfers.parseTombstone(ready.getPayload()),
                        AckResult.ACK_RESULT_STALE,
                        null,
                        null)
                .toByteArray();

        PhoneTombstoneCommitAckHandler.Result accepted =
                ackHandler().receive(staleAck, 12_000L);

        assertTrue(accepted.isCommittedNow());
        assertEquals(
                PhoneActivityRepository.TOMBSTONE_COMMITTED,
                repository.tombstoneOutbox(TRANSFER_ID).getState());
        assertTrue(repository.pendingTombstoneOutbox(10).isEmpty());
    }

    @Test
    public void processRestartReclaimsSendingAndRetriesWholeFile() {
        PhoneActivityRepository.TombstoneOutboxItem ready = deletedActivity();
        repository.transitionTombstoneOutbox(
                TRANSFER_ID,
                PhoneActivityRepository.TOMBSTONE_READY,
                PhoneActivityRepository.TOMBSTONE_SENDING,
                10_000L,
                null,
                null);
        nodes.nodeIds = Collections.singletonList(NODE_ID);

        PhoneTombstoneSyncRunner.Result result = runner().run(true);

        assertEquals(1, result.getSentCount());
        assertArrayEquals(ready.getPayload(), sender.payloads.get(0));
        assertEquals(
                PhoneActivityRepository.TOMBSTONE_AWAITING_COMMIT_ACK,
                repository.tombstoneOutbox(TRANSFER_ID).getState());
    }

    @Test
    public void mismatchedTransferSessionDeleteAndHashAcksNeverCommit() {
        PhoneActivityRepository.TombstoneOutboxItem ready = deletedActivity();
        nodes.nodeIds = Collections.singletonList(NODE_ID);
        runner().run(false);
        CommitAck valid = CanonicalTransfers.tombstoneCommitAck(
                CanonicalTransfers.parseTombstone(ready.getPayload()),
                AckResult.ACK_RESULT_ACCEPTED,
                null,
                null);

        assertCode(
                "TOMBSTONE_OUTBOX_NOT_FOUND",
                () -> ackHandler().receive(
                        valid.toBuilder()
                                .setTransferId(
                                        "5111388b-674e-4dcb-89ae-e8be6a29205a")
                                .build()
                                .toByteArray(),
                        12_000L));
        assertCode(
                "ACK_IDENTITY_MISMATCH",
                () -> ackHandler().receive(
                        valid.toBuilder()
                                .setSessionId(
                                        "3df4c4be-e987-4711-b938-af2ef666a8d6")
                                .build()
                                .toByteArray(),
                        12_000L));
        assertCode(
                "ACK_IDENTITY_MISMATCH",
                () -> ackHandler().receive(
                        valid.toBuilder()
                                .setDeleteRevision(2L)
                                .build()
                                .toByteArray(),
                        12_000L));
        assertCode(
                "ACK_IDENTITY_MISMATCH",
                () -> ackHandler().receive(
                        valid.toBuilder()
                                .setPayloadSha256(ByteString.copyFrom(new byte[32]))
                                .build()
                                .toByteArray(),
                        12_000L));

        assertEquals(
                PhoneActivityRepository.TOMBSTONE_AWAITING_COMMIT_ACK,
                repository.tombstoneOutbox(TRANSFER_ID).getState());
    }

    @Test
    public void channelFailureLeavesRetryableCanonicalState() {
        deletedActivity();
        nodes.nodeIds = Collections.singletonList(NODE_ID);
        sender.failuresRemaining = 1;

        PhoneTombstoneSyncRunner.Result result = runner().run(false);

        assertEquals(1, result.getFailureCount());
        assertTrue(result.isRetryRequired());
        assertEquals(
                PhoneActivityRepository.TOMBSTONE_RETRYABLE,
                repository.tombstoneOutbox(TRANSFER_ID).getState());
        assertEquals(1L, repository.tombstoneOutbox(TRANSFER_ID).getAttemptCount());
    }

    private PhoneActivityRepository.TombstoneOutboxItem deletedActivity() {
        repository.deleteLocal(
                SESSION_ID,
                1L,
                1L,
                TRANSFER_ID,
                2_000L);
        return repository.tombstoneOutbox(TRANSFER_ID);
    }

    private PhoneTombstoneSyncRunner runner() {
        return new PhoneTombstoneSyncRunner(
                repository,
                nodes,
                sender,
                events,
                clock::get);
    }

    private PhoneTombstoneCommitAckHandler ackHandler() {
        return new PhoneTombstoneCommitAckHandler(
                repository,
                item -> events.changed(item, null));
    }

    private static void assertCode(String code, Runnable operation) {
        try {
            operation.run();
            fail("Expected " + code);
        } catch (CanonicalContractException expected) {
            assertEquals(code, expected.getCode());
        }
    }

    private static List<String> asList(String... values) {
        List<String> result = new ArrayList<>();
        Collections.addAll(result, values);
        return result;
    }

    private static final class MutableNodes
            implements PhoneTombstoneSyncRunner.WatchNodeSource {
        private List<String> nodeIds = Collections.emptyList();

        @Override
        public List<String> reachableNodeIds() {
            return nodeIds;
        }
    }

    private static final class RecordingSender
            implements PhoneTombstoneSyncRunner.TransferSender {
        private final List<byte[]> payloads = new ArrayList<>();
        private int failuresRemaining;

        @Override
        public void send(
                String nodeId,
                String transferId,
                byte[] exactTransferBytes) throws Exception {
            assertEquals(NODE_ID, nodeId);
            assertEquals(TRANSFER_ID, transferId);
            payloads.add(exactTransferBytes.clone());
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw new IOException("simulated disconnected channel");
            }
        }
    }

    private static final class RecordingEvents
            implements PhoneTombstoneSyncRunner.EventSink {
        private final List<String> states = new ArrayList<>();

        @Override
        public void changed(
                PhoneActivityRepository.TombstoneOutboxItem item,
                String failureCode) {
            states.add(item.getState());
        }
    }
}
