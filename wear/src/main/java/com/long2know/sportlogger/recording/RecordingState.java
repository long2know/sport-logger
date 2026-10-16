package com.long2know.sportlogger.recording;

public enum RecordingState {
    IDLE,
    STARTING,
    RECORDING,
    PAUSED,
    STOPPING,
    DISCARDING,
    EXPORT_PENDING,
    EXPORT_QUEUED,
    ERROR
}
