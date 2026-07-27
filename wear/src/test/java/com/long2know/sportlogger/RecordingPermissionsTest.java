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
    public void api26UsesOnlyLegacyForegroundPermissions() {
        List<String> requested =
                Arrays.asList(RecordingPermissions.requestedOnStartup(26, 35));

        assertTrue(requested.contains(Manifest.permission.BODY_SENSORS));
        assertTrue(requested.contains(Manifest.permission.ACCESS_FINE_LOCATION));
        assertFalse(requested.contains(RecordingPermissions.ACTIVITY_RECOGNITION));
        assertFalse(requested.contains(Manifest.permission.POST_NOTIFICATIONS));
        assertNull(RecordingPermissions.backgroundSensorPermissionForRecording(26, 35));
    }

    @Test
    public void api33To35RequestsLegacyBackgroundPermissionSeparately() {
        List<String> startup =
                Arrays.asList(RecordingPermissions.requestedOnStartup(35, 35));
        List<String> required =
                Arrays.asList(RecordingPermissions.requiredForRecording(35, 35));

        assertTrue(startup.contains(Manifest.permission.BODY_SENSORS));
        assertTrue(startup.contains(RecordingPermissions.ACTIVITY_RECOGNITION));
        assertTrue(startup.contains(Manifest.permission.POST_NOTIFICATIONS));
        assertFalse(startup.contains(RecordingPermissions.BODY_SENSORS_BACKGROUND));
        assertTrue(required.contains(RecordingPermissions.BODY_SENSORS_BACKGROUND));
    }

    @Test
    public void api36Target35KeepsLegacyBodySensorPolicy() {
        List<String> required =
                Arrays.asList(RecordingPermissions.requiredForRecording(36, 35));

        assertTrue(required.contains(Manifest.permission.BODY_SENSORS));
        assertTrue(required.contains(RecordingPermissions.BODY_SENSORS_BACKGROUND));
        assertFalse(required.contains(RecordingPermissions.READ_HEART_RATE));
        assertFalse(required.contains(RecordingPermissions.READ_HEALTH_DATA_IN_BACKGROUND));
    }

    @Test
    public void api36Target36UsesGranularHealthPermissions() {
        List<String> startup =
                Arrays.asList(RecordingPermissions.requestedOnStartup(36, 36));
        List<String> required =
                Arrays.asList(RecordingPermissions.requiredForRecording(36, 36));

        assertTrue(startup.contains(RecordingPermissions.READ_HEART_RATE));
        assertFalse(startup.contains(Manifest.permission.BODY_SENSORS));
        assertFalse(startup.contains(RecordingPermissions.READ_HEALTH_DATA_IN_BACKGROUND));
        assertTrue(required.contains(RecordingPermissions.READ_HEALTH_DATA_IN_BACKGROUND));
        assertFalse(required.contains(RecordingPermissions.BODY_SENSORS_BACKGROUND));
    }

    @Test
    public void notificationDenialDoesNotBlockRecording() {
        int sdkInt = 35;
        int targetSdkInt = 35;
        Set<String> granted = grantedRequiredPermissions(sdkInt, targetSdkInt);

        assertFalse(granted.contains(Manifest.permission.POST_NOTIFICATIONS));
        assertTrue(RecordingPermissions.allRequiredForRecordingGranted(
                sdkInt, targetSdkInt, granted::contains));
    }

    @Test
    public void requiredPermissionDenialBlocksRecording() {
        int sdkInt = 35;
        int targetSdkInt = 35;
        Set<String> granted = grantedRequiredPermissions(sdkInt, targetSdkInt);
        granted.remove(RecordingPermissions.ACTIVITY_RECOGNITION);

        assertFalse(RecordingPermissions.allRequiredForRecordingGranted(
                sdkInt, targetSdkInt, granted::contains));
    }

    @Test
    public void targetBelow33DoesNotRequireBackgroundSensorPermission() {
        assertNull(RecordingPermissions.backgroundSensorPermissionForRecording(36, 32));
    }

    @Test
    public void api36BackgroundPermissionSelectionMatchesTargetPolicy() {
        assertEquals(
                RecordingPermissions.BODY_SENSORS_BACKGROUND,
                RecordingPermissions.backgroundSensorPermissionForRecording(36, 35));
        assertEquals(
                RecordingPermissions.READ_HEALTH_DATA_IN_BACKGROUND,
                RecordingPermissions.backgroundSensorPermissionForRecording(36, 36));
    }

    private static Set<String> grantedRequiredPermissions(int sdkInt, int targetSdkInt) {
        return new HashSet<>(
                Arrays.asList(
                        RecordingPermissions.requiredForRecording(sdkInt, targetSdkInt)));
    }
}
