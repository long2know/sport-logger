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
    public void fenceFailureRetainsActivityAndAllowsRetry() {
        RecordingStateMachine machine = new RecordingStateMachine();
        machine.begin(RecordingStateMachine.Operation.START);
        machine.completeSuccess(RecordingStateMachine.Operation.START);

        assertEquals(
                RecordingStateMachine.Decision.ACCEPTED,
                machine.begin(RecordingStateMachine.Operation.STOP));
        assertTrue(machine.completeFailure(RecordingStateMachine.Operation.STOP, true));
        assertEquals(
                RecordingStateMachine.State.FENCE_FAILED,
                machine.getState());

        assertEquals(
                RecordingStateMachine.Decision.ACCEPTED,
                machine.begin(RecordingStateMachine.Operation.STOP));
        assertFalse(machine.completeSuccess(RecordingStateMachine.Operation.DISCARD));
        assertTrue(machine.completeSuccess(RecordingStateMachine.Operation.STOP));
        assertEquals(RecordingStateMachine.State.IDLE, machine.getState());
    }

    @Test
    public void asynchronousWriterFailureMakesTheGenerationTerminal() {
        RecordingStateMachine machine = new RecordingStateMachine();
        machine.begin(RecordingStateMachine.Operation.START);
        machine.completeSuccess(RecordingStateMachine.Operation.START);

        machine.failGeneration();

        assertEquals(
                RecordingStateMachine.State.SHUTTING_DOWN,
                machine.getState());
        assertEquals(
                RecordingStateMachine.Decision.INVALID,
                machine.begin(RecordingStateMachine.Operation.RESUME));
        assertEquals(
                RecordingStateMachine.Decision.INVALID,
                machine.begin(RecordingStateMachine.Operation.STOP));
    }
}
