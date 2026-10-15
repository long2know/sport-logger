package com.long2know.sportlogger.recording;

import android.content.Context;
import android.content.SharedPreferences;

public final class SharedPreferencesRecordingJournal implements RecordingJournal {
    private static final Object LEASE_LOCK = new Object();
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

    public SharedPreferencesRecordingJournal(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE);
    }

    @Override
    public long acquireLease() {
        synchronized (LEASE_LOCK) {
            long lease = preferences.getLong(KEY_LEASE, 0L) + 1L;
            if (!preferences.edit().putLong(KEY_LEASE, lease).commit()) {
                throw new IllegalStateException("Recording lease was not persisted");
            }
            return lease;
        }
    }

    @Override
    public PersistentRecording read() {
        String storedState = preferences.getString(KEY_STATE, RecordingState.IDLE.name());
        RecordingState state;
        try {
            state = RecordingState.valueOf(storedState);
        } catch (RuntimeException error) {
            throw new IllegalStateException("Unknown durable recording state: " + storedState, error);
        }

        int activityId = preferences.getInt(KEY_ACTIVITY_ID, 0);
        if (state != RecordingState.IDLE && state != RecordingState.ERROR && activityId <= 0) {
            throw new IllegalStateException("Active recording state has no activity identifier");
        }

        RecordingError recordingError = null;
        String errorCode = preferences.getString(KEY_ERROR_CODE, null);
        if (errorCode != null) {
            try {
                recordingError = new RecordingError(
                        RecordingError.Code.valueOf(errorCode),
                        preferences.getString(KEY_ERROR_MESSAGE, ""));
            } catch (RuntimeException error) {
                throw new IllegalStateException("Unknown durable recording error: " + errorCode, error);
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

    @Override
    public void write(long lease, PersistentRecording recording) {
        if (!isCurrent(lease)) {
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

    @Override
    public boolean isCurrent(long lease) {
        return preferences.getLong(KEY_LEASE, 0L) == lease;
    }
}
