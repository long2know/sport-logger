package com.long2know.sportlogger.services;

import java.util.Objects;

final class GpsRegistrationLifecycle<L, K> {
    static final int DEFAULT_MAX_RETRY_ATTEMPTS = 3;
    static final long DEFAULT_RETRY_DELAY_MILLIS = 250L;

    interface Platform<L, K> extends LocationRegistration.Backend<L> {
        K getEnvironmentKey();

        void registerProviderChanges(Runnable callback);

        void unregisterProviderChanges();
    }

    interface RetryScheduler {
        void schedule(Runnable callback, long delayMillis);

        void cancel(Runnable callback);
    }

    private final Platform<L, K> platform;
    private final RetryScheduler retryScheduler;
    private final LocationRegistration<L> locationRegistration;
    private final int maxRetryAttempts;
    private final long retryDelayMillis;
    private final Runnable providerChangesCallback = this::onEnvironmentChanged;

    private boolean started;
    private boolean receiverRegistered;
    private long generation;
    private K environmentKey;
    private int retryAttempts;
    private boolean retrySettled = true;
    private RetryTask pendingRetry;

    GpsRegistrationLifecycle(
            Platform<L, K> platform,
            RetryScheduler retryScheduler,
            LocationRegistration.StatusReporter statusReporter) {
        this(
                platform,
                retryScheduler,
                statusReporter,
                DEFAULT_MAX_RETRY_ATTEMPTS,
                DEFAULT_RETRY_DELAY_MILLIS);
    }

    GpsRegistrationLifecycle(
            Platform<L, K> platform,
            RetryScheduler retryScheduler,
            LocationRegistration.StatusReporter statusReporter,
            int maxRetryAttempts,
            long retryDelayMillis) {
        this.platform = Objects.requireNonNull(platform);
        this.retryScheduler = Objects.requireNonNull(retryScheduler);
        this.locationRegistration = new LocationRegistration<>(
                platform,
                Objects.requireNonNull(statusReporter));
        if (maxRetryAttempts < 0) {
            throw new IllegalArgumentException("maxRetryAttempts must not be negative");
        }
        if (retryDelayMillis < 0L) {
            throw new IllegalArgumentException("retryDelayMillis must not be negative");
        }
        this.maxRetryAttempts = maxRetryAttempts;
        this.retryDelayMillis = retryDelayMillis;
    }

    synchronized void start() {
        if (started) {
            return;
        }

        started = true;
        generation++;
        try {
            platform.registerProviderChanges(providerChangesCallback);
            receiverRegistered = true;
            LocationRegistration.Status status = locationRegistration.start();
            environmentKey = platform.getEnvironmentKey();
            resetRetryState(status);
        } catch (RuntimeException exception) {
            try {
                stop();
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    synchronized void stop() {
        if (!started
                && !receiverRegistered
                && pendingRetry == null
                && !locationRegistration.isRegistered()) {
            return;
        }

        started = false;
        generation++;
        RuntimeException failure = cancelPendingRetry();

        try {
            locationRegistration.stop();
        } catch (RuntimeException exception) {
            failure = appendFailure(failure, exception);
        }

        if (receiverRegistered) {
            receiverRegistered = false;
            try {
                platform.unregisterProviderChanges();
            } catch (RuntimeException exception) {
                failure = appendFailure(failure, exception);
            }
        }

        environmentKey = null;
        retryAttempts = 0;
        retrySettled = true;
        if (failure != null) {
            throw failure;
        }
    }

    synchronized void onEnvironmentChanged() {
        if (!started) {
            return;
        }

        K nextEnvironmentKey = platform.getEnvironmentKey();
        if (!Objects.equals(environmentKey, nextEnvironmentKey)) {
            generation++;
            RuntimeException failure = cancelPendingRetry();
            environmentKey = nextEnvironmentKey;
            retryAttempts = 0;
            retrySettled = false;
            if (failure != null) {
                throw failure;
            }
        }

        if (!retrySettled && pendingRetry == null && retryAttempts < maxRetryAttempts) {
            scheduleRetry();
        }
    }

    synchronized boolean isStarted() {
        return started;
    }

    synchronized boolean isReceiverRegistered() {
        return receiverRegistered;
    }

    synchronized boolean isLocationRegistered() {
        return locationRegistration.isRegistered();
    }

    synchronized boolean hasPendingRetry() {
        return pendingRetry != null;
    }

    synchronized int getRetryAttempts() {
        return retryAttempts;
    }

    synchronized LocationRegistration.Status getStatus() {
        return locationRegistration.getStatus();
    }

    private void resetRetryState(LocationRegistration.Status status) {
        retryAttempts = 0;
        retrySettled = !isRetryable(status);
        if (!retrySettled && maxRetryAttempts > 0) {
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        RetryTask retryTask = new RetryTask(generation);
        pendingRetry = retryTask;
        try {
            retryScheduler.schedule(retryTask, retryDelayMillis);
        } catch (RuntimeException exception) {
            pendingRetry = null;
            throw exception;
        }
    }

    private synchronized void runRetry(RetryTask retryTask) {
        if (!started
                || retryTask.generation != generation
                || pendingRetry != retryTask) {
            return;
        }

        pendingRetry = null;
        retryAttempts++;
        try {
            LocationRegistration.Status status = locationRegistration.start();
            K nextEnvironmentKey = platform.getEnvironmentKey();
            if (!Objects.equals(environmentKey, nextEnvironmentKey)) {
                generation++;
                environmentKey = nextEnvironmentKey;
                retryAttempts = 0;
                retrySettled = false;
                if (maxRetryAttempts > 0) {
                    scheduleRetry();
                }
                return;
            }

            retrySettled = !isRetryable(status) || retryAttempts >= maxRetryAttempts;
            if (!retrySettled) {
                scheduleRetry();
            }
        } catch (RuntimeException exception) {
            try {
                stop();
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    private RuntimeException cancelPendingRetry() {
        RetryTask retryTask = pendingRetry;
        pendingRetry = null;
        if (retryTask == null) {
            return null;
        }
        try {
            retryScheduler.cancel(retryTask);
            return null;
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    private static boolean isRetryable(LocationRegistration.Status status) {
        return status == LocationRegistration.Status.NO_PROVIDER
                || status == LocationRegistration.Status.LOCATION_DISABLED;
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

    private final class RetryTask implements Runnable {
        private final long generation;

        private RetryTask(long generation) {
            this.generation = generation;
        }

        @Override
        public void run() {
            runRetry(this);
        }
    }
}
