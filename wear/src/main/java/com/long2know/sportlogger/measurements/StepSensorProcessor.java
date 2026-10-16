package com.long2know.sportlogger.measurements;

import android.hardware.Sensor;

final class StepSensorProcessor {
    interface Session {
        int activityId();

        boolean isActive();

        int currentSteps();
    }

    private final Session session;
    private int trackedActivityId = Integer.MIN_VALUE;
    private int steps;
    private long counterBaseline = Long.MIN_VALUE;

    StepSensorProcessor(Session session) {
        this.session = session;
    }

    MeasurementEvent onSensorValue(
            int sensorType,
            long sequence,
            float sensorValue) {
        synchronizeSession();
        if (sensorType == Sensor.TYPE_STEP_COUNTER) {
            return onCounter(sequence, sensorValue);
        }
        if (sensorType == Sensor.TYPE_STEP_DETECTOR) {
            return onDetector(sequence, sensorValue);
        }
        return null;
    }

    private MeasurementEvent onCounter(long sequence, float sensorValue) {
        long currentCounter = Math.max(0L, Math.round(sensorValue));
        if (counterBaseline == Long.MIN_VALUE || currentCounter < counterBaseline) {
            counterBaseline = currentCounter;
            return null;
        }

        long delta = currentCounter - counterBaseline;
        counterBaseline = currentCounter;
        if (!session.isActive() || delta <= 0L) {
            return null;
        }
        steps = addSteps(steps, delta);
        return MeasurementEvent.steps(sequence, steps);
    }

    private MeasurementEvent onDetector(long sequence, float sensorValue) {
        if (!session.isActive()) {
            return null;
        }
        int increment = Math.max(1, Math.round(sensorValue));
        steps = addSteps(steps, increment);
        return MeasurementEvent.steps(sequence, steps);
    }

    private void synchronizeSession() {
        int currentActivityId = session.activityId();
        if (currentActivityId != trackedActivityId) {
            trackedActivityId = currentActivityId;
            steps = Math.max(0, session.currentSteps());
            counterBaseline = Long.MIN_VALUE;
        }
    }

    private static int addSteps(int current, long increment) {
        long next = current + increment;
        return next >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) next;
    }
}
