package com.long2know.sportlogger.services;

import com.long2know.utilities.models.SharedData;
import com.long2know.sportlogger.recording.RecordingSnapshot;

public interface ISportLoggerServiceClient {
    void onLoggerUpdate(SharedData data);

    void onRecordingStateChanged(RecordingSnapshot snapshot);

    void onLoggerError(String code, String message);
}
