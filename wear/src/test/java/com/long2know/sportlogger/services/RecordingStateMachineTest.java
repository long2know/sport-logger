package com.long2know.sportlogger.services;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecordingStateMachineTest {
    @Test
    public void duplicateTransitionsAreNoOpsAndInvalidTransitionsAreRejected() {
        RecordingStateMachine machine = new RecordingStateMachine();

        assertEquals(
                RecordingStateMachine.Decision.ACCEPTED,
                machine.begin(RecordingStateMachine.Operation.START));
        assertEquals(
                RecordingStateMachine.Decision.NO_OP,
                machine.begin(RecordingStateMachine.Operation.START));
        assertTrue(machine.completeSuccess(RecordingStateMachine.Operation.START));
        assertEquals(
                RecordingStateMachine.Decision.NO_OP,
                machine.begin(RecordingStateMachine.Operation.START));
        assertEquals(
                RecordingStateMachine.Decision.NO_OP,
                machine.begin(RecordingStateMachine.Operation.RESUME));

        assertEquals(
                RecordingStateMachine.Decision.ACCEPTED,
                machine.begin(RecordingStateMachine.Operation.PAUSE));
        assertEquals(
                RecordingStateMachine.Decision.NO_OP,
                machine.begin(RecordingStateMachine.Operation.PAUSE));
        assertTrue(machine.completeSuccess(RecordingStateMachine.Operation.PAUSE));
        assertEquals(
                RecordingStateMachine.Decision.NO_OP,
                machine.begin(RecordingStateMachine.Operation.PAUSE));

        assertEquals(
                RecordingStateMachine.Decision.ACCEPTED,
                machine.begin(RecordingStateMachine.Operation.RESUME));
        assertEquals(
                RecordingStateMachine.Decision.NO_OP,
                machine.begin(RecordingStateMachine.Operation.RESUME));
        assertTrue(machine.completeSuccess(RecordingStateMachine.Operation.RESUME));
        assertEquals(
                RecordingStateMachine.Decision.NO_OP,
                machine.begin(RecordingStateMachine.Operation.RESUME));
    }

    @Test
    public void fenceFailureRequiresExplicitRecoveryBeforeRetry() {
        RecordingStateMachine machine = new RecordingStateMachine();
        machine.begin(RecordingStateMachine.Operation.START);
        machine.completeSuccess(RecordingStateMachine.Operation.START);

        assertEquals(
                RecordingStateMachine.Decision.ACCEPTED,
                machine.begin(RecordingStateMachine.Operation.STOP));
        assertTrue(machine.completeFailure(RecordingStateMachine.Operation.STOP, true));
        assertEquals(
                RecordingStateMachine.State.RECOVERY_REQUIRED,
                machine.getState());

        assertEquals(
                RecordingStateMachine.Decision.INVALID,
                machine.begin(RecordingStateMachine.Operation.STOP));
        assertEquals(
                RecordingStateMachine.Decision.ACCEPTED,
                machine.begin(RecordingStateMachine.Operation.RECOVER));
        assertFalse(machine.completeSuccess(RecordingStateMachine.Operation.STOP));
        assertTrue(machine.completeSuccess(RecordingStateMachine.Operation.RECOVER));
        assertEquals(RecordingStateMachine.State.PAUSED, machine.getState());
        assertEquals(
                RecordingStateMachine.Decision.ACCEPTED,
                machine.begin(RecordingStateMachine.Operation.STOP));
    }

    @Test
    public void asynchronousWriterFailureRetainsActivityForExplicitRecovery() {
        RecordingStateMachine machine = new RecordingStateMachine();
        machine.begin(RecordingStateMachine.Operation.START);
        machine.completeSuccess(RecordingStateMachine.Operation.START);

        machine.failGeneration();

        assertEquals(
                RecordingStateMachine.State.RECOVERY_REQUIRED,
                machine.getState());
        assertEquals(
                RecordingStateMachine.Decision.INVALID,
                machine.begin(RecordingStateMachine.Operation.RESUME));
        assertEquals(
                RecordingStateMachine.Decision.INVALID,
                machine.begin(RecordingStateMachine.Operation.STOP));
        assertEquals(
                RecordingStateMachine.Decision.ACCEPTED,
                machine.begin(RecordingStateMachine.Operation.RECOVER));
        assertTrue(machine.completeSuccess(RecordingStateMachine.Operation.RECOVER));
        assertEquals(
                RecordingStateMachine.Decision.ACCEPTED,
                machine.begin(RecordingStateMachine.Operation.RESUME));
    }

    @Test
    public void replacementRestoresOwnedActivityWithoutPretendingItIsPaused() {
        RecordingStateMachine machine = new RecordingStateMachine();

        machine.restoreOwnedActivity();

        assertEquals(
                RecordingStateMachine.State.RECOVERY_REQUIRED,
                machine.getState());
        assertEquals(
                RecordingStateMachine.Decision.INVALID,
                machine.begin(RecordingStateMachine.Operation.DISCARD));
        assertEquals(
                RecordingStateMachine.Decision.ACCEPTED,
                machine.begin(RecordingStateMachine.Operation.RECOVER));
    }

    @Test
    public void inFlightTransitionsExposeTheirPreviousStableUiState() {
        RecordingStateMachine machine = new RecordingStateMachine();

        machine.begin(RecordingStateMachine.Operation.START);
        assertEquals(
                RecordingStateMachine.State.IDLE,
                machine.getStableState());
        machine.completeSuccess(RecordingStateMachine.Operation.START);

        machine.begin(RecordingStateMachine.Operation.PAUSE);
        assertEquals(
                RecordingStateMachine.State.RECORDING,
                machine.getStableState());
        machine.completeSuccess(RecordingStateMachine.Operation.PAUSE);

        machine.begin(RecordingStateMachine.Operation.STOP);
        assertEquals(
                RecordingStateMachine.State.PAUSED,
                machine.getStableState());
    }
}
