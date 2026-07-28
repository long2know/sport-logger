package com.long2know.sportlogger.services;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

final class ListenerThreadOwner {
    interface ManagedListener extends Runnable {
        void requestStop();

        boolean awaitStopped(long timeout, TimeUnit unit) throws InterruptedException;
    }

    interface ThreadFactory {
        Thread create(Runnable runnable, String name);
    }

    private final ThreadFactory threadFactory;
    private final String threadName;
    private final long stopTimeoutMillis;

    private ManagedListener activeListener;
    private Thread activeThread;

    ListenerThreadOwner(String threadName, long stopTimeoutMillis) {
        this(Thread::new, threadName, stopTimeoutMillis);
    }

    ListenerThreadOwner(
            ThreadFactory threadFactory,
            String threadName,
            long stopTimeoutMillis) {
        this.threadFactory = Objects.requireNonNull(threadFactory);
        this.threadName = Objects.requireNonNull(threadName);
        if (stopTimeoutMillis <= 0L) {
            throw new IllegalArgumentException("stopTimeoutMillis must be positive");
        }
        this.stopTimeoutMillis = stopTimeoutMillis;
    }

    synchronized void replace(ManagedListener listener) {
        Objects.requireNonNull(listener);
        stop();

        Thread thread = Objects.requireNonNull(threadFactory.create(listener, threadName));
        activeListener = listener;
        activeThread = thread;
        try {
            thread.start();
        } catch (RuntimeException exception) {
            activeListener = null;
            activeThread = null;
            listener.requestStop();
            throw exception;
        }
    }

    synchronized void stop() {
        ManagedListener listener = activeListener;
        Thread thread = activeThread;
        if (listener == null) {
            return;
        }

        RuntimeException failure = null;
        try {
            listener.requestStop();
        } catch (RuntimeException exception) {
            failure = exception;
        }

        try {
            if (!listener.awaitStopped(stopTimeoutMillis, TimeUnit.MILLISECONDS)) {
                thread.interrupt();
                if (!listener.awaitStopped(stopTimeoutMillis, TimeUnit.MILLISECONDS)) {
                    failure = appendFailure(
                            failure,
                            new IllegalStateException(
                                    "Listener did not stop within "
                                            + (stopTimeoutMillis * 2L)
                                            + " ms"));
                }
            }
            thread.join(stopTimeoutMillis);
            if (thread.isAlive()) {
                failure = appendFailure(
                        failure,
                        new IllegalStateException("Listener thread is still alive after teardown"));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failure = appendFailure(
                    failure,
                    new IllegalStateException("Interrupted while stopping listener", exception));
        }

        if (!thread.isAlive() && activeListener == listener) {
            activeListener = null;
            activeThread = null;
        }
        if (failure != null) {
            throw failure;
        }
    }

    synchronized ManagedListener getActiveListener() {
        return activeListener;
    }

    synchronized Thread getActiveThread() {
        return activeThread;
    }

    private static RuntimeException appendFailure(
            RuntimeException failure,
            RuntimeException nextFailure) {
        if (failure == null) {
            return nextFailure;
        }
        failure.addSuppressed(nextFailure);
        return failure;
    }
}
