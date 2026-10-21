package com.long2know.sportlogger.services;

import androidx.annotation.Nullable;

import com.long2know.sportlogger.acquisition.RouteStatus;
import com.long2know.sportlogger.acquisition.healthservices.HealthServicesExerciseSnapshot;
import com.long2know.utilities.models.SharedData;
import com.long2know.sportlogger.recording.RecordingSnapshot;
import com.long2know.sportlogger.recording.CompletedRecordingSnapshot;

public interface ISportLoggerServiceClient {
    void onLoggerUpdate(SharedData data);

    void onRecordingStateChanged(RecordingSnapshot snapshot);

    void onRouteStatusChanged(RouteStatus status);

    void onAcquisitionStateChanged(HealthServicesExerciseSnapshot snapshot);

    void onLatestCompletedChanged(@Nullable CompletedRecordingSnapshot snapshot);

    void onLoggerError(String code, String message);
}
