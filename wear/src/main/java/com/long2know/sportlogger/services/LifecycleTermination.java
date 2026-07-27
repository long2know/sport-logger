package com.long2know.sportlogger.services;

enum LifecycleTermination {
    TERMINATED,
    TERMINATED_WITH_FAILURE,
    TIMED_OUT,
    INTERRUPTED,
    FAILED;

    boolean succeeded() {
        return this == TERMINATED;
    }

    boolean quiesced() {
        return this == TERMINATED || this == TERMINATED_WITH_FAILURE;
    }
}
