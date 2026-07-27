package com.long2know.sportlogger.services;

interface BoundedLifecycle {
    LifecycleTermination start(long timeoutMillis);

    LifecycleTermination shutdown(long timeoutMillis);
}
