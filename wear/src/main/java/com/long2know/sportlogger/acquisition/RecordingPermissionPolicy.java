package com.long2know.sportlogger.acquisition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class RecordingPermissionPolicy {
    public static final String BODY_SENSORS = "android.permission.BODY_SENSORS";
    public static final String READ_HEART_RATE =
            "android.permission.health.READ_HEART_RATE";
    public static final String ACTIVITY_RECOGNITION =
            "android.permission.ACTIVITY_RECOGNITION";
    public static final String ACCESS_COARSE_LOCATION =
            "android.permission.ACCESS_COARSE_LOCATION";
    public static final String ACCESS_FINE_LOCATION =
            "android.permission.ACCESS_FINE_LOCATION";
    public static final String POST_NOTIFICATIONS =
            "android.permission.POST_NOTIFICATIONS";

    public enum RoutePermission {
        PRECISE,
        APPROXIMATE_ONLY,
        DENIED
    }

    public interface Checker {
        boolean isGranted(String permission);
    }

    private RecordingPermissionPolicy() {
    }

    public static String activeHeartRatePermission(int sdkInt) {
        return sdkInt >= 36 ? READ_HEART_RATE : BODY_SENSORS;
    }

    public static List<String> runtimePermissions(
            int sdkInt,
            boolean includeRoute,
            boolean includeNotifications) {
        Set<String> permissions = new LinkedHashSet<>();
        permissions.add(activeHeartRatePermission(sdkInt));
        if (sdkInt >= 29) {
            permissions.add(ACTIVITY_RECOGNITION);
        }
        if (includeRoute) {
            permissions.add(ACCESS_COARSE_LOCATION);
            permissions.add(ACCESS_FINE_LOCATION);
        }
        if (includeNotifications && sdkInt >= 33) {
            permissions.add(POST_NOTIFICATIONS);
        }
        return Collections.unmodifiableList(new ArrayList<>(permissions));
    }

    public static boolean hasHealthForegroundPrerequisite(
            int sdkInt,
            Checker checker) {
        Objects.requireNonNull(checker);
        return checker.isGranted(activeHeartRatePermission(sdkInt))
                || (sdkInt < 29 || checker.isGranted(ACTIVITY_RECOGNITION));
    }

    public static RoutePermission routePermission(Checker checker) {
        Objects.requireNonNull(checker);
        if (checker.isGranted(ACCESS_FINE_LOCATION)) {
            return RoutePermission.PRECISE;
        }
        if (checker.isGranted(ACCESS_COARSE_LOCATION)) {
            return RoutePermission.APPROXIMATE_ONLY;
        }
        return RoutePermission.DENIED;
    }
}
