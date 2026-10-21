package com.long2know.sportlogger.measurements;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;

public final class AndroidPressureSource implements ResourceOwner.Source {
    private final SensorManager sensorManager;

    public AndroidPressureSource(Context context) {
        sensorManager = (SensorManager) context.getApplicationContext()
                .getSystemService(Context.SENSOR_SERVICE);
    }

    @Override
    public void open(ResourceOwner.Ownership ownership, ResourceOwner.EventSink sink)
            throws ResourceException {
        if (sensorManager == null) {
            throw unavailable("Sensor service is unavailable");
        }
        Sensor pressure = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        if (pressure == null) {
            throw unavailable("Pressure sensor is not available");
        }

        HandlerThread callbackThread = new HandlerThread("sport-logger-pressure");
        callbackThread.start();
        ownership.own("pressure-callback-thread", callbackThread::quitSafely);
        SensorEventListener listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                if (event.values.length > 0) {
                    sink.onEvent(MeasurementEvent.pressure(
                            event.timestamp,
                            event.values[0]));
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };
        try {
            boolean registered = sensorManager.registerListener(
                    listener,
                    pressure,
                    SensorManager.SENSOR_DELAY_GAME,
                    new Handler(callbackThread.getLooper()));
            if (!registered) {
                throw new ResourceException(
                        new ResourceFailure(
                                "pressure",
                                ResourceFailure.Code.REGISTRATION_FAILED,
                                "Pressure sensor registration was rejected"),
                        true);
            }
            ownership.own(
                    "pressure",
                    () -> sensorManager.unregisterListener(listener, pressure));
        } catch (ResourceException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ResourceException(
                    new ResourceFailure(
                            "pressure",
                            ResourceFailure.Code.REGISTRATION_FAILED,
                            message(error)),
                    true);
        }
    }

    private static ResourceException unavailable(String message) {
        return new ResourceException(
                new ResourceFailure(
                        "pressure",
                        ResourceFailure.Code.UNAVAILABLE,
                        message),
                false);
    }

    private static String message(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }
}
