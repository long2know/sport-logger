package com.long2know.sportlogger.services;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecordingRecoveryStateTest {
    @Test
    public void preRecordingStartupFailureRemainsIdle() {
        FakeStore store = new FakeStore();
        RecordingRecoveryState recovery = new RecordingRecoveryState(store);

        assertTrue(recovery.clearAfterPreRecordingFailure());
        assertEquals(
                RecordingRecoveryState.Phase.IDLE,
                recovery.snapshot().getPhase());
        assertEquals(0, recovery.snapshot().getActivityId());
    }

    @Test
    public void ownedAsyncFailurePreservesExactActivityAndGeneration() {
        FakeStore store = new FakeStore();
        RecordingRecoveryState recovery = createRecording(store, 42, 7L);

        assertTrue(recovery.requireRecovery(42, 7L));
        assertFalse(recovery.requireRecovery(42, 6L));
        assertFalse(recovery.requireRecovery(41, 7L));

        RecordingRecoveryState.Snapshot retained = recovery.snapshot();
        assertEquals(
                RecordingRecoveryState.Phase.RECOVERY_REQUIRED,
                retained.getPhase());
        assertEquals(42, retained.getActivityId());
        assertEquals(7L, retained.getGeneration());
    }

    @Test
    public void replacementReconstructsOwnedRecordingBeforeControlsAreShown() {
        FakeStore store = new FakeStore();
        createRecording(store, 51, 12L);

        RecordingRecoveryState replacement = new RecordingRecoveryState(store);
        assertTrue(replacement.prepareForServiceReplacement(true));
        assertEquals(
                RecordingRecoveryState.Phase.RECOVERY_REQUIRED,
                replacement.snapshot().getPhase());
        assertEquals(51, replacement.snapshot().getActivityId());
        assertEquals(12L, replacement.snapshot().getGeneration());

        assertTrue(replacement.recordPaused(51, 12L));
        assertEquals(
                RecordingRecoveryState.Phase.PAUSED,
                replacement.snapshot().getPhase());
    }

    @Test
    public void replacementDoesNotInventOwnershipForMissingDatabaseRow() {
        FakeStore store = new FakeStore();
        createRecording(store, 61, 2L);

        RecordingRecoveryState replacement = new RecordingRecoveryState(store);
        assertTrue(replacement.prepareForServiceReplacement(false));
        assertEquals(
                RecordingRecoveryState.Phase.IDLE,
                replacement.snapshot().getPhase());
        assertEquals(0, replacement.snapshot().getActivityId());
    }

    @Test
    public void resumeAfterReplacementKeepsActivityAndAdvancesGeneration() {
        FakeStore store = new FakeStore();
        createRecording(store, 71, 4L);
        RecordingRecoveryState replacement = new RecordingRecoveryState(store);
        replacement.prepareForServiceReplacement(true);
        replacement.recordPaused(71, 4L);

        assertTrue(replacement.recordRecording(71, 5L));
        assertFalse(replacement.recordRecording(71, 5L));
        assertEquals(
                RecordingRecoveryState.Phase.RECORDING,
                replacement.snapshot().getPhase());
        assertEquals(71, replacement.snapshot().getActivityId());
        assertEquals(5L, replacement.snapshot().getGeneration());
    }

    @Test
    public void stopAfterReplacementClearsOnlyTheExactOwnedActivity() {
        FakeStore store = new FakeStore();
        createRecording(store, 81, 9L);
        RecordingRecoveryState replacement = new RecordingRecoveryState(store);
        replacement.prepareForServiceReplacement(true);
        replacement.recordPaused(81, 9L);

        assertFalse(replacement.clearAfterStop(80));
        assertTrue(replacement.clearAfterStop(81));
        assertEquals(
                RecordingRecoveryState.Phase.IDLE,
                replacement.snapshot().getPhase());
    }

    @Test
    public void discardAfterReplacementClearsOnlyAfterDataDeletion() {
        FakeStore store = new FakeStore();
        createRecording(store, 91, 3L);
        RecordingRecoveryState replacement = new RecordingRecoveryState(store);
        replacement.prepareForServiceReplacement(true);
        replacement.recordPaused(91, 3L);

        assertTrue(replacement.clearAfterDiscard(91));
        assertEquals(
                RecordingRecoveryState.Phase.IDLE,
                replacement.snapshot().getPhase());
    }

    private static RecordingRecoveryState createRecording(
            FakeStore store, int activityId, long generation) {
        RecordingRecoveryState recovery = new RecordingRecoveryState(store);
        assertTrue(recovery.recordActivityCreated(activityId));
        assertTrue(recovery.recordRecording(activityId, generation));
        return recovery;
    }

    private static final class FakeStore implements RecordingRecoveryState.Store {
        RecordingRecoveryState.Snapshot snapshot =
                RecordingRecoveryState.Snapshot.idle();

        @Override
        public RecordingRecoveryState.Snapshot load() {
            return snapshot;
        }

        @Override
        public boolean save(RecordingRecoveryState.Snapshot value) {
            snapshot = value;
            return true;
        }

        @Override
        public boolean clear() {
            snapshot = RecordingRecoveryState.Snapshot.idle();
            return true;
        }
    }
}
