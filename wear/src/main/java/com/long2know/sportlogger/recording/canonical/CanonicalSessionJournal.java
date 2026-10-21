package com.long2know.sportlogger.recording.canonical;

import android.content.Context;
import android.content.SharedPreferences;

import com.long2know.sportlogger.recording.RecordingSnapshot;
import com.long2know.utilities.contract.CanonicalIds;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

final class CanonicalSessionJournal {
    private static final Object LOCK = new Object();
    private static final String PREFERENCES_NAME = "sport_logger_canonical_recording";
    private static final String KEY_LEASE_COUNTER = "lease_counter";
    private static final String KEY_SESSION_ID = "session_id";
    private static final String KEY_LEASE = "lease";
    private static final String KEY_ACTIVITY_ID = "activity_id";
    private static final String KEY_STARTED_AT = "started_at";
    private static final String KEY_TIME_ZONE = "time_zone";
    private static final String KEY_UTC_OFFSET = "utc_offset";
    private static final String KEY_BOOT_ID = "boot_id";
    private static final String KEY_MONOTONIC_ANCHOR = "monotonic_anchor";
    private static final String KEY_ACCUMULATED_AT_START = "accumulated_at_start";
    private static final String KEY_NEXT_SEQUENCE = "next_sequence";
    private static final String KEY_LIFECYCLE = "lifecycle";
    private static final String KEY_OPEN_INTERVAL_ELAPSED = "open_interval_elapsed";
    private static final String KEY_OPEN_INTERVAL_ACTIVE = "open_interval_active";
    private static final String KEY_INTERVALS = "intervals";
    private static final String KEY_COMPLETION_TRANSFER = "completion_transfer";
    private static final String KEY_COMPLETION_REVISION = "completion_revision";
    private static final String KEY_DISCARD_TRANSFER = "discard_transfer";
    private static final String KEY_DISCARD_REVISION = "discard_revision";
    private static final String KEY_TERMINAL = "terminal";

    private final SharedPreferences preferences;

    CanonicalSessionJournal(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE);
    }

    State openOrCreate(RecordingSnapshot snapshot, String bootId) {
        synchronized (LOCK) {
            State existing = readLocked();
            if (existing != null) {
                if (existing.activityId == snapshot.getActivityId()) {
                    return existing;
                }
                if (!existing.terminal) {
                    throw new IllegalStateException(
                            "A different canonical recording is still active");
                }
                clearSessionLocked();
            }

            long leaseCounter = preferences.getLong(KEY_LEASE_COUNTER, 0L) + 1L;
            if (leaseCounter == 0L) {
                leaseCounter = 1L;
            }
            long startedAt = snapshot.getStartedAtMillis();
            if (startedAt <= 0L || snapshot.getActivityId() <= 0) {
                throw new IllegalArgumentException(
                        "Canonical recording requires a started legacy activity");
            }
            TimeZone timeZone = TimeZone.getDefault();
            String intervals = "";
            SharedPreferences.Editor editor = preferences.edit()
                    .putLong(KEY_LEASE_COUNTER, leaseCounter)
                    .putString(KEY_SESSION_ID, CanonicalIds.newSessionId())
                    .putLong(KEY_LEASE, leaseCounter)
                    .putInt(KEY_ACTIVITY_ID, snapshot.getActivityId())
                    .putLong(KEY_STARTED_AT, startedAt)
                    .putString(KEY_TIME_ZONE, timeZone.getID())
                    .putInt(
                            KEY_UTC_OFFSET,
                            timeZone.getOffset(startedAt) / (60 * 1_000))
                    .putString(KEY_BOOT_ID, bootId)
                    .putLong(
                            KEY_MONOTONIC_ANCHOR,
                            snapshot.getElapsedSinceElapsedRealtimeMillis())
                    .putLong(KEY_ACCUMULATED_AT_START, snapshot.getDurationMillis())
                    .putLong(KEY_NEXT_SEQUENCE, 1L)
                    .putString(KEY_LIFECYCLE, snapshot.getState().name())
                    .putString(KEY_INTERVALS, intervals)
                    .putBoolean(KEY_TERMINAL, false)
                    .remove(KEY_COMPLETION_TRANSFER)
                    .remove(KEY_COMPLETION_REVISION)
                    .remove(KEY_DISCARD_TRANSFER)
                    .remove(KEY_DISCARD_REVISION);
            if (snapshot.getState()
                    == com.long2know.sportlogger.recording.RecordingState.RECORDING) {
                editor.putLong(
                        KEY_OPEN_INTERVAL_ELAPSED,
                        snapshot.getElapsedDurationMillis())
                        .putLong(
                                KEY_OPEN_INTERVAL_ACTIVE,
                                snapshot.getDurationMillis());
            } else {
                editor.remove(KEY_OPEN_INTERVAL_ELAPSED)
                        .remove(KEY_OPEN_INTERVAL_ACTIVE);
            }
            commit(editor, "Canonical recording identity was not persisted");
            return readLocked();
        }
    }

    State read() {
        synchronized (LOCK) {
            return readLocked();
        }
    }

    long nextSequence(String sessionId) {
        synchronized (LOCK) {
            State state = requireLocked(sessionId);
            long sequence = state.nextSequence;
            if (sequence < 0L || sequence == Long.MAX_VALUE) {
                throw new IllegalStateException("Canonical sample sequence is exhausted");
            }
            commit(
                    preferences.edit().putLong(KEY_NEXT_SEQUENCE, sequence + 1L),
                    "Canonical sample sequence was not persisted");
            return sequence;
        }
    }

    State recordSnapshot(String sessionId, RecordingSnapshot snapshot) {
        synchronized (LOCK) {
            State state = requireLocked(sessionId);
            String previous = state.lifecycle;
            String next = snapshot.getState().name();
            SharedPreferences.Editor editor = preferences.edit()
                    .putString(KEY_LIFECYCLE, next);
            List<Interval> intervals = new ArrayList<>(state.intervals);
            Long openElapsed = state.openIntervalElapsed;
            Long openActive = state.openIntervalActive;
            if ("RECORDING".equals(previous) && !"RECORDING".equals(next)
                    && openElapsed != null && openActive != null) {
                long endElapsed = Math.max(
                        openElapsed,
                        snapshot.getElapsedDurationMillis());
                long activeDuration = Math.max(
                        0L,
                        snapshot.getDurationMillis() - openActive);
                intervals.add(new Interval(
                        openElapsed,
                        endElapsed,
                        Math.min(activeDuration, endElapsed - openElapsed)));
                openElapsed = null;
                openActive = null;
                editor.remove(KEY_OPEN_INTERVAL_ELAPSED)
                        .remove(KEY_OPEN_INTERVAL_ACTIVE);
            } else if (!"RECORDING".equals(previous)
                    && "RECORDING".equals(next)) {
                openElapsed = snapshot.getElapsedDurationMillis();
                openActive = snapshot.getDurationMillis();
                editor.putLong(KEY_OPEN_INTERVAL_ELAPSED, openElapsed)
                        .putLong(KEY_OPEN_INTERVAL_ACTIVE, openActive);
            }
            editor.putString(KEY_INTERVALS, encodeIntervals(intervals));
            commit(editor, "Canonical lifecycle was not persisted");
            return readLocked();
        }
    }

    State reserveCompletion(
            String sessionId,
            long revision,
            String transferId) {
        synchronized (LOCK) {
            State state = requireLocked(sessionId);
            if (state.completionTransferId != null) {
                if (!state.completionTransferId.equals(transferId)
                        || state.completionRevision == null
                        || state.completionRevision != revision) {
                    throw new IllegalStateException(
                            "Canonical completion identity conflicts with recovery state");
                }
                return state;
            }
            commit(
                    preferences.edit()
                            .putString(KEY_COMPLETION_TRANSFER, transferId)
                            .putLong(KEY_COMPLETION_REVISION, revision),
                    "Canonical completion identity was not persisted");
            return readLocked();
        }
    }

    State reserveDiscard(
            String sessionId,
            long revision,
            String transferId) {
        synchronized (LOCK) {
            State state = requireLocked(sessionId);
            if (state.discardTransferId != null) {
                if (!state.discardTransferId.equals(transferId)
                        || state.discardRevision == null
                        || state.discardRevision != revision) {
                    throw new IllegalStateException(
                            "Canonical discard identity conflicts with recovery state");
                }
                return state;
            }
            commit(
                    preferences.edit()
                            .putString(KEY_DISCARD_TRANSFER, transferId)
                            .putLong(KEY_DISCARD_REVISION, revision),
                    "Canonical discard identity was not persisted");
            return readLocked();
        }
    }

    void markTerminal(String sessionId) {
        synchronized (LOCK) {
            requireLocked(sessionId);
            commit(
                    preferences.edit().putBoolean(KEY_TERMINAL, true),
                    "Canonical terminal state was not persisted");
        }
    }

    void clearIfTerminal(int activityId) {
        synchronized (LOCK) {
            State state = readLocked();
            if (state != null && state.activityId == activityId && state.terminal) {
                clearSessionLocked();
            }
        }
    }

    private State readLocked() {
        String sessionId = preferences.getString(KEY_SESSION_ID, null);
        if (sessionId == null) {
            return null;
        }
        return new State(
                sessionId,
                preferences.getLong(KEY_LEASE, 0L),
                preferences.getInt(KEY_ACTIVITY_ID, 0),
                preferences.getLong(KEY_STARTED_AT, 0L),
                preferences.getString(KEY_TIME_ZONE, null),
                preferences.getInt(KEY_UTC_OFFSET, 0),
                preferences.getString(KEY_BOOT_ID, null),
                preferences.contains(KEY_MONOTONIC_ANCHOR)
                        ? preferences.getLong(KEY_MONOTONIC_ANCHOR, 0L)
                        : null,
                preferences.getLong(KEY_ACCUMULATED_AT_START, 0L),
                preferences.getLong(KEY_NEXT_SEQUENCE, 1L),
                preferences.getString(KEY_LIFECYCLE, "RECORDING"),
                preferences.contains(KEY_OPEN_INTERVAL_ELAPSED)
                        ? preferences.getLong(KEY_OPEN_INTERVAL_ELAPSED, 0L)
                        : null,
                preferences.contains(KEY_OPEN_INTERVAL_ACTIVE)
                        ? preferences.getLong(KEY_OPEN_INTERVAL_ACTIVE, 0L)
                        : null,
                decodeIntervals(preferences.getString(KEY_INTERVALS, "")),
                preferences.getString(KEY_COMPLETION_TRANSFER, null),
                preferences.contains(KEY_COMPLETION_REVISION)
                        ? preferences.getLong(KEY_COMPLETION_REVISION, 0L)
                        : null,
                preferences.getString(KEY_DISCARD_TRANSFER, null),
                preferences.contains(KEY_DISCARD_REVISION)
                        ? preferences.getLong(KEY_DISCARD_REVISION, 0L)
                        : null,
                preferences.getBoolean(KEY_TERMINAL, false));
    }

    private State requireLocked(String sessionId) {
        State state = readLocked();
        if (state == null || !state.sessionId.equals(sessionId)) {
            throw new IllegalStateException("Canonical session ownership is stale");
        }
        if (state.leaseToken == 0L) {
            throw new IllegalStateException("Canonical session lease is missing");
        }
        return state;
    }

    private void clearSessionLocked() {
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : new String[]{
                KEY_SESSION_ID,
                KEY_LEASE,
                KEY_ACTIVITY_ID,
                KEY_STARTED_AT,
                KEY_TIME_ZONE,
                KEY_UTC_OFFSET,
                KEY_BOOT_ID,
                KEY_MONOTONIC_ANCHOR,
                KEY_ACCUMULATED_AT_START,
                KEY_NEXT_SEQUENCE,
                KEY_LIFECYCLE,
                KEY_OPEN_INTERVAL_ELAPSED,
                KEY_OPEN_INTERVAL_ACTIVE,
                KEY_INTERVALS,
                KEY_COMPLETION_TRANSFER,
                KEY_COMPLETION_REVISION,
                KEY_DISCARD_TRANSFER,
                KEY_DISCARD_REVISION,
                KEY_TERMINAL}) {
            editor.remove(key);
        }
        commit(editor, "Canonical session state was not cleared");
    }

    private static void commit(SharedPreferences.Editor editor, String message) {
        if (!editor.commit()) {
            throw new IllegalStateException(message);
        }
    }

    private static String encodeIntervals(List<Interval> intervals) {
        StringBuilder encoded = new StringBuilder();
        for (Interval interval : intervals) {
            if (encoded.length() > 0) {
                encoded.append(';');
            }
            encoded.append(interval.startElapsedMs)
                    .append(',')
                    .append(interval.endElapsedMs)
                    .append(',')
                    .append(interval.activeDurationMs);
        }
        return encoded.toString();
    }

    private static List<Interval> decodeIntervals(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return Collections.emptyList();
        }
        List<Interval> intervals = new ArrayList<>();
        for (String row : encoded.split(";")) {
            String[] values = row.split(",");
            if (values.length != 3) {
                throw new IllegalStateException("Canonical active intervals are corrupt");
            }
            try {
                intervals.add(new Interval(
                        Long.parseLong(values[0]),
                        Long.parseLong(values[1]),
                        Long.parseLong(values[2])));
            } catch (NumberFormatException error) {
                throw new IllegalStateException(
                        "Canonical active intervals are corrupt",
                        error);
            }
        }
        return Collections.unmodifiableList(intervals);
    }

    static final class State {
        final String sessionId;
        final long leaseToken;
        final int activityId;
        final long startedAtUtcMs;
        final String timeZoneId;
        final int utcOffsetMinutes;
        final String bootId;
        final Long monotonicAnchorMs;
        final long accumulatedActiveAtStartMs;
        final long nextSequence;
        final String lifecycle;
        final Long openIntervalElapsed;
        final Long openIntervalActive;
        final List<Interval> intervals;
        final String completionTransferId;
        final Long completionRevision;
        final String discardTransferId;
        final Long discardRevision;
        final boolean terminal;

        private State(
                String sessionId,
                long leaseToken,
                int activityId,
                long startedAtUtcMs,
                String timeZoneId,
                int utcOffsetMinutes,
                String bootId,
                Long monotonicAnchorMs,
                long accumulatedActiveAtStartMs,
                long nextSequence,
                String lifecycle,
                Long openIntervalElapsed,
                Long openIntervalActive,
                List<Interval> intervals,
                String completionTransferId,
                Long completionRevision,
                String discardTransferId,
                Long discardRevision,
                boolean terminal) {
            this.sessionId = sessionId;
            this.leaseToken = leaseToken;
            this.activityId = activityId;
            this.startedAtUtcMs = startedAtUtcMs;
            this.timeZoneId = timeZoneId;
            this.utcOffsetMinutes = utcOffsetMinutes;
            this.bootId = bootId;
            this.monotonicAnchorMs = monotonicAnchorMs;
            this.accumulatedActiveAtStartMs = accumulatedActiveAtStartMs;
            this.nextSequence = nextSequence;
            this.lifecycle = lifecycle;
            this.openIntervalElapsed = openIntervalElapsed;
            this.openIntervalActive = openIntervalActive;
            this.intervals = intervals;
            this.completionTransferId = completionTransferId;
            this.completionRevision = completionRevision;
            this.discardTransferId = discardTransferId;
            this.discardRevision = discardRevision;
            this.terminal = terminal;
        }
    }

    static final class Interval {
        final long startElapsedMs;
        final long endElapsedMs;
        final long activeDurationMs;

        private Interval(
                long startElapsedMs,
                long endElapsedMs,
                long activeDurationMs) {
            this.startElapsedMs = startElapsedMs;
            this.endElapsedMs = endElapsedMs;
            this.activeDurationMs = activeDurationMs;
        }
    }
}
