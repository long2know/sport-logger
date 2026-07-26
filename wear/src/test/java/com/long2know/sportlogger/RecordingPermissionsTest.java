package com.long2know.sportlogger;

import android.Manifest;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecordingPermissionsTest {
    @Test
    public void target35UsesLegacyBodySensorPermissionOnApi36() {
        List<String> permissions =
                Arrays.asList(RecordingPermissions.requestedOnStartup(36, 35));

        assertTrue(permissions.contains(Manifest.permission.BODY_SENSORS));
        assertFalse(permissions.contains(RecordingPermissions.READ_HEART_RATE));
        assertTrue(permissions.contains(Manifest.permission.POST_NOTIFICATIONS));
    }

    @Test
    public void target36UsesGranularHeartRatePermissionOnApi36() {
        List<String> permissions =
                Arrays.asList(RecordingPermissions.requestedOnStartup(36, 36));

        assertTrue(permissions.contains(RecordingPermissions.READ_HEART_RATE));
        assertFalse(permissions.contains(Manifest.permission.BODY_SENSORS));
        assertTrue(permissions.contains(Manifest.permission.ACCESS_FINE_LOCATION));
        assertTrue(permissions.contains(Manifest.permission.POST_NOTIFICATIONS));
    }

    @Test
    public void target36StillUsesLegacyPermissionBeforeApi36() {
        List<String> permissions =
                Arrays.asList(RecordingPermissions.requestedOnStartup(35, 36));

        assertTrue(permissions.contains(Manifest.permission.BODY_SENSORS));
        assertFalse(permissions.contains(RecordingPermissions.READ_HEART_RATE));
    }
}
