package com.long2know.sportlogger.services;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

final class RecordingPersistenceBarrier {
    interface Operation<T> {
        T run();
    }

    enum ActivationStatus {
        ACTIVATED,
        STALE,
        INTERRUPTED,
        FAILED
    }

    static final class Epoch {
        private final long _value;

        private Epoch(long value) {
            if (value <= 0L) {
                throw new IllegalArgumentException(
                        "Persistence epochs must be positive.");
            }
            _value = value;
        }

        long getValue() {
            return _value;
        }
    }

    static final class Activation<T> {
        private final ActivationStatus _status;
        private final T _value;
        private final RuntimeException _failure;

        private Activation(
                ActivationStatus status, T value, RuntimeException failure) {
            _status = status;
            _value = value;
            _failure = failure;
        }

        ActivationStatus getStatus() {
            return _status;
        }

        T getValue() {
            return _value;
        }

        RuntimeException getFailure() {
            return _failure;
        }

        boolean activated() {
            return _status == ActivationStatus.ACTIVATED;
        }
    }

    private final AtomicLong _nextEpoch = new AtomicLong();
    private final ReentrantLock _persistenceLock = new ReentrantLock(true);
    private volatile long _activeEpoch;

    Epoch reserveEpoch() {
        while (true) {
            long current = _nextEpoch.get();
            if (current == Long.MAX_VALUE) {
                throw new IllegalStateException(
                        "Persistence epoch space is exhausted.");
            }
            long next = current + 1L;
            if (_nextEpoch.compareAndSet(current, next)) {
                return new Epoch(next);
            }
        }
    }

    <T> Activation<T> activate(Epoch epoch, Operation<T> operation) {
        if (epoch == null || operation == null) {
            return new Activation<>(ActivationStatus.FAILED, null, null);
        }
        try {
            _persistenceLock.lockInterruptibly();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Activation<>(
                    ActivationStatus.INTERRUPTED, null, null);
        }
        try {
            if (epoch.getValue() <= _activeEpoch) {
                return new Activation<>(ActivationStatus.STALE, null, null);
            }
            _activeEpoch = epoch.getValue();
            try {
                return new Activation<>(
                        ActivationStatus.ACTIVATED, operation.run(), null);
            } catch (RuntimeException exception) {
                return new Activation<>(
                        ActivationStatus.FAILED, null, exception);
            }
        } finally {
            _persistenceLock.unlock();
        }
    }

    <T> T read(Epoch epoch, T staleValue, Operation<T> operation) {
        if (operation == null) {
            return staleValue;
        }
        _persistenceLock.lock();
        try {
            if (!isActive(epoch)) {
                return staleValue;
            }
            return operation.run();
        } finally {
            _persistenceLock.unlock();
        }
    }

    boolean write(Epoch epoch, Operation<Boolean> operation) {
        if (operation == null) {
            return false;
        }
        _persistenceLock.lock();
        try {
            return isActive(epoch) && Boolean.TRUE.equals(operation.run());
        } finally {
            _persistenceLock.unlock();
        }
    }

    boolean isActive(Epoch epoch) {
        return epoch != null && epoch.getValue() == _activeEpoch;
    }
}
