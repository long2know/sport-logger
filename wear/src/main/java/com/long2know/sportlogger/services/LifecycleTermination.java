package com.long2know.sportlogger.services;

enum LifecycleTermination {
    TERMINATED,
    TIMED_OUT,
    INTERRUPTED,
    FAILED;

    boolean succeeded() {
        return this == TERMINATED;
    }
}
