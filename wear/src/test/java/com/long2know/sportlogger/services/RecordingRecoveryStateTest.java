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

        assertTrue(recovery.clearAfterPreRecordingFailure().isPersisted());
        assertEquals(
                RecordingRecoveryState.Phase.IDLE,
                recovery.snapshot().getPhase());
        assertEquals(0, recovery.snapshot().getActivityId());
    }

    @Test
    public void ownedAsyncFailurePreservesExactActivityAndGeneration() {
        FakeStore store = new FakeStore();
        RecordingRecoveryState recovery = createRecording(store, 42, 7L);

        assertTrue(recovery.requireRecovery(42, 7L).isPersisted());
        assertFalse(recovery.requireRecovery(42, 6L).isAccepted());
        assertFalse(recovery.requireRecovery(41, 7L).isAccepted());

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
        assertTrue(replacement.prepareForServiceReplacement(true).isPersisted());
        assertEquals(
                RecordingRecoveryState.Phase.RECOVERY_REQUIRED,
                replacement.snapshot().getPhase());
        assertEquals(51, replacement.snapshot().getActivityId());
        assertEquals(12L, replacement.snapshot().getGeneration());

        assertTrue(replacement.recordPaused(51, 12L).isPersisted());
        assertEquals(
                RecordingRecoveryState.Phase.PAUSED,
                replacement.snapshot().getPhase());
    }

    @Test
    public void replacementDoesNotInventOwnershipForMissingDatabaseRow() {
        FakeStore store = new FakeStore();
        createRecording(store, 61, 2L);

        RecordingRecoveryState replacement = new RecordingRecoveryState(store);
        assertTrue(replacement.prepareForServiceReplacement(false).isPersisted());
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

        assertTrue(replacement.recordRecording(71, 5L).isPersisted());
        assertFalse(replacement.recordRecording(71, 5L).isAccepted());
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

        assertFalse(replacement.clearAfterStop(80).isAccepted());
        assertTrue(replacement.clearAfterStop(81).isPersisted());
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

        assertTrue(replacement.clearAfterDiscard(91).isPersisted());
        assertEquals(
                RecordingRecoveryState.Phase.IDLE,
                replacement.snapshot().getPhase());
    }

    @Test
    public void failedRecoveryPersistenceRetainsExactTupleInMemory() {
        FakeStore store = new FakeStore();
        RecordingRecoveryState recovery = createRecording(store, 101, 13L);
        store.failSaves = true;

        RecordingRecoveryState.Transition transition =
                recovery.requireRecovery(101, 13L);

        assertTrue(transition.isAccepted());
        assertFalse(transition.isPersisted());
        assertTrue(transition.isCurrentProcessOnly());
        assertEquals(
                RecordingRecoveryState.Phase.RECOVERY_REQUIRED,
                recovery.snapshot().getPhase());
        assertEquals(101, recovery.snapshot().getActivityId());
        assertEquals(13L, recovery.snapshot().getGeneration());
    }

    @Test
    public void failedFirstTuplePersistenceCannotPromiseProcessDeathRecovery() {
        FakeStore store = new FakeStore();
        store.failSaves = true;
        RecordingRecoveryState recovery = new RecordingRecoveryState(store);

        RecordingRecoveryState.Transition transition =
                recovery.recordActivityCreated(111);

        assertTrue(transition.isCurrentProcessOnly());
        assertTrue(recovery.snapshot().ownsActivity());

        RecordingRecoveryState replacement = new RecordingRecoveryState(store);
        assertFalse(replacement.snapshot().ownsActivity());
        assertEquals(
                RecordingRecoveryState.Phase.IDLE,
                replacement.snapshot().getPhase());
    }

    @Test
    public void failedRecoveryPhasePersistenceReloadsOnlyLastDurableState() {
        FakeStore store = new FakeStore();
        RecordingRecoveryState recovery = createRecording(store, 121, 17L);
        store.failSaves = true;

        assertFalse(recovery.requireRecovery(121, 17L).isPersisted());

        RecordingRecoveryState replacement = new RecordingRecoveryState(store);
        assertEquals(
                RecordingRecoveryState.Phase.RECORDING,
                replacement.snapshot().getPhase());
        assertEquals(121, replacement.snapshot().getActivityId());
        assertEquals(17L, replacement.snapshot().getGeneration());
    }

    @Test
    public void failedStopClearKeepsOwnedTupleInMemory() {
        FakeStore store = new FakeStore();
        RecordingRecoveryState recovery = createRecording(store, 131, 19L);
        store.failClears = true;

        RecordingRecoveryState.Transition transition =
                recovery.clearAfterStop(131);

        assertTrue(transition.isAccepted());
        assertFalse(transition.isPersisted());
        assertTrue(recovery.snapshot().ownsActivity());
        assertEquals(131, recovery.snapshot().getActivityId());
        assertEquals(19L, recovery.snapshot().getGeneration());
    }

    @Test
    public void failedDiscardClearRetainsMetadataForExplicitCleanupRetry() {
        FakeStore store = new FakeStore();
        RecordingRecoveryState recovery = createRecording(store, 141, 23L);
        store.failClears = true;

        RecordingRecoveryState.Transition transition =
                recovery.clearAfterDiscard(141);

        assertTrue(transition.isAccepted());
        assertFalse(transition.isPersisted());
        assertTrue(recovery.snapshot().ownsActivity());
        assertEquals(
                RecordingRecoveryState.Phase.RECORDING,
                recovery.snapshot().getPhase());
    }

    private static RecordingRecoveryState createRecording(
            FakeStore store, int activityId, long generation) {
        RecordingRecoveryState recovery = new RecordingRecoveryState(store);
        assertTrue(recovery.recordActivityCreated(activityId).isPersisted());
        assertTrue(recovery.recordRecording(activityId, generation).isPersisted());
        return recovery;
    }

    private static final class FakeStore implements RecordingRecoveryState.Store {
        RecordingRecoveryState.Snapshot snapshot =
                RecordingRecoveryState.Snapshot.idle();
        boolean failSaves;
        boolean failClears;

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
            if (failClears) {
                return false;
            }
            snapshot = RecordingRecoveryState.Snapshot.idle();
            return true;
        }
    }
}
