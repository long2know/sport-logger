package com.long2know.sportlogger;

import android.Manifest;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RecordingPermissionsTest {
    @Test
    public void target35UsesSeparateLegacyBodySensorPermissionsOnApi36() {
        List<String> startupPermissions =
                Arrays.asList(RecordingPermissions.requestedOnStartup(36, 35));
        List<String> requiredPermissions =
                Arrays.asList(RecordingPermissions.requiredForRecording(36, 35));

        assertTrue(startupPermissions.contains(Manifest.permission.BODY_SENSORS));
        assertFalse(startupPermissions.contains(RecordingPermissions.BODY_SENSORS_BACKGROUND));
        assertFalse(startupPermissions.contains(RecordingPermissions.READ_HEART_RATE));
        assertTrue(startupPermissions.contains(RecordingPermissions.ACTIVITY_RECOGNITION));
        assertTrue(startupPermissions.contains(Manifest.permission.POST_NOTIFICATIONS));
        assertEquals(
                RecordingPermissions.BODY_SENSORS_BACKGROUND,
                RecordingPermissions.backgroundSensorPermissionForRecording(36, 35));
        assertTrue(requiredPermissions.contains(RecordingPermissions.BODY_SENSORS_BACKGROUND));
    }

    @Test
    public void target36UsesSeparateGranularHealthPermissionsOnApi36() {
        List<String> startupPermissions =
                Arrays.asList(RecordingPermissions.requestedOnStartup(36, 36));
        List<String> requiredPermissions =
                Arrays.asList(RecordingPermissions.requiredForRecording(36, 36));

        assertTrue(startupPermissions.contains(RecordingPermissions.READ_HEART_RATE));
        assertFalse(startupPermissions.contains(Manifest.permission.BODY_SENSORS));
        assertFalse(
                startupPermissions.contains(
                        RecordingPermissions.READ_HEALTH_DATA_IN_BACKGROUND));
        assertTrue(startupPermissions.contains(RecordingPermissions.ACTIVITY_RECOGNITION));
        assertTrue(startupPermissions.contains(Manifest.permission.ACCESS_FINE_LOCATION));
        assertTrue(startupPermissions.contains(Manifest.permission.POST_NOTIFICATIONS));
        assertEquals(
                RecordingPermissions.READ_HEALTH_DATA_IN_BACKGROUND,
                RecordingPermissions.backgroundSensorPermissionForRecording(36, 36));
        assertTrue(
                requiredPermissions.contains(
                        RecordingPermissions.READ_HEALTH_DATA_IN_BACKGROUND));
    }

    @Test
    public void target36StillUsesLegacyPermissionPairBeforeApi36() {
        List<String> startupPermissions =
                Arrays.asList(RecordingPermissions.requestedOnStartup(35, 36));

        assertTrue(startupPermissions.contains(Manifest.permission.BODY_SENSORS));
        assertFalse(startupPermissions.contains(RecordingPermissions.READ_HEART_RATE));
        assertFalse(startupPermissions.contains(RecordingPermissions.BODY_SENSORS_BACKGROUND));
        assertEquals(
                RecordingPermissions.BODY_SENSORS_BACKGROUND,
                RecordingPermissions.backgroundSensorPermissionForRecording(35, 36));
    }

    @Test
    public void backgroundSensorPermissionIsNotRequiredBeforeApi33() {
        List<String> requiredPermissions =
                Arrays.asList(RecordingPermissions.requiredForRecording(32, 35));

        assertNull(RecordingPermissions.backgroundSensorPermissionForRecording(32, 35));
        assertFalse(requiredPermissions.contains(RecordingPermissions.BODY_SENSORS_BACKGROUND));
        assertFalse(
                requiredPermissions.contains(
                        RecordingPermissions.READ_HEALTH_DATA_IN_BACKGROUND));
    }

    @Test
    public void targetBeforeApi33KeepsCompatibilityBehaviorOnNewerDevices() {
        assertNull(RecordingPermissions.backgroundSensorPermissionForRecording(36, 32));
    }

    @Test
    public void preApi33RecordingDoesNotWaitForBackgroundPermission() {
        int sdkInt = 32;
        int targetSdkInt = 35;
        Set<String> granted = grantedRecordingPermissions(sdkInt, targetSdkInt);

        assertTrue(RecordingPermissions.backgroundSensorPermissionGranted(
                sdkInt, targetSdkInt, granted::contains));
        assertTrue(RecordingPermissions.allRequiredForRecordingGranted(
                sdkInt, targetSdkInt, granted::contains));
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
    public void deniedWhileInUsePermissionBlocksRecordingBeforeBackgroundRequest() {
        int sdkInt = 35;
        int targetSdkInt = 35;
        Set<String> granted = grantedRecordingPermissions(sdkInt, targetSdkInt);
        granted.remove(RecordingPermissions.ACTIVITY_RECOGNITION);

        assertFalse(RecordingPermissions.allWhileInUseRequiredForRecordingGranted(
                sdkInt, targetSdkInt, granted::contains));
        assertFalse(RecordingPermissions.allRequiredForRecordingGranted(
                sdkInt, targetSdkInt, granted::contains));
    }

    @Test
    public void deniedBackgroundPermissionBlocksRecordingAfterForegroundGrant() {
        int sdkInt = 35;
        int targetSdkInt = 35;
        Set<String> granted = grantedRecordingPermissions(sdkInt, targetSdkInt);
        granted.remove(RecordingPermissions.BODY_SENSORS_BACKGROUND);

        assertTrue(RecordingPermissions.allWhileInUseRequiredForRecordingGranted(
                sdkInt, targetSdkInt, granted::contains));
        assertFalse(RecordingPermissions.backgroundSensorPermissionGranted(
                sdkInt, targetSdkInt, granted::contains));
        assertFalse(RecordingPermissions.allRequiredForRecordingGranted(
                sdkInt, targetSdkInt, granted::contains));
    }

    @Test
    public void grantedBackgroundPermissionAllowsRecording() {
        int sdkInt = 35;
        int targetSdkInt = 35;
        Set<String> granted = grantedRecordingPermissions(sdkInt, targetSdkInt);

        assertTrue(RecordingPermissions.allWhileInUseRequiredForRecordingGranted(
                sdkInt, targetSdkInt, granted::contains));
        assertTrue(RecordingPermissions.backgroundSensorPermissionGranted(
                sdkInt, targetSdkInt, granted::contains));
        assertTrue(RecordingPermissions.allRequiredForRecordingGranted(
                sdkInt, targetSdkInt, granted::contains));
    }

    @Test
    public void deniedApi36BackgroundHealthPermissionBlocksRecording() {
        int sdkInt = 36;
        int targetSdkInt = 36;
        Set<String> granted = grantedRecordingPermissions(sdkInt, targetSdkInt);
        granted.remove(RecordingPermissions.READ_HEALTH_DATA_IN_BACKGROUND);

        assertTrue(RecordingPermissions.allWhileInUseRequiredForRecordingGranted(
                sdkInt, targetSdkInt, granted::contains));
        assertFalse(RecordingPermissions.backgroundSensorPermissionGranted(
                sdkInt, targetSdkInt, granted::contains));
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
