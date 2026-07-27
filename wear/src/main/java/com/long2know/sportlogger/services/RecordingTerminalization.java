package com.long2know.sportlogger.services;

final class RecordingTerminalization {
    interface ActivityStore {
        enum State {
            MISSING,
            OPEN,
            COMPLETED
        }

        enum Completion {
            COMPLETED,
            ALREADY_COMPLETED,
            MISSING
        }

        State getState(int activityId);

        Completion complete(int activityId);
    }

    enum Status {
        COMMITTED,
        INTENT_PERSISTENCE_FAILED,
        DATABASE_FAILED,
        HANDOFF_PERSISTENCE_FAILED,
        HANDOFF_OCCUPIED,
        STATE_CHANGED
    }

    static final class Result {
        private final Status _status;
        private final RecordingRecoveryState.Transition _intent;
        private final RecordingTerminalCompletion _completion;

        private Result(
                Status status,
                RecordingRecoveryState.Transition intent,
                RecordingTerminalCompletion completion) {
            _status = status;
            _intent = intent;
            _completion = completion;
        }

        Status getStatus() {
            return _status;
        }

        RecordingRecoveryState.Transition getIntent() {
            return _intent;
        }

        RecordingTerminalCompletion getCompletion() {
            return _completion;
        }

        boolean isCommitted() {
            return _status == Status.COMMITTED && _completion != null;
        }
    }

    private RecordingTerminalization() {
    }

    static Result finish(
            RecordingRecoveryState recovery,
            RecordingTerminalCompletionState terminal,
            ActivityStore activities,
            int activityId,
            long generation) {
        RecordingRecoveryState.Transition intent =
                recovery.recordStopping(activityId, generation);
        if (!intent.isAccepted()) {
            return new Result(Status.STATE_CHANGED, intent, null);
        }
        if (!intent.isPersisted()) {
            return new Result(
                    Status.INTENT_PERSISTENCE_FAILED, intent, null);
        }

        final ActivityStore.State state;
        try {
            state = activities.getState(activityId);
            if (state == ActivityStore.State.MISSING) {
                return new Result(Status.DATABASE_FAILED, intent, null);
            }
            if (state == ActivityStore.State.OPEN
                    && activities.complete(activityId)
                            == ActivityStore.Completion.MISSING) {
                return new Result(Status.DATABASE_FAILED, intent, null);
            }
        } catch (RuntimeException exception) {
            return new Result(Status.DATABASE_FAILED, intent, null);
        }

        RecordingTerminalCompletionState.RecordResult handoff =
                terminal.recordSuccessfulStop(activityId, generation);
        if (handoff.isOccupied()) {
            return new Result(Status.HANDOFF_OCCUPIED, intent, null);
        }
        if (!handoff.isPersisted()) {
            return new Result(
                    handoff.isAccepted()
                            ? Status.HANDOFF_PERSISTENCE_FAILED
                            : Status.STATE_CHANGED,
                    intent,
                    null);
        }
        return new Result(
                Status.COMMITTED, intent, handoff.getCompletion());
    }
}
