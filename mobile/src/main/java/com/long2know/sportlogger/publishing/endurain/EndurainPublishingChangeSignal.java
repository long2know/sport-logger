package com.long2know.sportlogger.publishing.endurain;

import java.util.concurrent.Executor;

public interface EndurainPublishingChangeSignal {
    void signal();

    AutoCloseable observe(Executor executor, Runnable listener);
}
