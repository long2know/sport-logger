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
                    || generation <= 0L) {
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
    private long _version;

    RecordingRecoveryState(Store store) {
        _store = store;
        Snapshot loaded = store.load();
        _snapshot = loaded == null ? Snapshot.idle() : loaded;
    }

    Snapshot snapshot() {
        synchronized (this) {
            return _snapshot;
        }
    }

    Transition prepareForServiceReplacement(boolean activityExists) {
        Snapshot current = snapshot();
        if (!current.ownsActivity()) {
            return clearIdle();
        }
        if (!activityExists) {
            return clearOwnedActivity(current.getActivityId());
        }
        return update(current, Snapshot.owned(
                Phase.RECOVERY_REQUIRED,
                current.getActivityId(),
                current.getGeneration()));
    }

    Transition reserveWriterGeneration(int activityId, long generation) {
        Snapshot current = snapshot();
        if (activityId <= 0
                || generation <= 0L
                || (current.ownsActivity()
                        && (current.getActivityId() != activityId
                                || generation <= current.getGeneration()))) {
            return Transition.rejected(current);
        }
        return update(current, Snapshot.owned(
                Phase.RECOVERY_REQUIRED, activityId, generation));
    }

    Transition recordRecording(int activityId, long generation) {
        Snapshot current = snapshot();
        if (!matches(current, activityId, generation)
                || current.getPhase() == Phase.RECORDING) {
            return Transition.rejected(current);
        }
        return update(current, Snapshot.owned(
                Phase.RECORDING, activityId, generation));
    }

    Transition recordPaused(int activityId, long generation) {
        Snapshot current = snapshot();
        if (!matches(current, activityId, generation)) {
            return Transition.rejected(current);
        }
        return update(current, Snapshot.owned(
                Phase.PAUSED, activityId, generation));
    }

    Transition requireRecovery(int activityId, long generation) {
        Snapshot current = snapshot();
        if (!matches(current, activityId, generation)) {
            return Transition.rejected(current);
        }
        return update(current, Snapshot.owned(
                Phase.RECOVERY_REQUIRED, activityId, generation));
    }

    Transition clearAfterStop(int activityId) {
        return clearOwnedActivity(activityId);
    }

    Transition clearAfterDiscard(int activityId) {
        Snapshot current = snapshot();
        if (!matchesActivity(current, activityId)) {
            return Transition.rejected(current);
        }
        return clear(current);
    }

    Transition clearAfterPreRecordingFailure() {
        Snapshot current = snapshot();
        return !current.ownsActivity()
                ? clearIdle()
                : Transition.rejected(current);
    }

    private static boolean matchesActivity(Snapshot snapshot, int activityId) {
        return snapshot.ownsActivity()
                && snapshot.getActivityId() == activityId;
    }

    private static boolean matches(
            Snapshot snapshot, int activityId, long generation) {
        return matchesActivity(snapshot, activityId)
                && snapshot.getGeneration() == generation;
    }

    private Transition clearOwnedActivity(int activityId) {
        Snapshot current = snapshot();
        if (!matchesActivity(current, activityId)) {
            return Transition.rejected(current);
        }
        return clear(current);
    }

    private Transition clearIdle() {
        return clear(snapshot());
    }

    private Transition clear(Snapshot expected) {
        final long version;
        synchronized (this) {
            if (_snapshot != expected) {
                return Transition.rejected(_snapshot);
            }
            version = ++_version;
        }
        boolean persisted;
        try {
            persisted = _store.clear();
        } catch (RuntimeException exception) {
            persisted = false;
        }
        synchronized (this) {
            if (_version != version || _snapshot != expected) {
                return Transition.rejected(_snapshot);
            }
            if (persisted) {
                _snapshot = Snapshot.idle();
            }
            return Transition.accepted(_snapshot, persisted);
        }
    }

    private Transition update(Snapshot expected, Snapshot snapshot) {
        final long version;
        synchronized (this) {
            if (_snapshot != expected) {
                return Transition.rejected(_snapshot);
            }
            _snapshot = snapshot;
            version = ++_version;
        }
        boolean persisted;
        try {
            persisted = _store.save(snapshot);
        } catch (RuntimeException exception) {
            persisted = false;
        }
        synchronized (this) {
            if (_version != version || _snapshot != snapshot) {
                return Transition.rejected(_snapshot);
            }
            return Transition.accepted(_snapshot, persisted);
        }
    }
}
