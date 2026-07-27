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

    synchronized boolean prepareForServiceReplacement(boolean activityExists) {
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

    synchronized boolean recordActivityCreated(int activityId) {
        if (_snapshot.ownsActivity() || activityId <= 0) {
            return false;
        }
        return update(Snapshot.owned(
                Phase.RECOVERY_REQUIRED, activityId, 0L));
    }

    synchronized boolean recordRecording(int activityId, long generation) {
        if (!matchesActivity(activityId)
                || generation <= _snapshot.getGeneration()) {
            return false;
        }
        return update(Snapshot.owned(
                Phase.RECORDING, activityId, generation));
    }

    synchronized boolean recordPaused(int activityId, long generation) {
        if (!matches(activityId, generation)) {
            return false;
        }
        return update(Snapshot.owned(
                Phase.PAUSED, activityId, generation));
    }

    synchronized boolean requireRecovery(int activityId, long generation) {
        if (!matches(activityId, generation)) {
            return false;
        }
        return update(Snapshot.owned(
                Phase.RECOVERY_REQUIRED, activityId, generation));
    }

    synchronized boolean clearAfterStop(int activityId) {
        return clearOwnedActivity(activityId);
    }

    synchronized boolean clearAfterDiscard(int activityId) {
        return clearOwnedActivity(activityId);
    }

    synchronized boolean clearAfterPreRecordingFailure() {
        return !_snapshot.ownsActivity() && clearIdle();
    }

    private boolean matchesActivity(int activityId) {
        return _snapshot.ownsActivity()
                && _snapshot.getActivityId() == activityId;
    }

    private boolean matches(int activityId, long generation) {
        return matchesActivity(activityId)
                && _snapshot.getGeneration() == generation;
    }

    private boolean clearOwnedActivity(int activityId) {
        if (!matchesActivity(activityId)) {
            return false;
        }
        return clearIdle();
    }

    private boolean clearIdle() {
        if (!_store.clear()) {
            return false;
        }
        _snapshot = Snapshot.idle();
        return true;
    }

    private boolean update(Snapshot snapshot) {
        _snapshot = snapshot;
        return _store.save(snapshot);
    }
}
