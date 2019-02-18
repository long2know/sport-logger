package com.long2know.sportlogger.services;

import com.long2know.utilities.models.SharedData;

// Simple interface to let the service send updates to an activity directly
public interface ISportLoggerServiceClient {
    void onLoggerUpdate(SharedData data);
}
