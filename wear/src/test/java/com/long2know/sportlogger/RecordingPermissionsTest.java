package com.long2know.sportlogger;

import android.Manifest;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecordingPermissionsTest {
    @Test
    public void target35UsesLegacyBodySensorPermissionOnApi36() {
        List<String> permissions =
                Arrays.asList(RecordingPermissions.requestedOnStartup(36, 35));

        assertTrue(permissions.contains(Manifest.permission.BODY_SENSORS));
        assertFalse(permissions.contains(RecordingPermissions.READ_HEART_RATE));
        assertTrue(permissions.contains(RecordingPermissions.ACTIVITY_RECOGNITION));
        assertTrue(permissions.contains(Manifest.permission.POST_NOTIFICATIONS));
    }

    @Test
    public void target36UsesGranularHeartRatePermissionOnApi36() {
        List<String> permissions =
                Arrays.asList(RecordingPermissions.requestedOnStartup(36, 36));

        assertTrue(permissions.contains(RecordingPermissions.READ_HEART_RATE));
        assertFalse(permissions.contains(Manifest.permission.BODY_SENSORS));
        assertTrue(permissions.contains(RecordingPermissions.ACTIVITY_RECOGNITION));
        assertTrue(permissions.contains(Manifest.permission.ACCESS_FINE_LOCATION));
        assertTrue(permissions.contains(Manifest.permission.POST_NOTIFICATIONS));
    }

    @Test
    public void target36StillUsesLegacyPermissionBeforeApi36() {
        List<String> permissions =
                Arrays.asList(RecordingPermissions.requestedOnStartup(35, 36));

        assertTrue(permissions.contains(Manifest.permission.BODY_SENSORS));
        assertFalse(permissions.contains(RecordingPermissions.READ_HEART_RATE));
        assertTrue(permissions.contains(RecordingPermissions.ACTIVITY_RECOGNITION));
    }

    @Test
    public void activityRecognitionIsRequestedOnlyFromApi29() {
        List<String> api28Permissions =
                Arrays.asList(RecordingPermissions.requestedOnStartup(28, 35));
        List<String> api29Permissions =
                Arrays.asList(RecordingPermissions.requestedOnStartup(29, 35));

        assertFalse(api28Permissions.contains(RecordingPermissions.ACTIVITY_RECOGNITION));
        assertTrue(api29Permissions.contains(RecordingPermissions.ACTIVITY_RECOGNITION));
    }

    @Test
    public void deniedActivityRecognitionBlocksRecording() {
        int sdkInt = 35;
        int targetSdkInt = 35;
        Set<String> granted = grantedRecordingPermissions(sdkInt, targetSdkInt);
        granted.remove(RecordingPermissions.ACTIVITY_RECOGNITION);

        assertFalse(RecordingPermissions.allRequiredForRecordingGranted(
                sdkInt, targetSdkInt, granted::contains));
    }

    @Test
    public void notificationPermissionDoesNotBlockRecording() {
        int sdkInt = 35;
        int targetSdkInt = 35;
        Set<String> granted = grantedRecordingPermissions(sdkInt, targetSdkInt);

        assertFalse(granted.contains(Manifest.permission.POST_NOTIFICATIONS));
        assertTrue(RecordingPermissions.allRequiredForRecordingGranted(
                sdkInt, targetSdkInt, granted::contains));
    }

    @Test
    public void existingHeartRateAndLocationPermissionsRemainRequired() {
        int sdkInt = 35;
        int targetSdkInt = 35;
        Set<String> granted = grantedRecordingPermissions(sdkInt, targetSdkInt);

        granted.remove(Manifest.permission.BODY_SENSORS);
        assertFalse(RecordingPermissions.allRequiredForRecordingGranted(
                sdkInt, targetSdkInt, granted::contains));

        granted = grantedRecordingPermissions(sdkInt, targetSdkInt);
        granted.remove(Manifest.permission.ACCESS_FINE_LOCATION);
        assertFalse(RecordingPermissions.allRequiredForRecordingGranted(
                sdkInt, targetSdkInt, granted::contains));
    }

    private static Set<String> grantedRecordingPermissions(int sdkInt, int targetSdkInt) {
        return new HashSet<>(
                Arrays.asList(RecordingPermissions.requiredForRecording(sdkInt, targetSdkInt)));
    }
}
