package com.long2know.sportlogger.sync;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.google.protobuf.ByteString;
import com.long2know.sportlogger.persistence.watch.WatchActivityRepository;
import com.long2know.sportlogger.persistence.watch.WatchDatabase;
import com.long2know.utilities.contract.CanonicalContractException;
import com.long2know.utilities.contract.CanonicalTransfers;
import com.long2know.utilities.contract.proto.AckResult;
import com.long2know.utilities.contract.proto.ActivityRecord;
import com.long2know.utilities.contract.proto.ActivityTransferFile;
import com.long2know.utilities.contract.proto.ActivityType;
import com.long2know.utilities.contract.proto.SourceKind;
import com.long2know.utilities.contract.proto.SourceMetadata;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class WatchSyncRunnerTest {
    private static final String NODE_ID = "phone-node";
    private static final String SESSION_ID = "d0a1b802-6eb7-41bd-bb31-8d1956625264";
    private static final String TRANSFER_ID = "982e6c4a-c162-413e-bae5-d63f29e5822a";
    private static final long LEASE = 71L;

    private WatchDatabase database;
    private WatchActivityRepository repository;
    private MutableNodes nodes;
    private RecordingSender sender;
    private RecordingEvents events;
    private AtomicLong clock;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        database = Room.inMemoryDatabaseBuilder(context, WatchDatabase.class)
                .allowMainThreadQueries()
                .build();
        repository = new WatchActivityRepository(database, Runnable::run);
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
    public void offlineRetryThenExactCommitAckMovesActualCanonicalStates() {
        WatchActivityRepository.OutboxItem ready = completedActivity();

        WatchSyncRunner.Result offline = runner().run(false);

        assertEquals(1, offline.getFailureCount());
        assertTrue(offline.isRetryRequired());
        assertEquals(
                WatchActivityRepository.OUTBOX_RETRYABLE,
                repository.outbox(TRANSFER_ID).join().getState());
        assertEquals(
                WatchSyncEvents.DisplayState.FAILED,
                WatchSyncEvents.displayState(
                        repository.outbox(TRANSFER_ID).join().getState()));

        nodes.nodeIds = Collections.singletonList(NODE_ID);
        clock.set(11_000L);
        WatchSyncRunner.Result connected = runner().run(false);

        assertEquals(1, connected.getSentCount());
        assertArrayEquals(ready.getPayload(), sender.activityPayloads.get(0));
        assertEquals(
                WatchActivityRepository.OUTBOX_AWAITING_COMMIT_ACK,
                repository.outbox(TRANSFER_ID).join().getState());
        assertEquals(
                WatchSyncEvents.DisplayState.TRANSFERRING,
                WatchSyncEvents.displayState(
                        repository.outbox(TRANSFER_ID).join().getState()));

        ActivityTransferFile transfer =
                CanonicalTransfers.parseActivityTransfer(ready.getPayload()).getTransfer();
        byte[] ack = CanonicalTransfers.commitAck(
                        transfer,
                        1L,
                        AckResult.ACK_RESULT_ACCEPTED,
                        null,
                        null)
                .toByteArray();
        WatchCommitAckHandler.Result accepted =
                ackHandler().receive(ack, 12_000L);

        assertTrue(accepted.isCommittedNow());
        assertEquals(
                WatchActivityRepository.OUTBOX_COMMITTED,
                repository.outbox(TRANSFER_ID).join().getState());
        assertEquals(
                WatchSyncEvents.DisplayState.SYNCED,
                WatchSyncEvents.displayState(
                        repository.outbox(TRANSFER_ID).join().getState()));
        assertTrue(repository.pendingOutbox(10).join().isEmpty());
        assertEquals(
                asList(
                        WatchActivityRepository.OUTBOX_RETRYABLE,
                        WatchActivityRepository.OUTBOX_SENDING,
                        WatchActivityRepository.OUTBOX_AWAITING_COMMIT_ACK,
                        WatchActivityRepository.OUTBOX_COMMITTED),
                events.activityStates);
    }

    @Test
    public void lostAckResendsSameImmutableFileAndDuplicateAckIsIdempotent() {
        WatchActivityRepository.OutboxItem ready = completedActivity();
        nodes.nodeIds = Collections.singletonList(NODE_ID);

        runner().run(false);
        clock.set(11_000L);
        runner().run(false);

        assertEquals(2, sender.activityPayloads.size());
        assertArrayEquals(sender.activityPayloads.get(0), sender.activityPayloads.get(1));
        assertEquals(2L, repository.outbox(TRANSFER_ID).join().getAttemptCount());

        ActivityTransferFile transfer =
                CanonicalTransfers.parseActivityTransfer(ready.getPayload()).getTransfer();
        byte[] ack = CanonicalTransfers.commitAck(
                        transfer,
                        1L,
                        AckResult.ACK_RESULT_DUPLICATE,
                        null,
                        null)
                .toByteArray();
        WatchCommitAckHandler.Result first =
                ackHandler().receive(ack, 12_000L);
        WatchCommitAckHandler.Result duplicate =
                ackHandler().receive(ack, 13_000L);

        assertTrue(first.isCommittedNow());
        assertFalse(duplicate.isCommittedNow());
        assertArrayEquals(
                ack,
                repository.outbox(TRANSFER_ID).join().getCommitAckPayload());
    }

    @Test
    public void typedStaleActivityAckTerminatesRetryAtNewerPhoneRevision() {
        WatchActivityRepository.OutboxItem ready = completedActivity();
        nodes.nodeIds = Collections.singletonList(NODE_ID);
        runner().run(false);
        ActivityTransferFile transfer =
                CanonicalTransfers.parseActivityTransfer(ready.getPayload()).getTransfer();
        byte[] staleAck = CanonicalTransfers.commitAck(
                        transfer,
                        2L,
                        AckResult.ACK_RESULT_STALE,
                        null,
                        null)
                .toByteArray();

        WatchCommitAckHandler.Result accepted =
                ackHandler().receive(staleAck, 12_000L);

        assertTrue(accepted.isCommittedNow());
        assertEquals(
                WatchActivityRepository.OUTBOX_COMMITTED,
                repository.outbox(TRANSFER_ID).join().getState());
        assertTrue(repository.pendingOutbox(10).join().isEmpty());
    }

    @Test
    public void processRestartReclaimsSendingBeforeRetryingWholeFile() {
        WatchActivityRepository.OutboxItem ready = completedActivity();
        repository.transitionOutbox(
                        TRANSFER_ID,
                        WatchActivityRepository.OUTBOX_READY,
                        WatchActivityRepository.OUTBOX_SENDING,
                        10_000L,
                        null,
                        null)
                .join();
        nodes.nodeIds = Collections.singletonList(NODE_ID);

        WatchSyncRunner.Result result = runner().run(true);

        assertEquals(1, result.getSentCount());
        assertArrayEquals(ready.getPayload(), sender.activityPayloads.get(0));
        assertEquals(
                asList(
                        WatchActivityRepository.OUTBOX_RETRYABLE,
                        WatchActivityRepository.OUTBOX_SENDING,
                        WatchActivityRepository.OUTBOX_AWAITING_COMMIT_ACK),
                events.activityStates);
        assertEquals(
                WatchActivityRepository.OUTBOX_AWAITING_COMMIT_ACK,
                repository.outbox(TRANSFER_ID).join().getState());
    }

    @Test
    public void sendingStateSurvivesDatabaseCloseAndProcessRestart() {
        database.close();
        Context context = ApplicationProvider.getApplicationContext();
        String databaseName = "watch-sync-process-death-" + System.nanoTime() + ".db";
        context.deleteDatabase(databaseName);
        WatchDatabase firstDatabase = Room.databaseBuilder(
                        context,
                        WatchDatabase.class,
                        databaseName)
                .allowMainThreadQueries()
                .build();
        try {
            WatchActivityRepository firstRepository =
                    new WatchActivityRepository(firstDatabase, Runnable::run);
            firstRepository.startSession(
                            WatchActivityRepository.StartRequest
                                    .builder(SESSION_ID, LEASE, 1_000L)
                                    .activityType(ActivityType.ACTIVITY_TYPE_RUNNING)
                                    .build())
                    .join();
            firstRepository.finishSession(
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
                                            .setKind(
                                                    SourceKind
                                                            .SOURCE_KIND_HEALTH_SERVICES)
                                            .setAppContractVersion(1))
                                    .build(),
                            TRANSFER_ID,
                            2_100L)
                    .join();
            firstRepository.transitionOutbox(
                            TRANSFER_ID,
                            WatchActivityRepository.OUTBOX_READY,
                            WatchActivityRepository.OUTBOX_SENDING,
                            2_200L,
                            null,
                            null)
                    .join();
        } finally {
            firstDatabase.close();
        }

        WatchDatabase restartedDatabase = Room.databaseBuilder(
                        context,
                        WatchDatabase.class,
                        databaseName)
                .allowMainThreadQueries()
                .build();
        try {
            WatchActivityRepository restartedRepository =
                    new WatchActivityRepository(restartedDatabase, Runnable::run);
            MutableNodes restartedNodes = new MutableNodes();
            restartedNodes.nodeIds = Collections.singletonList(NODE_ID);
            RecordingSender restartedSender = new RecordingSender();
            RecordingEvents restartedEvents = new RecordingEvents();
            WatchSyncRunner.Result result = new WatchSyncRunner(
                    restartedRepository,
                    restartedNodes,
                    restartedSender,
                    restartedEvents,
                    () -> 3_000L)
                    .run(true);

            assertEquals(1, result.getSentCount());
            assertEquals(1, restartedSender.activityPayloads.size());
            assertEquals(
                    WatchActivityRepository.OUTBOX_AWAITING_COMMIT_ACK,
                    restartedRepository.outbox(TRANSFER_ID).join().getState());
        } finally {
            restartedDatabase.close();
            context.deleteDatabase(databaseName);
        }

        database = Room.inMemoryDatabaseBuilder(context, WatchDatabase.class)
                .allowMainThreadQueries()
                .build();
        repository = new WatchActivityRepository(database, Runnable::run);
    }

    @Test
    public void mismatchedTransferSessionRevisionAndHashAcksNeverCommit() {
        WatchActivityRepository.OutboxItem ready = completedActivity();
        nodes.nodeIds = Collections.singletonList(NODE_ID);
        runner().run(false);
        ActivityTransferFile transfer =
                CanonicalTransfers.parseActivityTransfer(ready.getPayload()).getTransfer();

        assertCode(
                "OUTBOX_NOT_FOUND",
                () -> ackHandler().receive(
                        CanonicalTransfers.commitAck(
                                        transfer.toBuilder()
                                                .setTransferId(
                                                        "41af81ee-366b-4751-a78c-f11ec6a62456")
                                                .build(),
                                        1L,
                                        AckResult.ACK_RESULT_ACCEPTED,
                                        null,
                                        null)
                                .toByteArray(),
                        12_000L));
        assertCode(
                "ACK_IDENTITY_MISMATCH",
                () -> ackHandler().receive(
                        CanonicalTransfers.commitAck(
                                        transfer.toBuilder()
                                                .setSessionId(
                                                        "73acef01-ddc4-4d5f-82ee-a4607fb85687")
                                                .build(),
                                        1L,
                                        AckResult.ACK_RESULT_ACCEPTED,
                                        null,
                                        null)
                                .toByteArray(),
                        12_000L));
        assertCode(
                "ACK_IDENTITY_MISMATCH",
                () -> ackHandler().receive(
                        CanonicalTransfers.commitAck(
                                        transfer,
                                        0L,
                                        AckResult.ACK_RESULT_ACCEPTED,
                                        null,
                                        null)
                                .toByteArray(),
                        12_000L));
        assertCode(
                "ACK_IDENTITY_MISMATCH",
                () -> ackHandler().receive(
                        CanonicalTransfers.commitAck(
                                        transfer.toBuilder()
                                                .setPayloadSha256(
                                                        ByteString.copyFrom(new byte[32]))
                                                .build(),
                                        1L,
                                        AckResult.ACK_RESULT_ACCEPTED,
                                        null,
                                        null)
                                .toByteArray(),
                        12_000L));

        assertEquals(
                WatchActivityRepository.OUTBOX_AWAITING_COMMIT_ACK,
                repository.outbox(TRANSFER_ID).join().getState());
        assertTrue(repository.outbox(TRANSFER_ID).join().getCommitAckPayload() == null);
    }

    @Test
    public void tombstoneUsesWholeFileAndOnlyExactDeleteAckCommits() {
        String sessionId = "82d6aa47-1ebc-486e-9dfb-76f525f07c59";
        String transferId = "4168caad-6f40-4ef1-aec7-38d6c2500e2c";
        repository.startSession(
                        WatchActivityRepository.StartRequest
                                .builder(sessionId, 91L, 1_000L)
                                .activityType(ActivityType.ACTIVITY_TYPE_RUNNING)
                                .build())
                .join();
        repository.discardSession(
                        sessionId,
                        91L,
                        CanonicalTransfers.tombstone(
                                transferId,
                                sessionId,
                                1L,
                                0L,
                                TombstoneOrigin.TOMBSTONE_ORIGIN_WATCH,
                                2_000L),
                        2_000L)
                .join();
        WatchActivityRepository.TombstoneOutboxItem ready =
                repository.tombstoneOutbox(transferId).join();
        nodes.nodeIds = Collections.singletonList(NODE_ID);

        WatchSyncRunner.Result result = runner().run(false);

        assertEquals(1, result.getSentCount());
        assertArrayEquals(ready.getPayload(), sender.tombstonePayloads.get(0));
        assertEquals(
                WatchActivityRepository.OUTBOX_AWAITING_COMMIT_ACK,
                repository.tombstoneOutbox(transferId).join().getState());

        byte[] ack = com.long2know.utilities.contract.proto.CommitAck.newBuilder()
                .setSchemaVersion(CanonicalTransfers.SCHEMA_VERSION)
                .setTransferId(transferId)
                .setSessionId(sessionId)
                .setAcceptedSessionRevision(0L)
                .setPayloadSha256(ByteString.copyFrom(hexBytes(
                        ready.getPayloadSha256())))
                .setResult(AckResult.ACK_RESULT_ACCEPTED)
                .setDeleteRevision(1L)
                .setTombstoneOrigin(TombstoneOrigin.TOMBSTONE_ORIGIN_WATCH)
                .build()
                .toByteArray();

        WatchCommitAckHandler.Result accepted =
                ackHandler().receive(ack, 12_000L);

        assertTrue(accepted.isTombstone());
        assertTrue(accepted.isCommittedNow());
        assertEquals(
                WatchActivityRepository.OUTBOX_COMMITTED,
                repository.tombstoneOutbox(transferId).join().getState());
    }

    @Test
    public void typedStaleTombstoneAckTerminatesLosingWatchDelete() {
        String sessionId = "6f81251d-e619-438f-986d-e17ab0b1a284";
        String transferId = "2cd33895-afac-4718-8dcb-4b7b42abcc17";
        repository.startSession(
                        WatchActivityRepository.StartRequest
                                .builder(sessionId, 92L, 1_000L)
                                .activityType(ActivityType.ACTIVITY_TYPE_RUNNING)
                                .build())
                .join();
        repository.discardSession(
                        sessionId,
                        92L,
                        CanonicalTransfers.tombstone(
                                transferId,
                                sessionId,
                                1L,
                                0L,
                                TombstoneOrigin.TOMBSTONE_ORIGIN_WATCH,
                                2_000L),
                        2_000L)
                .join();
        WatchActivityRepository.TombstoneOutboxItem ready =
                repository.tombstoneOutbox(transferId).join();
        nodes.nodeIds = Collections.singletonList(NODE_ID);
        runner().run(false);
        byte[] staleAck = CanonicalTransfers.tombstoneCommitAck(
                        CanonicalTransfers.parseTombstone(ready.getPayload()),
                        AckResult.ACK_RESULT_STALE,
                        null,
                        null)
                .toByteArray();

        WatchCommitAckHandler.Result accepted =
                ackHandler().receive(staleAck, 12_000L);

        assertTrue(accepted.isCommittedNow());
        assertTrue(accepted.isTombstone());
        assertEquals(
                WatchActivityRepository.OUTBOX_COMMITTED,
                repository.tombstoneOutbox(transferId).join().getState());
        assertTrue(repository.pendingTombstoneOutbox(10).join().isEmpty());
    }

    @Test
    public void channelFailureLeavesRetryableCanonicalState() {
        completedActivity();
        nodes.nodeIds = Collections.singletonList(NODE_ID);
        sender.failuresRemaining = 1;

        WatchSyncRunner.Result result = runner().run(false);

        assertEquals(1, result.getFailureCount());
        assertTrue(result.isRetryRequired());
        assertEquals(
                WatchActivityRepository.OUTBOX_RETRYABLE,
                repository.outbox(TRANSFER_ID).join().getState());
        assertEquals(1L, repository.outbox(TRANSFER_ID).join().getAttemptCount());
    }

    private WatchActivityRepository.OutboxItem completedActivity() {
        repository.startSession(
                        WatchActivityRepository.StartRequest
                                .builder(SESSION_ID, LEASE, 1_000L)
                                .activityType(ActivityType.ACTIVITY_TYPE_RUNNING)
                                .build())
                .join();
        return repository.finishSession(
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
                        TRANSFER_ID,
                        2_100L)
                .join()
                .getOutbox();
    }

    private WatchSyncRunner runner() {
        return new WatchSyncRunner(
                repository,
                nodes,
                sender,
                events,
                clock::get);
    }

    private WatchCommitAckHandler ackHandler() {
        return new WatchCommitAckHandler(
                repository,
                new WatchCommitAckHandler.EventSink() {
                    @Override
                    public void activityCommitted(
                            WatchActivityRepository.OutboxItem item) {
                        events.activityChanged(item, null);
                    }

                    @Override
                    public void tombstoneCommitted(
                            WatchActivityRepository.TombstoneOutboxItem item) {
                        events.tombstoneChanged(item, null);
                    }
                });
    }

    private static void assertCode(String code, Runnable operation) {
        try {
            operation.run();
            fail("Expected " + code);
        } catch (RuntimeException error) {
            Throwable current = error;
            while (current instanceof CompletionException && current.getCause() != null) {
                current = current.getCause();
            }
            if (!(current instanceof CanonicalContractException)) {
                throw error;
            }
            assertEquals(code, ((CanonicalContractException) current).getCode());
        }
    }

    private static List<String> asList(String... values) {
        List<String> result = new ArrayList<>();
        Collections.addAll(result, values);
        return result;
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

    private static final class MutableNodes
            implements WatchSyncRunner.PhoneNodeSource {
        private List<String> nodeIds = Collections.emptyList();

        @Override
        public List<String> reachableNodeIds() {
            return nodeIds;
        }
    }

    private static final class RecordingSender
            implements WatchSyncRunner.TransferSender {
        private final List<byte[]> activityPayloads = new ArrayList<>();
        private final List<byte[]> tombstonePayloads = new ArrayList<>();
        private int failuresRemaining;

        @Override
        public void sendActivity(
                String nodeId,
                String transferId,
                byte[] exactTransferBytes) throws Exception {
            send(nodeId, exactTransferBytes, activityPayloads);
        }

        @Override
        public void sendTombstone(
                String nodeId,
                String transferId,
                byte[] exactTransferBytes) throws Exception {
            send(nodeId, exactTransferBytes, tombstonePayloads);
        }

        private void send(
                String nodeId,
                byte[] exactTransferBytes,
                List<byte[]> destination) throws IOException {
            assertEquals(NODE_ID, nodeId);
            destination.add(exactTransferBytes.clone());
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw new IOException("simulated disconnected channel");
            }
        }
    }

    private static final class RecordingEvents
            implements WatchSyncRunner.EventSink {
        private final List<String> activityStates = new ArrayList<>();
        private final List<String> tombstoneStates = new ArrayList<>();

        @Override
        public void activityChanged(
                WatchActivityRepository.OutboxItem item,
                String failureCode) {
            activityStates.add(item.getState());
        }

        @Override
        public void tombstoneChanged(
                WatchActivityRepository.TombstoneOutboxItem item,
                String failureCode) {
            tombstoneStates.add(item.getState());
        }
    }
}
