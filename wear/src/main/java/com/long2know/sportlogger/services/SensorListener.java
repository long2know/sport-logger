package com.long2know.sportlogger.services;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.long2know.utilities.models.SharedData;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class SensorListener implements ManagedListener {
    private static final String TAG = "SensorListener";

    private final Context _context;
    private final Handler _uiHandler;
    private final Runnable _permissionFailureCallback;
    private final AtomicBoolean _ownerActive;
    private final CountDownLatch _ready = new CountDownLatch(1);
    private final CountDownLatch _stopped = new CountDownLatch(1);
    private final Object _lifecycleLock = new Object();

    private Looper _looper;
    private Handler _workerHandler;
    private SensorManager _sensorManager;
    private SensorEventListener _eventListener;
    private Sensor _heartRateSensor;
    private Sensor _stepCountSensor;
    private Sensor _stepDetectSensor;
    private int _stepCount;
    private volatile boolean _shutdownRequested;

    SensorListener(
            Context context,
            Handler uiHandler,
            Runnable permissionFailureCallback,
            AtomicBoolean ownerActive) {
        _context = context.getApplicationContext();
        _uiHandler = uiHandler;
        _permissionFailureCallback = permissionFailureCallback;
        _ownerActive = ownerActive;
    }

    @Override
    public void run() {
        android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_BACKGROUND);
        try {
            Looper.prepare();
            synchronized (_lifecycleLock) {
                if (_shutdownRequested) {
                    return;
                }
                _looper = Looper.myLooper();
                _workerHandler = new Handler(_looper);
            }
            if (!_shutdownRequested && _ownerActive.get()) {
                startListeners();
            }
            _ready.countDown();
            if (!_shutdownRequested && _ownerActive.get()) {
                Looper.loop();
            }
        } finally {
            stopListeners();
            synchronized (_lifecycleLock) {
                _workerHandler = null;
                _looper = null;
            }
            _ready.countDown();
            _stopped.countDown();
        }
    }

    @Override
    public void requestShutdown() {
        synchronized (_ownerActive) {
            _ownerActive.set(false);
        }
        Handler handler;
        synchronized (_lifecycleLock) {
            _shutdownRequested = true;
            handler = _workerHandler;
            if (handler == null) {
                return;
            }
        }
        if (!handler.postAtFrontOfQueue(new Runnable() {
            @Override
            public void run() {
                Looper.myLooper().quitSafely();
            }
        })) {
            Looper looper;
            synchronized (_lifecycleLock) {
                looper = _looper;
            }
            if (looper != null) {
                looper.quitSafely();
            }
        }
    }

    @Override
    public boolean awaitStopped(long timeoutMillis) throws InterruptedException {
        return _stopped.await(Math.max(0L, timeoutMillis), TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean awaitReady(long timeoutMillis) throws InterruptedException {
        return _ready.await(Math.max(0L, timeoutMillis), TimeUnit.MILLISECONDS);
    }

    private void startListeners() {
        try {
            _sensorManager = (SensorManager) _context.getSystemService(Context.SENSOR_SERVICE);
            if (_sensorManager == null) {
                return;
            }

            _heartRateSensor = _sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
            _stepCountSensor = _sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            _stepDetectSensor = _sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
            _eventListener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    synchronized (_ownerActive) {
                        if (!_ownerActive.get() || _shutdownRequested) {
                            return;
                        }
                        SharedData singleton = SharedData.getInstance();
                        int sensorType = event.sensor.getType();
                        if (sensorType == Sensor.TYPE_STEP_DETECTOR) {
                            _stepCount++;
                            singleton.setSteps(_stepCount);
                        } else if (sensorType == Sensor.TYPE_HEART_RATE) {
                            singleton.setHeartRate(event.values[0]);
                        }
                        Message message =
                                _uiHandler.obtainMessage(1, sensorType, 0, event);
                        message.sendToTarget();
                    }
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {
                    Log.d(TAG, sensor + " - " + accuracy);
                }
            };

            register(_heartRateSensor, SensorManager.SENSOR_DELAY_FASTEST);
            register(_stepCountSensor, SensorManager.SENSOR_DELAY_GAME);
            register(_stepDetectSensor, SensorManager.SENSOR_DELAY_GAME);
        } catch (SecurityException exception) {
            Log.e(TAG, "Recording sensor permission was denied or revoked.", exception);
            stopListeners();
            boolean notify;
            synchronized (_ownerActive) {
                notify = _ownerActive.get();
                _ownerActive.set(false);
            }
            if (notify && _permissionFailureCallback != null) {
                _permissionFailureCallback.run();
            }
        }
    }

    private void register(Sensor sensor, int delay) {
        if (sensor != null && _ownerActive.get() && !_shutdownRequested) {
            _sensorManager.registerListener(_eventListener, sensor, delay);
        }
    }

    private void stopListeners() {
        if (_sensorManager != null && _eventListener != null) {
            _sensorManager.unregisterListener(_eventListener);
        }
        _eventListener = null;
        _heartRateSensor = null;
        _stepCountSensor = null;
        _stepDetectSensor = null;
    }
}
