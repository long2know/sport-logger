package com.long2know.sportlogger;

final class CancellableCallbackLoop {
    interface Scheduler {
        void postDelayed(Runnable callback, long delayMillis);

        void removeCallbacks(Runnable callback);
    }

    private final Scheduler _scheduler;
    private final Runnable _tick;
    private final long _delayMillis;
    private Runnable _scheduledCallback;
    private long _generation;
    private boolean _running;

    CancellableCallbackLoop(Scheduler scheduler, Runnable tick, long delayMillis) {
        _scheduler = scheduler;
        _tick = tick;
        _delayMillis = delayMillis;
    }

    synchronized void start() {
        if (_running) {
            return;
        }
        _running = true;
        final long generation = ++_generation;
        _scheduledCallback = new Runnable() {
            @Override
            public void run() {
                runTick(generation);
            }
        };
        _scheduler.postDelayed(_scheduledCallback, _delayMillis);
    }

    synchronized void stop() {
        _running = false;
        _generation++;
        if (_scheduledCallback != null) {
            _scheduler.removeCallbacks(_scheduledCallback);
            _scheduledCallback = null;
        }
    }

    synchronized boolean isRunning() {
        return _running;
    }

    private void runTick(long generation) {
        synchronized (this) {
            if (!_running || generation != _generation) {
                return;
            }
        }

        _tick.run();

        synchronized (this) {
            if (_running && generation == _generation && _scheduledCallback != null) {
                _scheduler.postDelayed(_scheduledCallback, _delayMillis);
            }
        }
    }
}
