package com.long2know.sportlogger.services;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class ListenerGroup implements BoundedLifecycle {
    private final AtomicBoolean _active;
    private final ManagedListener _sensorListener;
    private final ManagedListener _locationListener;
    private final Thread _sensorThread;
    private final Thread _locationThread;
    private boolean _started;

    ListenerGroup(
            AtomicBoolean active,
            ManagedListener sensorListener,
            ManagedListener locationListener,
            String threadNamePrefix) {
        _active = active;
        _sensorListener = sensorListener;
        _locationListener = locationListener;
        _sensorThread = new Thread(_sensorListener, threadNamePrefix + "-sensors");
        _locationThread = new Thread(_locationListener, threadNamePrefix + "-location");
    }

    @Override
    public synchronized LifecycleTermination start(long timeoutMillis) {
        if (_started) {
            return LifecycleTermination.TERMINATED;
        }
        _started = true;
        _active.set(true);
        _sensorThread.start();
        _locationThread.start();
        long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        try {
            boolean sensorsReady = awaitReady(_sensorListener, deadlineNanos);
            boolean locationReady = awaitReady(_locationListener, deadlineNanos);
            if (!sensorsReady || !locationReady) {
                shutdown(remainingMillis(deadlineNanos));
                return LifecycleTermination.TIMED_OUT;
            }
            return LifecycleTermination.TERMINATED;
        } catch (InterruptedException exception) {
            shutdown(remainingMillis(deadlineNanos));
            Thread.currentThread().interrupt();
            return LifecycleTermination.INTERRUPTED;
        } catch (RuntimeException exception) {
            shutdown(remainingMillis(deadlineNanos));
            return LifecycleTermination.FAILED;
        }
    }

    @Override
    public LifecycleTermination shutdown(long timeoutMillis) {
        _active.set(false);
        _sensorListener.requestShutdown();
        _locationListener.requestShutdown();

        long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        try {
            boolean sensorsStopped = await(_sensorListener, deadlineNanos);
            boolean locationStopped = await(_locationListener, deadlineNanos);
            if (!sensorsStopped || !locationStopped) {
                _sensorThread.interrupt();
                _locationThread.interrupt();
                return LifecycleTermination.TIMED_OUT;
            }
            return LifecycleTermination.TERMINATED;
        } catch (InterruptedException exception) {
            _sensorThread.interrupt();
            _locationThread.interrupt();
            Thread.currentThread().interrupt();
            return LifecycleTermination.INTERRUPTED;
        } catch (RuntimeException exception) {
            _sensorThread.interrupt();
            _locationThread.interrupt();
            return LifecycleTermination.FAILED;
        }
    }

    boolean isActive() {
        return _active.get();
    }

    private static boolean await(ManagedListener listener, long deadlineNanos)
            throws InterruptedException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            return listener.awaitStopped(0L);
        }
        long remainingMillis = Math.max(
                1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
        return listener.awaitStopped(remainingMillis);
    }

    private static boolean awaitReady(
            ManagedListener listener, long deadlineNanos)
            throws InterruptedException {
        return listener.awaitReady(remainingMillis(deadlineNanos));
    }

    private static long remainingMillis(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        return remainingNanos <= 0L
                ? 0L
                : Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
    }
}
