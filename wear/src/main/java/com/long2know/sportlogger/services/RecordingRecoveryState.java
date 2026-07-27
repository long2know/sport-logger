package com.long2know.sportlogger.services;

final class RecordingRecoveryState {
    enum Phase {
        IDLE,
        RECORDING,
        PAUSED,
        RECOVERY_REQUIRED
    }

    interface Store {
        Snapshot load();

        boolean save(Snapshot snapshot);

        boolean clear();
    }

    static final class Snapshot {
        private static final Snapshot IDLE =
                new Snapshot(Phase.IDLE, 0, 0L);

        private final Phase _phase;
        private final int _activityId;
        private final long _generation;

        private Snapshot(Phase phase, int activityId, long generation) {
            _phase = phase;
            _activityId = activityId;
            _generation = generation;
        }

        static Snapshot idle() {
            return IDLE;
        }

        static Snapshot owned(Phase phase, int activityId, long generation) {
            if (phase == null
                    || phase == Phase.IDLE
                    || activityId <= 0
                    || generation < 0L) {
                return IDLE;
            }
            return new Snapshot(phase, activityId, generation);
        }

        Phase getPhase() {
            return _phase;
        }

        int getActivityId() {
            return _activityId;
        }

        long getGeneration() {
            return _generation;
        }

        boolean ownsActivity() {
            return _activityId > 0 && _phase != Phase.IDLE;
        }
    }

    static final class Transition {
        private final Snapshot _snapshot;
        private final boolean _accepted;
        private final boolean _persisted;

        private Transition(Snapshot snapshot, boolean accepted, boolean persisted) {
            _snapshot = snapshot;
            _accepted = accepted;
            _persisted = persisted;
        }

        static Transition rejected(Snapshot snapshot) {
            return new Transition(snapshot, false, false);
        }

        static Transition accepted(Snapshot snapshot, boolean persisted) {
            return new Transition(snapshot, true, persisted);
        }

        boolean isAccepted() {
            return _accepted;
        }

        boolean isPersisted() {
            return _accepted && _persisted;
        }

        boolean isCurrentProcessOnly() {
            return _accepted && !_persisted && _snapshot.ownsActivity();
        }
    }

    private final Store _store;
    private Snapshot _snapshot;

    RecordingRecoveryState(Store store) {
        _store = store;
        Snapshot loaded = store.load();
        _snapshot = loaded == null ? Snapshot.idle() : loaded;
    }

    synchronized Snapshot snapshot() {
        return _snapshot;
    }

    synchronized Transition prepareForServiceReplacement(boolean activityExists) {
        if (!_snapshot.ownsActivity()) {
            return clearIdle();
        }
        if (!activityExists) {
            return clearOwnedActivity(_snapshot.getActivityId());
        }
        return update(Snapshot.owned(
                Phase.RECOVERY_REQUIRED,
                _snapshot.getActivityId(),
                _snapshot.getGeneration()));
    }

    synchronized Transition recordActivityCreated(int activityId) {
        if (_snapshot.ownsActivity() || activityId <= 0) {
            return Transition.rejected(_snapshot);
        }
        return update(Snapshot.owned(
                Phase.RECOVERY_REQUIRED, activityId, 0L));
    }

    synchronized Transition recordRecording(int activityId, long generation) {
        if (!matchesActivity(activityId)
                || generation <= _snapshot.getGeneration()) {
            return Transition.rejected(_snapshot);
        }
        return update(Snapshot.owned(
                Phase.RECORDING, activityId, generation));
    }

    synchronized Transition recordPaused(int activityId, long generation) {
        if (!matches(activityId, generation)) {
            return Transition.rejected(_snapshot);
        }
        return update(Snapshot.owned(
                Phase.PAUSED, activityId, generation));
    }

    synchronized Transition requireRecovery(int activityId, long generation) {
        if (!matches(activityId, generation)) {
            return Transition.rejected(_snapshot);
        }
        return update(Snapshot.owned(
                Phase.RECOVERY_REQUIRED, activityId, generation));
    }

    synchronized Transition clearAfterStop(int activityId) {
        return clearOwnedActivity(activityId);
    }

    synchronized Transition clearAfterDiscard(int activityId) {
        if (!matchesActivity(activityId)) {
            return Transition.rejected(_snapshot);
        }
        _snapshot = Snapshot.idle();
        boolean persisted;
        try {
            persisted = _store.clear();
        } catch (RuntimeException exception) {
            persisted = false;
        }
        return Transition.accepted(_snapshot, persisted);
    }

    synchronized Transition clearAfterPreRecordingFailure() {
        return !_snapshot.ownsActivity()
                ? clearIdle()
                : Transition.rejected(_snapshot);
    }

    private boolean matchesActivity(int activityId) {
        return _snapshot.ownsActivity()
                && _snapshot.getActivityId() == activityId;
    }

    private boolean matches(int activityId, long generation) {
        return matchesActivity(activityId)
                && _snapshot.getGeneration() == generation;
    }

    private Transition clearOwnedActivity(int activityId) {
        if (!matchesActivity(activityId)) {
            return Transition.rejected(_snapshot);
        }
        return clearIdle();
    }

    private Transition clearIdle() {
        boolean persisted;
        try {
            persisted = _store.clear();
        } catch (RuntimeException exception) {
            persisted = false;
        }
        if (!persisted) {
            return Transition.accepted(_snapshot, false);
        }
        _snapshot = Snapshot.idle();
        return Transition.accepted(_snapshot, true);
    }

    private Transition update(Snapshot snapshot) {
        _snapshot = snapshot;
        boolean persisted;
        try {
            persisted = _store.save(snapshot);
        } catch (RuntimeException exception) {
            persisted = false;
        }
        return Transition.accepted(_snapshot, persisted);
    }
}
