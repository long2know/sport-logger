package com.long2know.sportlogger.services;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.long2know.utilities.models.SharedData;

public class StopWatch {
    interface CallbackScheduler {
        void postDelayed(Runnable callback, long delayMillis);
        void removeCallbacks(Runnable callback);
    }

    interface TimeSource {
        long uptimeMillis();
    }

    private static final class HandlerCallbackScheduler implements CallbackScheduler {
        private final Handler _handler;

        HandlerCallbackScheduler(Handler handler) {
            _handler = handler;
        }

        @Override
        public void postDelayed(Runnable callback, long delayMillis) {
            _handler.postDelayed(callback, delayMillis);
        }

        @Override
        public void removeCallbacks(Runnable callback) {
            _handler.removeCallbacks(callback);
        }
    }

    private long _millisecondTime;
    private long _startTime;
    private long _timeBuff;
    private long _updateTime;
    private int _seconds;
    private int _minutes;
    private int _hours;
    private int _milliSeconds;
    String _duration;
    private final CallbackScheduler _callbackScheduler;
    private final TimeSource _timeSource;
    private Runnable _scheduledCallback;
    private long _generation;
    private boolean _isRunning;

    public StopWatch() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        _callbackScheduler = new HandlerCallbackScheduler(
                new Handler(Looper.getMainLooper()));
        _timeSource = SystemClock::uptimeMillis;
        _duration = "00:00:00";
    }

    StopWatch(CallbackScheduler callbackScheduler, TimeSource timeSource) {
        _callbackScheduler = callbackScheduler;
        _timeSource = timeSource;
        _duration = "00:00:00";
    }

    public synchronized void startTImer() {
        if (_isRunning) {
            return;
        }

        cancelScheduledCallback();
        _millisecondTime = 0L;
        _startTime = _timeSource.uptimeMillis();
        _isRunning = true;
        final long generation = ++_generation;
        _scheduledCallback = new Runnable() {
            @Override
            public void run() {
                updateTimer(generation);
            }
        };
        _callbackScheduler.postDelayed(_scheduledCallback, 200);
    }

    public synchronized void resetTimer() {
        _isRunning = false;
        _generation++;
        cancelScheduledCallback();
        _millisecondTime = 0L;
        _startTime = 0L;
        _timeBuff = 0L;
        _updateTime = 0L;
        _seconds = 0;
        _minutes = 0;
        _hours = 0;
        _milliSeconds = 0;
        _duration = "00:00:00";
        SharedData.getInstance().Duration = _duration;
    }

    public synchronized void pauseTimer() {
        if (_isRunning) {
            _millisecondTime = Math.max(0L, _timeSource.uptimeMillis() - _startTime);
            _timeBuff += _millisecondTime;
        }
        _isRunning = false;
        _generation++;
        cancelScheduledCallback();
    }

    private synchronized void updateTimer(long generation) {
        if (!_isRunning || generation != _generation) {
            return;
        }

        _millisecondTime = Math.max(0L, _timeSource.uptimeMillis() - _startTime);
        _updateTime = _timeBuff + _millisecondTime;
        _seconds = (int) (_updateTime / 1000);
        _hours = _seconds / 3600;
        _minutes = (_seconds / 60) % 60;
        _seconds = _seconds % 60;
        _milliSeconds = (int) (_updateTime % 1000);

        _duration = String.format("%02d", _hours) + ":"
                + String.format("%02d", _minutes) + ":"
                + String.format("%02d", _seconds);

        SharedData.getInstance().Duration = _duration;
        if (_isRunning && generation == _generation) {
            _callbackScheduler.postDelayed(_scheduledCallback, 0);
        }
    }

    private void cancelScheduledCallback() {
        if (_scheduledCallback != null) {
            _callbackScheduler.removeCallbacks(_scheduledCallback);
            _scheduledCallback = null;
        }
    }
}
