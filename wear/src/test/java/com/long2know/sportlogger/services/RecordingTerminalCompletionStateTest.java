package com.long2know.sportlogger.services;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RecordingTerminalCompletionStateTest {
    @Test
    public void committedStopSurvivesServiceRecreationUntilSuccessfulAck() {
        FakeStore store = new FakeStore();
        RecordingTerminalCompletionState first =
                new RecordingTerminalCompletionState(store);

        RecordingTerminalCompletionState.RecordResult recorded =
                first.recordSuccessfulStop(51, 7L);
        assertTrue(recorded.isPersisted());

        RecordingTerminalCompletionState replacement =
                new RecordingTerminalCompletionState(store);
        RecordingTerminalCompletion pending = replacement.pending();
        assertNotNull(pending);
        assertEquals(recorded.getCompletion().getOperationId(),
                pending.getOperationId());
        assertEquals(51, pending.getActivityId());
        assertEquals(7L, pending.getGeneration());

        assertEquals(
                RecordingTerminalCompletionState.AcknowledgeStatus.RETAINED,
                replacement.acknowledge(pending.getOperationId(), false));
        assertNotNull(new RecordingTerminalCompletionState(store).pending());

        assertEquals(
                RecordingTerminalCompletionState.AcknowledgeStatus.CLEARED,
                replacement.acknowledge(pending.getOperationId(), true));
        assertNull(new RecordingTerminalCompletionState(store).pending());
        assertEquals(
                RecordingTerminalCompletionState.AcknowledgeStatus
                        .ALREADY_CLEARED,
                replacement.acknowledge(pending.getOperationId(), true));
    }

    @Test
    public void failedAcknowledgmentPersistenceRemainsRetryable() {
        FakeStore store = new FakeStore();
        RecordingTerminalCompletionState state =
                new RecordingTerminalCompletionState(store);
        long operationId =
                state.recordSuccessfulStop(61, 9L)
                        .getCompletion()
                        .getOperationId();
        store.failClears = true;

        assertEquals(
                RecordingTerminalCompletionState.AcknowledgeStatus.RETAINED,
                state.acknowledge(operationId, true));
        assertNotNull(state.pending());
        assertNotNull(new RecordingTerminalCompletionState(store).pending());

        store.failClears = false;
        assertEquals(
                RecordingTerminalCompletionState.AcknowledgeStatus.CLEARED,
                state.acknowledge(operationId, true));
        assertNull(state.pending());
    }

    @Test
    public void duplicateStopRecordAndAckAreIdempotent() {
        FakeStore store = new FakeStore();
        RecordingTerminalCompletionState state =
                new RecordingTerminalCompletionState(store);

        RecordingTerminalCompletionState.RecordResult first =
                state.recordSuccessfulStop(71, 11L);
        RecordingTerminalCompletionState.RecordResult duplicate =
                state.recordSuccessfulStop(71, 11L);

        assertTrue(duplicate.isPersisted());
        assertEquals(
                first.getCompletion().getOperationId(),
                duplicate.getCompletion().getOperationId());
        assertTrue(state.recordSuccessfulStop(72, 12L).isOccupied());

        long operationId = first.getCompletion().getOperationId();
        assertEquals(
                RecordingTerminalCompletionState.AcknowledgeStatus.CLEARED,
                state.acknowledge(operationId, true));
        assertEquals(
                RecordingTerminalCompletionState.AcknowledgeStatus
                        .ALREADY_CLEARED,
                state.acknowledge(operationId, true));
    }

    @Test
    public void pendingStoppedActivityIsProtectedFromDiscard() {
        RecordingTerminalCompletionState state =
                new RecordingTerminalCompletionState(new FakeStore());
        state.recordSuccessfulStop(81, 13L);

        assertTrue(state.protectsActivity(81));
        assertFalse(state.protectsActivity(82));
    }

    @Test
    public void failedStopPersistenceDoesNotPublishTerminalSuccess() {
        FakeStore store = new FakeStore();
        store.failSaves = true;
        RecordingTerminalCompletionState state =
                new RecordingTerminalCompletionState(store);

        RecordingTerminalCompletionState.RecordResult result =
                state.recordSuccessfulStop(91, 15L);

        assertTrue(result.isAccepted());
        assertFalse(result.isPersisted());
        assertNull(state.pending());
        assertNull(new RecordingTerminalCompletionState(store).pending());
    }

    @Test
    public void acknowledgedOperationIdIsNotReused() {
        FakeStore store = new FakeStore();
        RecordingTerminalCompletionState state =
                new RecordingTerminalCompletionState(store);
        long firstOperationId =
                state.recordSuccessfulStop(101, 17L)
                        .getCompletion()
                        .getOperationId();
        state.acknowledge(firstOperationId, true);

        RecordingTerminalCompletionState replacement =
                new RecordingTerminalCompletionState(store);
        long secondOperationId =
                replacement.recordSuccessfulStop(102, 18L)
                        .getCompletion()
                        .getOperationId();

        assertTrue(secondOperationId > firstOperationId);
        assertEquals(
                RecordingTerminalCompletionState.AcknowledgeStatus.STALE,
                replacement.acknowledge(firstOperationId, true));
        assertNotNull(replacement.pending());
    }

    private static final class FakeStore
            implements RecordingTerminalCompletionState.Store {
        RecordingTerminalCompletionState.Snapshot snapshot =
                RecordingTerminalCompletionState.Snapshot.empty(0L);
        boolean failSaves;
        boolean failClears;

        @Override
        public RecordingTerminalCompletionState.Snapshot load() {
            return snapshot;
        }

        @Override
        public boolean save(
                RecordingTerminalCompletionState.Snapshot value) {
            if (failSaves) {
                return false;
            }
            snapshot = value;
            return true;
        }

        @Override
        public boolean clearPending(long expectedOperationId) {
            if (failClears) {
                return false;
            }
            RecordingTerminalCompletion pending = snapshot.getPending();
            if (pending == null) {
                return true;
            }
            if (pending.getOperationId() != expectedOperationId) {
                return false;
            }
            snapshot = RecordingTerminalCompletionState.Snapshot.empty(
                    snapshot.getLastOperationId());
            return true;
        }
    }
}
