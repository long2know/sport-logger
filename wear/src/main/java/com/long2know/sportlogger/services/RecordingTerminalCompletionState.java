package com.long2know.sportlogger.services;

final class RecordingTerminalCompletionState {
    interface Store {
        Snapshot load();

        boolean save(Snapshot snapshot);

        boolean clearPending(long expectedOperationId);
    }

    static final class Snapshot {
        private final long _lastOperationId;
        private final RecordingTerminalCompletion _pending;

        private Snapshot(
                long lastOperationId, RecordingTerminalCompletion pending) {
            _lastOperationId = Math.max(0L, lastOperationId);
            _pending = pending;
        }

        static Snapshot empty(long lastOperationId) {
            return new Snapshot(lastOperationId, null);
        }

        static Snapshot pending(
                long lastOperationId, RecordingTerminalCompletion pending) {
            if (pending == null
                    || pending.getOperationId() <= 0L
                    || pending.getActivityId() <= 0
                    || pending.getGeneration() <= 0L
                    || pending.getType() == null
                    || pending.getResult() == null) {
                return empty(lastOperationId);
            }
            return new Snapshot(
                    Math.max(lastOperationId, pending.getOperationId()), pending);
        }

        long getLastOperationId() {
            return _lastOperationId;
        }

        RecordingTerminalCompletion getPending() {
            return _pending;
        }
    }

    static final class RecordResult {
        private final RecordingTerminalCompletion _completion;
        private final boolean _accepted;
        private final boolean _persisted;
        private final boolean _occupied;

        private RecordResult(
                RecordingTerminalCompletion completion,
                boolean accepted,
                boolean persisted,
                boolean occupied) {
            _completion = completion;
            _accepted = accepted;
            _persisted = persisted;
            _occupied = occupied;
        }

        static RecordResult recorded(RecordingTerminalCompletion completion) {
            return new RecordResult(completion, true, true, false);
        }

        static RecordResult failed(RecordingTerminalCompletion completion) {
            return new RecordResult(completion, true, false, false);
        }

        static RecordResult occupied(RecordingTerminalCompletion completion) {
            return new RecordResult(completion, false, false, true);
        }

        static RecordResult rejected() {
            return new RecordResult(null, false, false, false);
        }

        RecordingTerminalCompletion getCompletion() {
            return _completion;
        }

        boolean isAccepted() {
            return _accepted;
        }

        boolean isPersisted() {
            return _accepted && _persisted;
        }

        boolean isOccupied() {
            return _occupied;
        }
    }

    enum AcknowledgeStatus {
        CLEARED,
        ALREADY_CLEARED,
        RETAINED,
        STALE
    }

    private final Store _store;
    private Snapshot _snapshot;
    private boolean _mutationInProgress;

    RecordingTerminalCompletionState(Store store) {
        _store = store;
        Snapshot loaded = store.load();
        _snapshot = loaded == null ? Snapshot.empty(0L) : loaded;
    }

    synchronized RecordingTerminalCompletion pending() {
        return _snapshot.getPending();
    }

    synchronized boolean protectsActivity(int activityId) {
        RecordingTerminalCompletion pending = _snapshot.getPending();
        return pending != null && pending.getActivityId() == activityId;
    }

    RecordResult recordSuccessfulStop(int activityId, long generation) {
        final Snapshot proposed;
        synchronized (this) {
            RecordingTerminalCompletion pending = _snapshot.getPending();
            if (pending != null) {
                if (pending.getActivityId() == activityId
                        && pending.getGeneration() == generation
                        && pending.getType()
                                == RecordingTerminalCompletion.Type.STOP_EXPORT
                        && pending.getResult()
                                == RecordingTerminalCompletion.Result.SUCCESS) {
                    return RecordResult.recorded(pending);
                }
                return RecordResult.occupied(pending);
            }
            if (_mutationInProgress || activityId <= 0 || generation <= 0L) {
                return RecordResult.rejected();
            }
            long operationId = _snapshot.getLastOperationId() + 1L;
            RecordingTerminalCompletion completion =
                    new RecordingTerminalCompletion(
                            operationId,
                            activityId,
                            generation,
                            RecordingTerminalCompletion.Type.STOP_EXPORT,
                            RecordingTerminalCompletion.Result.SUCCESS);
            proposed = Snapshot.pending(operationId, completion);
            _mutationInProgress = true;
        }

        boolean persisted;
        try {
            persisted = _store.save(proposed);
        } catch (RuntimeException exception) {
            persisted = false;
        }
        synchronized (this) {
            _mutationInProgress = false;
            if (persisted) {
                _snapshot = proposed;
                return RecordResult.recorded(proposed.getPending());
            }
            return RecordResult.failed(proposed.getPending());
        }
    }

    AcknowledgeStatus acknowledge(long operationId, boolean successful) {
        if (!successful) {
            return AcknowledgeStatus.RETAINED;
        }

        final RecordingTerminalCompletion pending;
        synchronized (this) {
            pending = _snapshot.getPending();
            if (pending == null) {
                return operationId > 0L
                                && operationId <= _snapshot.getLastOperationId()
                        ? AcknowledgeStatus.ALREADY_CLEARED
                        : AcknowledgeStatus.STALE;
            }
            if (pending.getOperationId() != operationId
                    || _mutationInProgress) {
                return AcknowledgeStatus.STALE;
            }
            _mutationInProgress = true;
        }

        boolean cleared;
        try {
            cleared = _store.clearPending(operationId);
        } catch (RuntimeException exception) {
            cleared = false;
        }
        synchronized (this) {
            _mutationInProgress = false;
            if (!cleared
                    || _snapshot.getPending() == null
                    || _snapshot.getPending().getOperationId()
                            != operationId) {
                return AcknowledgeStatus.RETAINED;
            }
            _snapshot = Snapshot.empty(_snapshot.getLastOperationId());
            return AcknowledgeStatus.CLEARED;
        }
    }
}
