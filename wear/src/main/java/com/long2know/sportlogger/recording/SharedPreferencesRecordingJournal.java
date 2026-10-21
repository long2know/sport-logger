package com.long2know.sportlogger.recording;

import android.content.Context;
import android.content.SharedPreferences;

import com.long2know.utilities.contract.proto.ActivityType;

import java.util.IdentityHashMap;
import java.util.Map;

public final class SharedPreferencesRecordingJournal implements RecordingJournal {
    private static final Object DURABLE_LOCK = new Object();
    private static final Object OWNERSHIP_REGISTRY_LOCK = new Object();
    private static final CallbackOwnership PRODUCTION_CALLBACK_OWNERSHIP =
            new CallbackOwnership();
    private static final Map<SharedPreferences, CallbackOwnership> CALLBACK_OWNERSHIPS =
            new IdentityHashMap<>();
    private static final String PREFERENCES_NAME = "sport_logger_recording";
    private static final String KEY_LEASE = "lease";
    private static final String KEY_STATE = "state";
    private static final String KEY_ACTIVITY_ID = "activity_id";
    private static final String KEY_ACTIVITY_TYPE = "activity_type";
    private static final String KEY_AUTHORITY = "authority";
    private static final String KEY_STARTED_AT = "started_at";
    private static final String KEY_ACTIVE_SINCE = "active_since";
    private static final String KEY_ACTIVE_SINCE_ELAPSED = "active_since_elapsed";
    private static final String KEY_ELAPSED_SINCE = "elapsed_since_elapsed";
    private static final String KEY_ACCUMULATED = "accumulated";
    private static final String KEY_ACCUMULATED_ELAPSED = "accumulated_elapsed";
    private static final String KEY_ENDED_AT = "ended_at";
    private static final String KEY_BOOT_COUNT = "boot_count";
    private static final String KEY_CURRENT_LAP_INDEX = "current_lap_index";
    private static final String KEY_LAST_LAP_ELAPSED = "last_lap_elapsed";
    private static final String KEY_LAST_LAP_ACTIVE = "last_lap_active";
    private static final String KEY_ERROR_CODE = "error_code";
    private static final String KEY_ERROR_MESSAGE = "error_message";

    private final SharedPreferences preferences;
    private final CallbackOwnership callbackOwnership;

    public SharedPreferencesRecordingJournal(Context context) {
        this(
                context.getApplicationContext().getSharedPreferences(
                        PREFERENCES_NAME,
                        Context.MODE_PRIVATE),
                PRODUCTION_CALLBACK_OWNERSHIP);
    }

    SharedPreferencesRecordingJournal(SharedPreferences preferences) {
        this(preferences, callbackOwnershipFor(preferences));
    }

    private SharedPreferencesRecordingJournal(
            SharedPreferences preferences,
            CallbackOwnership callbackOwnership) {
        this.preferences = preferences;
        this.callbackOwnership = callbackOwnership;
    }

    @Override
    public long acquireLease() {
        long lease;
        synchronized (DURABLE_LOCK) {
            lease = preferences.getLong(KEY_LEASE, 0L) + 1L;
            if (!preferences.edit().putLong(KEY_LEASE, lease).commit()) {
                throw new IllegalStateException("Recording lease was not persisted");
            }
        }
        callbackOwnership.activate(lease);
        return lease;
    }

    @Override
    public PersistentRecording read() {
        synchronized (DURABLE_LOCK) {
            String storedState =
                    preferences.getString(KEY_STATE, RecordingState.IDLE.name());
            RecordingState state;
            try {
                state = RecordingState.valueOf(storedState);
            } catch (RuntimeException error) {
                throw new IllegalStateException(
                        "Unknown durable recording state: " + storedState,
                        error);
            }

            int activityId = preferences.getInt(KEY_ACTIVITY_ID, 0);
            if (state != RecordingState.IDLE
                    && state != RecordingState.ERROR
                    && activityId <= 0) {
                throw new IllegalStateException(
                        "Active recording state has no activity identifier");
            }
            ActivityType activityType;
            try {
                activityType = ActivityType.valueOf(preferences.getString(
                        KEY_ACTIVITY_TYPE,
                        activityId > 0
                                ? ActivityType.ACTIVITY_TYPE_RUNNING.name()
                                : ActivityType.ACTIVITY_TYPE_UNSPECIFIED.name()));
            } catch (RuntimeException error) {
                throw new IllegalStateException(
                        "Unknown durable recording activity type",
                        error);
            }
            if (activityId > 0
                    && !RecordingActivityTypePolicy.isSupported(activityType)) {
                throw new IllegalStateException(
                        "Active recording has an unsupported activity type");
            }
            RecordingAuthority authority;
            try {
                authority = RecordingAuthority.valueOf(preferences.getString(
                        KEY_AUTHORITY,
                        RecordingAuthority.LEGACY_COMPATIBILITY.name()));
            } catch (RuntimeException error) {
                throw new IllegalStateException(
                        "Unknown durable recording authority",
                        error);
            }

            RecordingError recordingError = null;
            String errorCode = preferences.getString(KEY_ERROR_CODE, null);
            if (errorCode != null) {
                try {
                    recordingError = new RecordingError(
                            RecordingError.Code.valueOf(errorCode),
                            preferences.getString(KEY_ERROR_MESSAGE, ""));
                } catch (RuntimeException error) {
                    throw new IllegalStateException(
                            "Unknown durable recording error: " + errorCode,
                            error);
                }
            }

            return new PersistentRecording(
                    state,
                    activityId,
                    activityType,
                    authority,
                    preferences.getLong(KEY_STARTED_AT, 0L),
                    preferences.getLong(KEY_ACTIVE_SINCE, 0L),
                    preferences.getLong(KEY_ACTIVE_SINCE_ELAPSED, 0L),
                    preferences.getLong(
                            KEY_ELAPSED_SINCE,
                            preferences.getLong(KEY_ACTIVE_SINCE_ELAPSED, 0L)),
                    preferences.getLong(KEY_ACCUMULATED, 0L),
                    preferences.getLong(
                            KEY_ACCUMULATED_ELAPSED,
                            preferences.getLong(KEY_ACCUMULATED, 0L)),
                    preferences.getLong(KEY_ENDED_AT, 0L),
                    preferences.getInt(KEY_BOOT_COUNT, -1),
                    recordingError,
                    preferences.getInt(
                            KEY_CURRENT_LAP_INDEX,
                            activityId > 0 ? 1 : 0),
                    preferences.contains(KEY_LAST_LAP_ELAPSED)
                            ? preferences.getLong(KEY_LAST_LAP_ELAPSED, 0L)
                            : null,
                    preferences.contains(KEY_LAST_LAP_ACTIVE)
                            ? preferences.getLong(KEY_LAST_LAP_ACTIVE, 0L)
                            : null);
        }
    }

    @Override
    public void write(long lease, PersistentRecording recording) {
        synchronized (DURABLE_LOCK) {
            if (preferences.getLong(KEY_LEASE, 0L) != lease) {
                throw new IllegalStateException("Recording lease is stale");
            }

            SharedPreferences.Editor editor = preferences.edit()
                    .putString(KEY_STATE, recording.getState().name())
                    .putInt(KEY_ACTIVITY_ID, recording.getActivityId())
                    .putString(
                            KEY_ACTIVITY_TYPE,
                            recording.getActivityType().name())
                    .putString(KEY_AUTHORITY, recording.getAuthority().name())
                    .putLong(KEY_STARTED_AT, recording.getStartedAtMillis())
                    .putLong(KEY_ACTIVE_SINCE, recording.getActiveSinceMillis())
                    .putLong(
                            KEY_ACTIVE_SINCE_ELAPSED,
                            recording.getActiveSinceElapsedRealtimeMillis())
                    .putLong(
                            KEY_ELAPSED_SINCE,
                            recording.getElapsedSinceElapsedRealtimeMillis())
                    .putLong(KEY_ACCUMULATED, recording.getAccumulatedMillis())
                    .putLong(
                            KEY_ACCUMULATED_ELAPSED,
                            recording.getAccumulatedElapsedMillis())
                    .putLong(KEY_ENDED_AT, recording.getEndedAtMillis())
                    .putInt(KEY_BOOT_COUNT, recording.getBootCount())
                    .putInt(
                            KEY_CURRENT_LAP_INDEX,
                            recording.getCurrentLapIndex());
            if (recording.getLastLapElapsedDurationMillis() == null) {
                editor.remove(KEY_LAST_LAP_ELAPSED);
            } else {
                editor.putLong(
                        KEY_LAST_LAP_ELAPSED,
                        recording.getLastLapElapsedDurationMillis());
            }
            if (recording.getLastLapActiveDurationMillis() == null) {
                editor.remove(KEY_LAST_LAP_ACTIVE);
            } else {
                editor.putLong(
                        KEY_LAST_LAP_ACTIVE,
                        recording.getLastLapActiveDurationMillis());
            }
            RecordingError error = recording.getError();
            if (error == null) {
                editor.remove(KEY_ERROR_CODE).remove(KEY_ERROR_MESSAGE);
            } else {
                editor.putString(KEY_ERROR_CODE, error.getCode().name())
                        .putString(KEY_ERROR_MESSAGE, error.getMessage());
            }
            if (!editor.commit()) {
                throw new IllegalStateException("Recording state was not persisted");
            }
        }
    }

    @Override
    public boolean isCurrent(long lease) {
        synchronized (DURABLE_LOCK) {
            return preferences.getLong(KEY_LEASE, 0L) == lease;
        }
    }

    @Override
    public boolean runIfCurrent(long lease, Runnable callback) {
        return callbackOwnership.runIfCurrent(lease, callback);
    }

    private static CallbackOwnership callbackOwnershipFor(SharedPreferences preferences) {
        synchronized (OWNERSHIP_REGISTRY_LOCK) {
            CallbackOwnership ownership = CALLBACK_OWNERSHIPS.get(preferences);
            if (ownership == null) {
                ownership = new CallbackOwnership();
                CALLBACK_OWNERSHIPS.put(preferences, ownership);
            }
            return ownership;
        }
    }

    private static final class CallbackOwnership {
        private long currentLease;

        private synchronized void activate(long lease) {
            if (lease > currentLease) {
                currentLease = lease;
            }
        }

        private synchronized boolean runIfCurrent(long lease, Runnable callback) {
            if (currentLease != lease) {
                return false;
            }
            callback.run();
            return true;
        }
    }
}
