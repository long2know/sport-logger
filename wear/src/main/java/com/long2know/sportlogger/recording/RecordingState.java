package com.long2know.sportlogger.recording;

public enum RecordingState {
    IDLE,
    STARTING,
    RECORDING,
    PAUSED,
    INTERRUPTED,
    STOPPING,
    DISCARDING,
    EXPORT_PENDING,
    EXPORT_QUEUED,
    ERROR
}
