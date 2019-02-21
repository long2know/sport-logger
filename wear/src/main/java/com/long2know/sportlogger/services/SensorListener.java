package com.long2know.sportlogger.services;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.long2know.utilities.models.Config;
import com.long2know.utilities.models.SharedData;

import java.util.concurrent.ScheduledExecutorService;
import static android.content.Context.SENSOR_SERVICE;
import static android.support.constraint.Constraints.TAG;

public class SensorListener implements Runnable {
    public static Handler WorkerHandler;

    private Handler _handler;
    private SensorManager _sensorManager;
    private SensorEventListener _eventListener;
    private Sensor _heartRateSensor;
    private Sensor _stepCountSensor;
    private Sensor _stepDetectSensor;
    private ScheduledExecutorService _scheduler;
    private boolean _isStarted = true;

    private int _stepCount;

    // Defines the code to run for this task.
    @Override
    public void run() {
        // Moves the current Thread into the background
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);

        Looper.prepare();
        _handler = Config.handler;

        WorkerHandler = new Handler(Looper.myLooper()) {
            public void handleMessage(Message msg) {
                Log.d(TAG, "Received a message!");
                // For now, the only messages are start/stop
                if (msg.what == 0) {
                    // The sensors are started by default
                    if (_isStarted) {
                        stopListeners();
                        Looper.myLooper().quit();
                        _isStarted = false;
                    }
                } else {
                    if (!_isStarted) {
                        startListeners();
                        _isStarted = true;
                    }
                }
            }
        };

        startListeners();
        Looper.loop();
    }

    public void startListeners() {
        _sensorManager = ((SensorManager) Config.context.getSystemService(SENSOR_SERVICE));
        _heartRateSensor = _sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE);
        _stepCountSensor = _sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        _stepDetectSensor = _sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);

        _eventListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                Log.d(TAG, event.toString());

                SharedData singleton = SharedData.getInstance();
                int sensorType = event.sensor.getType();

                if (sensorType == Sensor.TYPE_STEP_DETECTOR) {
                    _stepCount++;
                    singleton.setSteps(_stepCount);
                }

                if (sensorType == Sensor.TYPE_HEART_RATE) {
                    singleton.setHeartRate(event.values[0]);
                }

                Message completeMessage = _handler.obtainMessage(1, sensorType, 0, event);
                completeMessage.sendToTarget();
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                Log.d("MY_APP", sensor.toString() + " - " + accuracy);
            }
        };

        if (_heartRateSensor != null) {
            _sensorManager.registerListener(_eventListener, _heartRateSensor, SensorManager.SENSOR_DELAY_FASTEST);
        }

//        // We can force reading at specific intervals like this
//        if (_heartRateSensor != null) {
//            final int measurementDuration = 1;   // Seconds
//            final int measurementBreak = 0;    // Seconds
//
//            _scheduler = Executors.newScheduledThreadPool(1);
//            _scheduler.scheduleAtFixedRate(
//                    new Runnable() {
//                        @Override
//                        public void run() {
//                            Log.d(TAG, "register Heartrate Sensor");
//                            _sensorManager.registerListener(_eventListener, _heartRateSensor, SensorManager.SENSOR_DELAY_FASTEST);
//
//                            try {
//                                Thread.sleep(measurementDuration * 1000);
//                            } catch (InterruptedException e) {
//                                Log.e(TAG, "Interrupted while waitting to unregister Heartrate Sensor");
//                            }
//
//                            Log.d(TAG, "unregister Heartrate Sensor");
//                            _sensorManager.unregisterListener(_eventListener, _heartRateSensor);
//                        }
//                    }, 3, measurementDuration + measurementBreak, TimeUnit.SECONDS);
//
//        } else {
//            Log.d(TAG, "No Heartrate Sensor found");
//        }

        if (_stepCountSensor != null) {
            // Try registering and unregistering to clear the count
            _sensorManager.registerListener(_eventListener, _stepCountSensor, SensorManager.SENSOR_DELAY_GAME);
            _sensorManager.unregisterListener(_eventListener, _stepCountSensor);
            _sensorManager.registerListener(_eventListener, _stepCountSensor, SensorManager.SENSOR_DELAY_GAME);
        }

        if (_stepDetectSensor != null) {
            _sensorManager.registerListener(_eventListener, _stepDetectSensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    public void stopListeners() {
        if (_heartRateSensor != null) {
            _sensorManager.unregisterListener(_eventListener, _heartRateSensor);
        }

        if (_stepCountSensor != null) {
            _sensorManager.unregisterListener(_eventListener, _stepCountSensor);
        }

        if (_stepDetectSensor != null) {
            _sensorManager.unregisterListener(_eventListener, _stepDetectSensor);
        }

        SharedData singleton = SharedData.getInstance();
        singleton.setHeartRate(0);
    }
}
