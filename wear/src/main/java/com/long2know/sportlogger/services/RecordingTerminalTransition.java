package com.long2know.sportlogger.services;

final class RecordingTerminalTransition {
    enum Outcome {
        COMMITTED,
        WRITER_FAILED,
        FENCE_FAILED,
        STATE_CHANGED;

        boolean permitsTerminalEffects() {
            return this == COMMITTED;
        }
    }

    private RecordingTerminalTransition() {
    }

    static Outcome finish(
            RecordingStateMachine stateMachine,
            RecordingStateMachine.Operation operation,
            LifecycleTermination termination) {
        if (!termination.succeeded()) {
            if (!stateMachine.completeFailure(operation, true)) {
                stateMachine.restoreOwnedActivity();
            }
            return termination == LifecycleTermination.TERMINATED_WITH_FAILURE
                    ? Outcome.WRITER_FAILED
                    : Outcome.FENCE_FAILED;
        }
        if (!stateMachine.completeSuccess(operation)) {
            stateMachine.restoreOwnedActivity();
            return Outcome.STATE_CHANGED;
        }
        return Outcome.COMMITTED;
    }
}
