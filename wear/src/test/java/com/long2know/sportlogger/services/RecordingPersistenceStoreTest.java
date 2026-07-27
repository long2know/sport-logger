package com.long2know.sportlogger.services;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class RecordingPersistenceStoreTest {
    private static final String RECOVERY_PREFERENCES =
            "sport_logger_recording_recovery";
    private static final String TERMINAL_PREFERENCES =
            "sport_logger_terminal_export_handoff";

    private Context _context;

    @Before
    public void setUp() {
        _context = RuntimeEnvironment.getApplication();
        clearPreferences();
    }

    @After
    public void tearDown() {
        clearPreferences();
    }

    private void clearPreferences() {
        _context.getSharedPreferences(
                RECOVERY_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        _context.getSharedPreferences(
                TERMINAL_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
    }

    @Test
    public void processDeathReloadsRecoveryAndTerminalState() {
        RecordingPersistenceBarrier firstProcess =
                new RecordingPersistenceBarrier();
        StatePair first = activate(
                firstProcess, firstProcess.reserveEpoch());
        assertTrue(first.recovery.reserveWriterGeneration(
                41, 7L).isPersisted());
        assertTrue(first.recovery.recordRecording(41, 7L).isPersisted());
        assertTrue(first.terminal.recordSuccessfulStop(
                41, 7L).isPersisted());

        RecordingPersistenceBarrier replacementProcess =
                new RecordingPersistenceBarrier();
        StatePair replacement = activate(
                replacementProcess, replacementProcess.reserveEpoch());

        assertEquals(41, replacement.recovery.snapshot().getActivityId());
        assertEquals(7L, replacement.recovery.snapshot().getGeneration());
        assertNotNull(replacement.terminal.pending());
        assertEquals(41, replacement.terminal.pending().getActivityId());
        assertEquals(7L, replacement.terminal.pending().getGeneration());
    }

    @Test
    public void predecessorCannotClearOrOverwriteReplacementState() {
        RecordingPersistenceBarrier barrier =
                new RecordingPersistenceBarrier();
        RecordingPersistenceBarrier.Epoch predecessorEpoch =
                barrier.reserveEpoch();
        StatePair predecessor = activate(barrier, predecessorEpoch);
        assertTrue(predecessor.recovery.reserveWriterGeneration(
                51, 9L).isPersisted());
        assertTrue(predecessor.recovery.recordRecording(
                51, 9L).isPersisted());
        long operationId = predecessor.terminal.recordSuccessfulStop(
                51, 9L).getCompletion().getOperationId();

        StatePair replacement = activate(
                barrier, barrier.reserveEpoch());
        assertEquals(51, replacement.recovery.snapshot().getActivityId());
        assertNotNull(replacement.terminal.pending());

        assertEquals(
                RecordingTerminalCompletionState.AcknowledgeStatus.RETAINED,
                predecessor.terminal.acknowledge(operationId, true));
        assertTrue(predecessor.recovery.requireRecovery(
                51, 9L).isCurrentProcessOnly());

        RecordingPersistenceBarrier processDeath =
                new RecordingPersistenceBarrier();
        StatePair durable = activate(
                processDeath, processDeath.reserveEpoch());
        assertEquals(
                RecordingRecoveryState.Phase.RECORDING,
                durable.recovery.snapshot().getPhase());
        assertNotNull(durable.terminal.pending());
        assertEquals(operationId,
                durable.terminal.pending().getOperationId());
    }

    @Test
    public void processDeathPreservesStoppingBeforeTerminalHandoff() {
        RecordingPersistenceBarrier firstProcess =
                new RecordingPersistenceBarrier();
        StatePair first = activate(
                firstProcess, firstProcess.reserveEpoch());
        assertTrue(first.recovery.reserveWriterGeneration(
                61, 11L).isPersisted());
        assertTrue(first.recovery.recordRecording(61, 11L).isPersisted());
        assertTrue(first.recovery.recordPaused(61, 11L).isPersisted());
        assertTrue(first.recovery.recordStopping(61, 11L).isPersisted());

        RecordingPersistenceBarrier replacementProcess =
                new RecordingPersistenceBarrier();
        StatePair replacement = activate(
                replacementProcess, replacementProcess.reserveEpoch());

        assertEquals(
                RecordingRecoveryState.Phase.STOPPING,
                replacement.recovery.snapshot().getPhase());
        assertEquals(61, replacement.recovery.snapshot().getActivityId());
        assertEquals(11L, replacement.recovery.snapshot().getGeneration());
        assertNull(replacement.terminal.pending());
    }

    private StatePair activate(
            RecordingPersistenceBarrier barrier,
            RecordingPersistenceBarrier.Epoch epoch) {
        RecordingPersistenceBarrier.Activation<StatePair> activation =
                barrier.activate(epoch, () -> new StatePair(
                        new RecordingRecoveryState(
                                new SharedPreferencesRecordingRecoveryStore(
                                        _context, barrier, epoch)),
                        new RecordingTerminalCompletionState(
                                new SharedPreferencesRecordingTerminalCompletionStore(
                                        _context, barrier, epoch))));
        assertTrue(activation.activated());
        return activation.getValue();
    }

    private static final class StatePair {
        final RecordingRecoveryState recovery;
        final RecordingTerminalCompletionState terminal;

        StatePair(
                RecordingRecoveryState recovery,
                RecordingTerminalCompletionState terminal) {
            this.recovery = recovery;
            this.terminal = terminal;
        }
    }
}
