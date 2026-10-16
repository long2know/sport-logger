package com.long2know.sportlogger;

import android.Manifest;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.rule.GrantPermissionRule;

import com.long2know.sportlogger.recording.RecordingSnapshot;
import com.long2know.sportlogger.recording.RecordingState;
import com.long2know.utilities.models.SharedData;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 33)
public class MainActivityRecreationTest {
    @Rule
    public final GrantPermissionRule permissions = GrantPermissionRule.grant(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.POST_NOTIFICATIONS);

    @Before
    public void setUp() {
        SharedData sharedData = SharedData.getInstance();
        sharedData.resetMeasurements();
        sharedData.setRecordingState(true, false, 91, "00:00:01");
    }

    @After
    public void tearDown() {
        SharedData sharedData = SharedData.getInstance();
        sharedData.resetMeasurements();
        sharedData.setRecordingState(false, false, 0, "00:00:00");
    }

    @Test
    public void restoredVisibleSensorFragmentReceivesLiveUpdatesAfterRecreation() {
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> assertLiveUpdate(activity, 3, "00:00:03"));
            scenario.recreate();
            scenario.onActivity(activity -> assertLiveUpdate(activity, 17, "00:00:17"));
        }
    }

    private static void assertLiveUpdate(
            MainActivity activity,
            int steps,
            String duration) {
        activity.onRecordingStateChanged(new RecordingSnapshot(
                RecordingState.RECORDING,
                91,
                17_000L,
                null));
        activity.getSupportFragmentManager().executePendingTransactions();

        SharedData sharedData = SharedData.getInstance();
        sharedData.setSteps(steps);
        sharedData.setRecordingState(true, false, 91, duration);
        activity.onLoggerUpdate(sharedData);

        Fragment visible = activity.getSupportFragmentManager()
                .findFragmentById(R.id.content_frame);
        assertTrue(visible instanceof SensorFragment);
        TextView stepsView = activity.findViewById(R.id.steps);
        TextView durationView = activity.findViewById(R.id.tvTimer);
        assertEquals(Integer.toString(steps), stepsView.getText().toString());
        assertEquals(duration, durationView.getText().toString());

        int sensorFragments = 0;
        for (Fragment fragment : activity.getSupportFragmentManager().getFragments()) {
            if (fragment instanceof SensorFragment) {
                sensorFragments++;
            }
        }
        assertEquals(1, sensorFragments);
    }
}
