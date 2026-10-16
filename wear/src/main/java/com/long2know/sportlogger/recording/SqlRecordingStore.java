package com.long2know.sportlogger.recording;

import android.content.Context;

import com.long2know.utilities.data_access.SqlLogger;
import com.long2know.utilities.models.SportActivity;

import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SqlRecordingStore implements RecordingStore {
    private final Context context;
    private final AtomicBoolean initialized = new AtomicBoolean();

    public SqlRecordingStore(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public int createActivity(long startedAtMillis) {
        ensureInitialized();
        return SqlLogger.createActivity(context, new Date(startedAtMillis));
    }

    @Override
    public void writeSample(int activityId) {
        ensureInitialized();
        try (SqlLogger logger = new SqlLogger(context)) {
            logger.writeData(activityId);
        }
    }

    @Override
    public void finishActivity(int activityId, long endedAtMillis) {
        ensureInitialized();
        try (SqlLogger logger = new SqlLogger(context)) {
            logger.completeActivity(activityId, new Date(endedAtMillis));
        }
    }

    @Override
    public void deleteActivity(int activityId) {
        ensureInitialized();
        try (SqlLogger logger = new SqlLogger(context)) {
            logger.deleteActivity(activityId);
        }
    }

    @Override
    public SportActivity loadActivity(int activityId) {
        ensureInitialized();
        try (SqlLogger logger = new SqlLogger(context)) {
            SportActivity activity = logger.getSportActivity(activityId);
            activity.SportTrackPoints = logger.getTrackPointsByActivity(activityId);
            return activity;
        }
    }

    private void ensureInitialized() {
        if (initialized.compareAndSet(false, true)) {
            try {
                SqlLogger.initDatabase(context);
            } catch (RuntimeException error) {
                initialized.set(false);
                throw error;
            }
        }
    }
}
