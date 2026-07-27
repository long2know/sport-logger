package com.long2know.sportlogger.services;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class RecordingTerminalTransitionTest {
    @Test
    public void writerFailureRacingStopCannotAuthorizeExportOrOwnershipClear() {
        RecordingStateMachine machine = recordingMachine();
        machine.begin(RecordingStateMachine.Operation.STOP);
        machine.failGeneration();
        AtomicInteger terminalEffects = new AtomicInteger();

        RecordingTerminalTransition.Outcome outcome =
                RecordingTerminalTransition.finish(
                        machine,
                        RecordingStateMachine.Operation.STOP,
                        LifecycleTermination.TERMINATED_WITH_FAILURE);
        if (outcome.permitsTerminalEffects()) {
            terminalEffects.incrementAndGet();
        }

        assertEquals(
                RecordingTerminalTransition.Outcome.WRITER_FAILED,
                outcome);
        assertEquals(0, terminalEffects.get());
        assertEquals(
                RecordingStateMachine.State.RECOVERY_REQUIRED,
                machine.getState());
    }

    @Test
    public void writerFailureRacingDiscardCannotAuthorizeDeleteOrOwnershipClear() {
        RecordingStateMachine machine = recordingMachine();
        machine.begin(RecordingStateMachine.Operation.DISCARD);
        machine.failGeneration();
        AtomicInteger terminalEffects = new AtomicInteger();

        RecordingTerminalTransition.Outcome outcome =
                RecordingTerminalTransition.finish(
                        machine,
                        RecordingStateMachine.Operation.DISCARD,
                        LifecycleTermination.TERMINATED_WITH_FAILURE);
        if (outcome.permitsTerminalEffects()) {
            terminalEffects.incrementAndGet();
        }

        assertEquals(
                RecordingTerminalTransition.Outcome.WRITER_FAILED,
                outcome);
        assertEquals(0, terminalEffects.get());
        assertEquals(
                RecordingStateMachine.State.RECOVERY_REQUIRED,
                machine.getState());
    }

    @Test
    public void failedGenerationIsNotSuccessfulQuiescence() {
        assertFalse(LifecycleTermination.TERMINATED_WITH_FAILURE.succeeded());
        assertFalse(LifecycleTermination.TERMINATED_WITH_FAILURE.quiesced());
    }

    @Test
    public void changedStateCannotAuthorizeEffectsAfterCleanFence() {
        RecordingStateMachine machine = recordingMachine();
        machine.begin(RecordingStateMachine.Operation.STOP);
        machine.failGeneration();

        RecordingTerminalTransition.Outcome outcome =
                RecordingTerminalTransition.finish(
                        machine,
                        RecordingStateMachine.Operation.STOP,
                        LifecycleTermination.TERMINATED);

        assertEquals(
                RecordingTerminalTransition.Outcome.STATE_CHANGED, outcome);
        assertFalse(outcome.permitsTerminalEffects());
        assertEquals(
                RecordingStateMachine.State.RECOVERY_REQUIRED,
                machine.getState());
    }

    private static RecordingStateMachine recordingMachine() {
        RecordingStateMachine machine = new RecordingStateMachine();
        machine.begin(RecordingStateMachine.Operation.START);
        machine.completeSuccess(RecordingStateMachine.Operation.START);
        return machine;
    }
}
