package com.long2know.sportlogger.publishing.endurain.scheduler;

public interface EndurainPublishingSchedule {
    void schedulePublishAt(long notBeforeUtcMs);

    void scheduleRecoveryAt(long notBeforeUtcMs);
}
