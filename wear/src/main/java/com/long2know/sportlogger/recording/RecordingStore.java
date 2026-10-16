package com.long2know.sportlogger.recording;

import com.long2know.utilities.models.SportActivity;

public interface RecordingStore {
    int createActivity(long startedAtMillis) throws Exception;

    void writeSample(int activityId) throws Exception;

    void finishActivity(int activityId, long endedAtMillis) throws Exception;

    void deleteActivity(int activityId) throws Exception;

    SportActivity loadActivity(int activityId) throws Exception;
}
