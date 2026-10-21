package com.long2know.sportlogger.acquisition.healthservices;

import android.content.Context;
import android.content.SharedPreferences;

final class SharedPreferencesHealthServicesSessionJournal
        implements HealthServicesSessionJournal {
    private static final String PREFERENCES_NAME = "sport_logger_health_services";
    private static final String KEY_ACTIVE_ACTIVITY_ID = "active_activity_id";

    private final SharedPreferences preferences;

    SharedPreferencesHealthServicesSessionJournal(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE);
    }

    @Override
    public int activeActivityId() {
        return preferences.getInt(KEY_ACTIVE_ACTIVITY_ID, 0);
    }

    @Override
    public void markActive(int activityId) {
        if (activityId <= 0
                || !preferences.edit()
                .putInt(KEY_ACTIVE_ACTIVITY_ID, activityId)
                .commit()) {
            throw new IllegalStateException(
                    "Health Services ownership state was not persisted");
        }
    }

    @Override
    public void clear() {
        if (!preferences.edit().remove(KEY_ACTIVE_ACTIVITY_ID).commit()) {
            throw new IllegalStateException(
                    "Health Services ownership state was not cleared");
        }
    }
}
