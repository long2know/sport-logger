package com.long2know.sportlogger.measurements;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.core.content.ContextCompat;

import com.long2know.sportlogger.acquisition.RecordingPermissionPolicy;
import com.long2know.utilities.models.SharedData;

public final class AndroidSensorSource implements ResourceOwner.Source {
    private final Context context;
    private final SensorManager sensorManager;

    public AndroidSensorSource(Context context) {
        this.context = context.getApplicationContext();
        this.sensorManager = (SensorManager) this.context.getSystemService(Context.SENSOR_SERVICE);
    }

    @Override
    public void open(ResourceOwner.Ownership ownership, ResourceOwner.EventSink sink)
            throws ResourceException {
        if (sensorManager == null) {
            throw unavailable("sensors", "Sensor service is unavailable", false);
        }

        HandlerThread callbackThread = new HandlerThread("sport-logger-sensors");
        callbackThread.start();
        ownership.own("sensor-callback-thread", callbackThread::quitSafely);
        Handler callbackHandler = new Handler(callbackThread.getLooper());
        StepSensorProcessor stepProcessor = new StepSensorProcessor(
                new StepSensorProcessor.Session() {
                    @Override
                    public int activityId() {
                        return SharedData.getInstance().getActivityId();
                    }

                    @Override
                    public boolean isActive() {
                        SharedData data = SharedData.getInstance();
                        return data.isRecording() && !data.isPaused();
                    }

                    @Override
                    public int currentSteps() {
                        return SharedData.getInstance().getData().Steps;
                    }
                });

        SensorEventListener listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                int type = event.sensor.getType();
                if (type == Sensor.TYPE_HEART_RATE && event.values.length > 0) {
                    sink.onEvent(MeasurementEvent.heartRate(event.timestamp, event.values[0]));
                } else if ((type == Sensor.TYPE_STEP_COUNTER
                        || type == Sensor.TYPE_STEP_DETECTOR)
                        && event.values.length > 0) {
                    MeasurementEvent measurement = stepProcessor.onSensorValue(
                            type,
                            event.timestamp,
                            event.values[0]);
                    if (measurement != null) {
                        sink.onEvent(measurement);
                    }
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                // Accuracy changes do not alter ownership or the latest measurement.
            }
        };

        int registered = 0;
        boolean permissionDenied = false;

        String heartRatePermission =
                RecordingPermissionPolicy.activeHeartRatePermission(Build.VERSION.SDK_INT);
        if (ContextCompat.checkSelfPermission(context, heartRatePermission)
                == PackageManager.PERMISSION_GRANTED) {
            registered += register(
                    "heart-rate",
                    Sensor.TYPE_HEART_RATE,
                    SensorManager.SENSOR_DELAY_FASTEST,
                    listener,
                    callbackHandler,
                    ownership,
                    sink,
                    true);
        } else {
            permissionDenied = true;
            sink.onFailure(new ResourceFailure(
                    "heart-rate",
                    ResourceFailure.Code.PERMISSION_DENIED,
                    "Body sensors permission is not granted"));
        }

        boolean hasActivityPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || ContextCompat.checkSelfPermission(
                        context,
                        RecordingPermissionPolicy.ACTIVITY_RECOGNITION)
                == PackageManager.PERMISSION_GRANTED;
        if (hasActivityPermission) {
            int stepRegistrations = register(
                    "step-counter",
                    Sensor.TYPE_STEP_COUNTER,
                    SensorManager.SENSOR_DELAY_GAME,
                    listener,
                    callbackHandler,
                    ownership,
                    sink,
                    false);
            if (stepRegistrations == 0) {
                stepRegistrations = register(
                        "step-detector",
                        Sensor.TYPE_STEP_DETECTOR,
                        SensorManager.SENSOR_DELAY_GAME,
                        listener,
                        callbackHandler,
                        ownership,
                        sink,
                        true);
            }
            registered += stepRegistrations;
        } else {
            permissionDenied = true;
            sink.onFailure(new ResourceFailure(
                    "steps",
                    ResourceFailure.Code.PERMISSION_DENIED,
                    "Activity recognition permission is not granted"));
        }

        if (registered == 0) {
            throw unavailable(
                    "sensors",
                    permissionDenied
                            ? "Sensor permissions are not granted"
                            : "No supported recording sensors are available",
                    permissionDenied);
        }
    }

    private int register(
            String name,
            int type,
            int delay,
            SensorEventListener listener,
            Handler callbackHandler,
            ResourceOwner.Ownership ownership,
            ResourceOwner.EventSink sink,
            boolean reportMissing) {
        Sensor sensor = sensorManager.getDefaultSensor(type);
        if (sensor == null) {
            if (reportMissing) {
                sink.onFailure(new ResourceFailure(
                        name,
                        ResourceFailure.Code.UNAVAILABLE,
                        "Sensor is not available"));
            }
            return 0;
        }
        try {
            if (!sensorManager.registerListener(listener, sensor, delay, callbackHandler)) {
                sink.onFailure(new ResourceFailure(
                        name,
                        ResourceFailure.Code.REGISTRATION_FAILED,
                        "Sensor registration was rejected"));
                return 0;
            }
            ownership.own(name, () -> sensorManager.unregisterListener(listener, sensor));
            return 1;
        } catch (SecurityException error) {
            sink.onFailure(new ResourceFailure(
                    name,
                    ResourceFailure.Code.PERMISSION_DENIED,
                    message(error)));
            return 0;
        } catch (RuntimeException error) {
            sink.onFailure(new ResourceFailure(
                    name,
                    ResourceFailure.Code.REGISTRATION_FAILED,
                    message(error)));
            return 0;
        }
    }

    private static ResourceException unavailable(
            String resource,
            String message,
            boolean retryable) {
        return new ResourceException(
                new ResourceFailure(resource, ResourceFailure.Code.UNAVAILABLE, message),
                retryable);
    }

    private static String message(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }
}
