package com.long2know.sportlogger.acquisition;

import org.junit.Test;

import java.util.Collections;
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

    @Test
    public void eitherHealthPermissionMakesAllOtherMissingPermissionsOptional() {
        Set<String> heartOnly = new HashSet<>();
        heartOnly.add(RecordingPermissionPolicy.READ_HEART_RATE);
        RecordingPermissionPolicy.RuntimeState heartState = state(
                heartOnly,
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet());

        assertTrue(heartState.hasHealthForegroundPrerequisite());
        assertTrue(heartState.getMissingRequiredPermissions().isEmpty());
        assertTrue(heartState.getMissingOptionalPermissions().contains(
                RecordingPermissionPolicy.ACTIVITY_RECOGNITION));
        assertTrue(heartState.getMissingOptionalPermissions().contains(
                RecordingPermissionPolicy.ACCESS_FINE_LOCATION));
        assertTrue(heartState.getMissingOptionalPermissions().contains(
                RecordingPermissionPolicy.POST_NOTIFICATIONS));

        Set<String> activityOnly = new HashSet<>();
        activityOnly.add(RecordingPermissionPolicy.ACTIVITY_RECOGNITION);
        RecordingPermissionPolicy.RuntimeState activityState = state(
                activityOnly,
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet());

        assertTrue(activityState.hasHealthForegroundPrerequisite());
        assertTrue(activityState.getMissingRequiredPermissions().isEmpty());
        assertTrue(activityState.getMissingOptionalPermissions().contains(
                RecordingPermissionPolicy.READ_HEART_RATE));
    }

    @Test
    public void noHealthPermissionRequiresOnlyTheTwoHealthGrantPaths() {
        RecordingPermissionPolicy.RuntimeState state = state(
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet());

        assertFalse(state.hasHealthForegroundPrerequisite());
        assertEquals(
                List.of(
                        RecordingPermissionPolicy.READ_HEART_RATE,
                        RecordingPermissionPolicy.ACTIVITY_RECOGNITION),
                state.getMissingRequiredPermissions());
        assertTrue(state.getMissingOptionalPermissions().contains(
                RecordingPermissionPolicy.ACCESS_FINE_LOCATION));
        assertTrue(state.getMissingOptionalPermissions().contains(
                RecordingPermissionPolicy.POST_NOTIFICATIONS));
    }

    @Test
    public void permanentDenialRequiresARealDeniedRequestAndNoRationale() {
        String permission = RecordingPermissionPolicy.READ_HEART_RATE;

        RecordingPermissionPolicy.RuntimeState neverAsked = state(
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptySet());
        assertFalse(neverAsked.getPermanentlyDeniedPermissions().contains(permission));
        assertTrue(neverAsked.getRequestablePermissions().contains(permission));

        RecordingPermissionPolicy.RuntimeState requestedButNotDenied = state(
                Collections.emptySet(),
                Set.of(permission),
                Collections.emptySet(),
                Collections.emptySet());
        assertFalse(
                requestedButNotDenied.getPermanentlyDeniedPermissions().contains(permission));

        RecordingPermissionPolicy.RuntimeState deniedWithRationale = state(
                Collections.emptySet(),
                Set.of(permission),
                Set.of(permission),
                Set.of(permission));
        assertFalse(
                deniedWithRationale.getPermanentlyDeniedPermissions().contains(permission));
        assertTrue(deniedWithRationale.getRequestablePermissions().contains(permission));

        RecordingPermissionPolicy.RuntimeState permanentlyDenied = state(
                Collections.emptySet(),
                Set.of(permission),
                Set.of(permission),
                Collections.emptySet());
        assertTrue(
                permanentlyDenied.getPermanentlyDeniedPermissions().contains(permission));
        assertFalse(permanentlyDenied.getRequestablePermissions().contains(permission));
    }

    private static RecordingPermissionPolicy.RuntimeState state(
            Set<String> granted,
            Set<String> requested,
            Set<String> denied,
            Set<String> rationale) {
        return RecordingPermissionPolicy.runtimeState(
                36,
                true,
                true,
                granted::contains,
                requested::contains,
                denied::contains,
                rationale::contains);
    }
}
