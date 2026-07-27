package com.long2know.sportlogger.services;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RecordingTerminalizationTest {
    @Test
    public void terminalIntentPersistenceFailurePreventsEndMutation() {
        FakeRecoveryStore recoveryStore = new FakeRecoveryStore();
        RecordingRecoveryState recovery =
                createPausedRecovery(recoveryStore, 41, 7L);
        recoveryStore.failSaves = true;
        FakeTerminalStore terminalStore = new FakeTerminalStore();
        FakeActivities activities = new FakeActivities();

        RecordingTerminalization.Result result =
                RecordingTerminalization.finish(
                        recovery,
                        new RecordingTerminalCompletionState(terminalStore),
                        activities,
                        41,
                        7L);

        assertEquals(
                RecordingTerminalization.Status.INTENT_PERSISTENCE_FAILED,
                result.getStatus());
        assertEquals(0, activities.completeCalls);
        assertEquals(
                RecordingRecoveryState.Phase.STOPPING,
                recovery.snapshot().getPhase());
        assertEquals(
                RecordingRecoveryState.Phase.PAUSED,
                recoveryStore.snapshot.getPhase());
        assertNull(terminalStore.snapshot.getPending());
    }

    @Test
    public void processDeathAfterIntentBeforeEndRetriesIdempotently() {
        FakeRecoveryStore recoveryStore = new FakeRecoveryStore();
        RecordingRecoveryState first =
                createPausedRecovery(recoveryStore, 51, 9L);
        assertTrue(first.recordStopping(51, 9L).isPersisted());
        FakeTerminalStore terminalStore = new FakeTerminalStore();
        FakeActivities activities = new FakeActivities();

        RecordingRecoveryState replacement =
                new RecordingRecoveryState(recoveryStore);
        RecordingTerminalization.Result result =
                RecordingTerminalization.finish(
                        replacement,
                        new RecordingTerminalCompletionState(terminalStore),
                        activities,
                        51,
                        9L);

        assertTrue(result.isCommitted());
        assertEquals(1, activities.completeCalls);
        assertEquals(
                RecordingTerminalization.ActivityStore.State.COMPLETED,
                activities.state);
        assertNotNull(terminalStore.snapshot.getPending());
    }

    @Test
    public void handoffFailureAfterEndKeepsDurableStoppingForRetry() {
        FakeRecoveryStore recoveryStore = new FakeRecoveryStore();
        RecordingRecoveryState recovery =
                createPausedRecovery(recoveryStore, 61, 11L);
        FakeTerminalStore terminalStore = new FakeTerminalStore();
        terminalStore.failSaves = true;
        FakeActivities activities = new FakeActivities();

        RecordingTerminalization.Result failed =
                RecordingTerminalization.finish(
                        recovery,
                        new RecordingTerminalCompletionState(terminalStore),
                        activities,
                        61,
                        11L);

        assertEquals(
                RecordingTerminalization.Status.HANDOFF_PERSISTENCE_FAILED,
                failed.getStatus());
        assertEquals(
                RecordingTerminalization.ActivityStore.State.COMPLETED,
                activities.state);
        assertEquals(
                RecordingRecoveryState.Phase.STOPPING,
                recoveryStore.snapshot.getPhase());
        assertNull(terminalStore.snapshot.getPending());

        terminalStore.failSaves = false;
        RecordingTerminalization.Result retried =
                RecordingTerminalization.finish(
                        new RecordingRecoveryState(recoveryStore),
                        new RecordingTerminalCompletionState(terminalStore),
                        activities,
                        61,
                        11L);

        assertTrue(retried.isCommitted());
        assertEquals(1, activities.completeCalls);
        assertNotNull(terminalStore.snapshot.getPending());
    }

    @Test
    public void processDeathAfterEndBeforeHandoffCompletesWithoutNewEnd() {
        FakeRecoveryStore recoveryStore = new FakeRecoveryStore();
        RecordingRecoveryState first =
                createPausedRecovery(recoveryStore, 71, 13L);
        assertTrue(first.recordStopping(71, 13L).isPersisted());
        FakeActivities activities = new FakeActivities();
        activities.state =
                RecordingTerminalization.ActivityStore.State.COMPLETED;
        FakeTerminalStore terminalStore = new FakeTerminalStore();

        RecordingTerminalization.Result result =
                RecordingTerminalization.finish(
                        new RecordingRecoveryState(recoveryStore),
                        new RecordingTerminalCompletionState(terminalStore),
                        activities,
                        71,
                        13L);

        assertTrue(result.isCommitted());
        assertEquals(0, activities.completeCalls);
        assertNotNull(terminalStore.snapshot.getPending());
    }

    @Test
    public void preExistingEndPromotesPausedRecoveryToStopping() {
        FakeRecoveryStore recoveryStore = new FakeRecoveryStore();
        RecordingRecoveryState recovery =
                createPausedRecovery(recoveryStore, 81, 15L);
        FakeActivities activities = new FakeActivities();
        activities.state =
                RecordingTerminalization.ActivityStore.State.COMPLETED;
        FakeTerminalStore terminalStore = new FakeTerminalStore();

        RecordingTerminalization.Result result =
                RecordingTerminalization.finish(
                        recovery,
                        new RecordingTerminalCompletionState(terminalStore),
                        activities,
                        81,
                        15L);

        assertTrue(result.isCommitted());
        assertEquals(
                RecordingRecoveryState.Phase.STOPPING,
                recoveryStore.snapshot.getPhase());
        assertFalse(recovery.clearAfterDiscard(81).isAccepted());
    }

    private static RecordingRecoveryState createPausedRecovery(
            FakeRecoveryStore store, int activityId, long generation) {
        RecordingRecoveryState recovery = new RecordingRecoveryState(store);
        assertTrue(recovery.reserveWriterGeneration(
                activityId, generation).isPersisted());
        assertTrue(recovery.recordRecording(
                activityId, generation).isPersisted());
        assertTrue(recovery.recordPaused(
                activityId, generation).isPersisted());
        return recovery;
    }

    private static final class FakeActivities
            implements RecordingTerminalization.ActivityStore {
        State state = State.OPEN;
        int completeCalls;

        @Override
        public State getState(int activityId) {
            return state;
        }

        @Override
        public Completion complete(int activityId) {
            completeCalls++;
            if (state == State.MISSING) {
                return Completion.MISSING;
            }
            if (state == State.COMPLETED) {
                return Completion.ALREADY_COMPLETED;
            }
            state = State.COMPLETED;
            return Completion.COMPLETED;
        }
    }

    private static final class FakeRecoveryStore
            implements RecordingRecoveryState.Store {
        RecordingRecoveryState.Snapshot snapshot =
                RecordingRecoveryState.Snapshot.idle();
        boolean failSaves;

        @Override
        public RecordingRecoveryState.Snapshot load() {
            return snapshot;
        }

        @Override
        public boolean save(RecordingRecoveryState.Snapshot value) {
            if (failSaves) {
                return false;
            }
            snapshot = value;
            return true;
        }

        @Override
        public boolean clear() {
            snapshot = RecordingRecoveryState.Snapshot.idle();
            return true;
        }
    }

    private static final class FakeTerminalStore
            implements RecordingTerminalCompletionState.Store {
        RecordingTerminalCompletionState.Snapshot snapshot =
                RecordingTerminalCompletionState.Snapshot.empty(0L);
        boolean failSaves;

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
            snapshot = RecordingTerminalCompletionState.Snapshot.empty(
                    snapshot.getLastOperationId());
            return true;
        }
    }
}
