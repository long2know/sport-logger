package com.long2know.sportlogger.services;

interface ManagedListener extends Runnable {
    void requestShutdown();

    boolean awaitStopped(long timeoutMillis) throws InterruptedException;

    boolean awaitReady(long timeoutMillis) throws InterruptedException;
}
