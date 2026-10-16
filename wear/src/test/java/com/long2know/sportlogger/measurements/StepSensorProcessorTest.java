package com.long2know.sportlogger.measurements;

import android.hardware.Sensor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class StepSensorProcessorTest {
    @Test
    public void counterUsesSessionDeltaAndExcludesPausedStepsAndCounterReset() {
        SessionState session = new SessionState(41, true, 0);
        StepSensorProcessor processor = new StepSensorProcessor(session);

        assertNull(processor.onSensorValue(
                Sensor.TYPE_STEP_COUNTER,
                1L,
                10_000F));
        assertEquals(3, steps(processor.onSensorValue(
                Sensor.TYPE_STEP_COUNTER,
                2L,
                10_003F)));

        session.active = false;
        session.currentSteps = 3;
        assertNull(processor.onSensorValue(
                Sensor.TYPE_STEP_COUNTER,
                3L,
                10_008F));

        session.active = true;
        assertEquals(5, steps(processor.onSensorValue(
                Sensor.TYPE_STEP_COUNTER,
                4L,
                10_010F)));

        session.currentSteps = 5;
        assertNull(processor.onSensorValue(
                Sensor.TYPE_STEP_COUNTER,
                5L,
                2F));
        assertEquals(8, steps(processor.onSensorValue(
                Sensor.TYPE_STEP_COUNTER,
                6L,
                5F)));
    }

    @Test
    public void recreationAndNewSessionNeverCountStepsSinceBoot() {
        SessionState session = new SessionState(51, true, 8);
        StepSensorProcessor recreated = new StepSensorProcessor(session);

        assertNull(recreated.onSensorValue(
                Sensor.TYPE_STEP_COUNTER,
                10L,
                20_000F));
        assertEquals(10, steps(recreated.onSensorValue(
                Sensor.TYPE_STEP_COUNTER,
                11L,
                20_002F)));

        session.activityId = 52;
        session.currentSteps = 0;
        assertNull(recreated.onSensorValue(
                Sensor.TYPE_STEP_COUNTER,
                12L,
                20_010F));
        assertEquals(1, steps(recreated.onSensorValue(
                Sensor.TYPE_STEP_COUNTER,
                13L,
                20_011F)));
    }

    @Test
    public void detectorFallbackHonorsPauseAndSessionTotals() {
        SessionState session = new SessionState(61, true, 2);
        StepSensorProcessor processor = new StepSensorProcessor(session);

        assertEquals(3, steps(processor.onSensorValue(
                Sensor.TYPE_STEP_DETECTOR,
                20L,
                1F)));
        session.active = false;
        session.currentSteps = 3;
        assertNull(processor.onSensorValue(
                Sensor.TYPE_STEP_DETECTOR,
                21L,
                1F));
        session.active = true;
        assertEquals(4, steps(processor.onSensorValue(
                Sensor.TYPE_STEP_DETECTOR,
                22L,
                1F)));
    }

    private static int steps(MeasurementEvent event) {
        if (event == null) {
            throw new AssertionError("Expected a step event");
        }
        return event.getSteps();
    }

    private static final class SessionState implements StepSensorProcessor.Session {
        private int activityId;
        private boolean active;
        private int currentSteps;

        private SessionState(int activityId, boolean active, int currentSteps) {
            this.activityId = activityId;
            this.active = active;
            this.currentSteps = currentSteps;
        }

        @Override
        public int activityId() {
            return activityId;
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public int currentSteps() {
            return currentSteps;
        }
    }
}
