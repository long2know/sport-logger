package com.long2know.sportlogger.recording;

import android.content.Context;
import android.content.SharedPreferences;

import com.long2know.utilities.models.SportActivity;

public final class CanonicalAuthorityRecordingStore implements RecordingStore {
    private static final Object LOCK = new Object();
    private static final String PREFERENCES_NAME = "sport_logger_recording_ids";
    private static final String KEY_LAST_ACTIVITY_ID = "last_activity_id";
    private static final int CANONICAL_ACTIVITY_ID_BASE = 1_000_000_000;

    private final SharedPreferences preferences;

    public CanonicalAuthorityRecordingStore(Context context) {
        this(context.getApplicationContext().getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE));
    }

    CanonicalAuthorityRecordingStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    @Override
    public int createActivity(long startedAtMillis) {
        synchronized (LOCK) {
            int current = preferences.getInt(
                    KEY_LAST_ACTIVITY_ID,
                    CANONICAL_ACTIVITY_ID_BASE);
            if (current == Integer.MAX_VALUE) {
                throw new IllegalStateException(
                        "Local recording activity identifiers are exhausted");
            }
            int next = current + 1;
            if (!preferences.edit().putInt(KEY_LAST_ACTIVITY_ID, next).commit()) {
                throw new IllegalStateException(
                        "Local recording activity identifier was not persisted");
            }
            return next;
        }
    }

    @Override
    public void writeSample(int activityId) {
        requireActivity(activityId);
    }

    @Override
    public void finishActivity(int activityId, long endedAtMillis) {
        requireActivity(activityId);
    }

    @Override
    public void deleteActivity(int activityId) {
        requireActivity(activityId);
    }

    @Override
    public SportActivity loadActivity(int activityId) {
        throw new IllegalStateException(
                "Canonical recordings do not produce legacy serialized exports");
    }

    private static void requireActivity(int activityId) {
        if (activityId <= 0) {
            throw new IllegalArgumentException(
                    "Local recording activity identifier must be positive");
        }
    }
}
