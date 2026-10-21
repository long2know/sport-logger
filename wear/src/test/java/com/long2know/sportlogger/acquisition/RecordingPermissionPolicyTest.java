package com.long2know.sportlogger.acquisition;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecordingPermissionPolicyTest {
    @Test
    public void api35UsesBodySensorsAndNoBackgroundPermissions() {
        List<String> permissions =
                RecordingPermissionPolicy.runtimePermissions(35, true, true);

        assertTrue(permissions.contains(RecordingPermissionPolicy.BODY_SENSORS));
        assertFalse(permissions.contains(RecordingPermissionPolicy.READ_HEART_RATE));
        assertFalse(permissions.contains("android.permission.BODY_SENSORS_BACKGROUND"));
        assertFalse(permissions.contains("android.permission.ACCESS_BACKGROUND_LOCATION"));
    }

    @Test
    public void api36UsesModernHeartPermissionAndNoPassivePermission() {
        List<String> permissions =
                RecordingPermissionPolicy.runtimePermissions(36, true, true);

        assertTrue(permissions.contains(RecordingPermissionPolicy.READ_HEART_RATE));
        assertFalse(permissions.contains(RecordingPermissionPolicy.BODY_SENSORS));
        assertFalse(
                permissions.contains(
                        "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"));
    }

    @Test
    public void approximateLocationIsDistinctAndDoesNotSatisfyPreciseRoute() {
        Set<String> granted = new HashSet<>();
        granted.add(RecordingPermissionPolicy.ACCESS_COARSE_LOCATION);

        assertEquals(
                RecordingPermissionPolicy.RoutePermission.APPROXIMATE_ONLY,
                RecordingPermissionPolicy.routePermission(granted::contains));
    }

    @Test
    public void activityPermissionKeepsHealthForegroundTypeWithoutLocation() {
        Set<String> granted = new HashSet<>();
        granted.add(RecordingPermissionPolicy.ACTIVITY_RECOGNITION);

        assertTrue(
                RecordingPermissionPolicy.hasHealthForegroundPrerequisite(
                        36,
                        granted::contains));
        assertEquals(
                RecordingPermissionPolicy.RoutePermission.DENIED,
                RecordingPermissionPolicy.routePermission(granted::contains));
    }
}
