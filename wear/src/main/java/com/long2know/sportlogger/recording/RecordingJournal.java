package com.long2know.sportlogger.recording;

public interface RecordingJournal {
    long acquireLease() throws Exception;

    PersistentRecording read() throws Exception;

    void write(long lease, PersistentRecording recording) throws Exception;

    boolean isCurrent(long lease);
}
