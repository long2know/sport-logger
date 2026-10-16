package com.long2know.sportlogger.recording;

import android.content.Context;
import android.content.SharedPreferences;

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
    private static final String KEY_STARTED_AT = "started_at";
    private static final String KEY_ACTIVE_SINCE = "active_since";
    private static final String KEY_ACCUMULATED = "accumulated";
    private static final String KEY_ENDED_AT = "ended_at";
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
                    preferences.getLong(KEY_STARTED_AT, 0L),
                    preferences.getLong(KEY_ACTIVE_SINCE, 0L),
                    preferences.getLong(KEY_ACCUMULATED, 0L),
                    preferences.getLong(KEY_ENDED_AT, 0L),
                    recordingError);
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
                    .putLong(KEY_STARTED_AT, recording.getStartedAtMillis())
                    .putLong(KEY_ACTIVE_SINCE, recording.getActiveSinceMillis())
                    .putLong(KEY_ACCUMULATED, recording.getAccumulatedMillis())
                    .putLong(KEY_ENDED_AT, recording.getEndedAtMillis());
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
