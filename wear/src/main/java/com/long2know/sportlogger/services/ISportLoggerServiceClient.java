package com.long2know.sportlogger.services;

import com.long2know.sportlogger.SharedData;

// Simple interface to let the service send updates to an activity directly
public interface ISportLoggerServiceClient {
    void onLoggerUpdate(SharedData data);
}
