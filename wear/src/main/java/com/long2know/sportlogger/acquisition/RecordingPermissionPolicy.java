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

    public static final class RuntimeState {
        private final boolean healthForegroundPrerequisiteGranted;
        private final List<String> missingPermissions;
        private final List<String> missingRequiredPermissions;
        private final List<String> missingOptionalPermissions;
        private final List<String> permanentlyDeniedPermissions;
        private final List<String> requestablePermissions;
        private final List<String> requestableRequiredPermissions;

        private RuntimeState(
                boolean healthForegroundPrerequisiteGranted,
                List<String> missingPermissions,
                List<String> missingRequiredPermissions,
                List<String> missingOptionalPermissions,
                List<String> permanentlyDeniedPermissions,
                List<String> requestablePermissions,
                List<String> requestableRequiredPermissions) {
            this.healthForegroundPrerequisiteGranted =
                    healthForegroundPrerequisiteGranted;
            this.missingPermissions = immutableCopy(missingPermissions);
            this.missingRequiredPermissions =
                    immutableCopy(missingRequiredPermissions);
            this.missingOptionalPermissions =
                    immutableCopy(missingOptionalPermissions);
            this.permanentlyDeniedPermissions =
                    immutableCopy(permanentlyDeniedPermissions);
            this.requestablePermissions = immutableCopy(requestablePermissions);
            this.requestableRequiredPermissions =
                    immutableCopy(requestableRequiredPermissions);
        }

        public boolean hasHealthForegroundPrerequisite() {
            return healthForegroundPrerequisiteGranted;
        }

        public List<String> getMissingPermissions() {
            return missingPermissions;
        }

        public List<String> getMissingRequiredPermissions() {
            return missingRequiredPermissions;
        }

        public List<String> getMissingOptionalPermissions() {
            return missingOptionalPermissions;
        }

        public List<String> getPermanentlyDeniedPermissions() {
            return permanentlyDeniedPermissions;
        }

        public List<String> getRequestablePermissions() {
            return requestablePermissions;
        }

        public List<String> getRequestableRequiredPermissions() {
            return requestableRequiredPermissions;
        }

        public boolean hasPermanentDenial() {
            return !permanentlyDeniedPermissions.isEmpty();
        }

        private static List<String> immutableCopy(List<String> permissions) {
            return Collections.unmodifiableList(new ArrayList<>(permissions));
        }
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

    public static List<String> healthForegroundPrerequisitePermissions(int sdkInt) {
        List<String> permissions = new ArrayList<>();
        permissions.add(activeHeartRatePermission(sdkInt));
        if (sdkInt >= 29) {
            permissions.add(ACTIVITY_RECOGNITION);
        }
        return Collections.unmodifiableList(permissions);
    }

    public static boolean hasHealthForegroundPrerequisite(
            int sdkInt,
            Checker checker) {
        Objects.requireNonNull(checker);
        return checker.isGranted(activeHeartRatePermission(sdkInt))
                || (sdkInt < 29 || checker.isGranted(ACTIVITY_RECOGNITION));
    }

    public static RuntimeState runtimeState(
            int sdkInt,
            boolean includeRoute,
            boolean includeNotifications,
            Checker granted,
            Checker requestedBefore,
            Checker deniedBefore,
            Checker shouldShowRationale) {
        Objects.requireNonNull(granted);
        Objects.requireNonNull(requestedBefore);
        Objects.requireNonNull(deniedBefore);
        Objects.requireNonNull(shouldShowRationale);

        List<String> missing = new ArrayList<>();
        for (String permission :
                runtimePermissions(sdkInt, includeRoute, includeNotifications)) {
            if (!granted.isGranted(permission)) {
                missing.add(permission);
            }
        }

        boolean healthGranted =
                hasHealthForegroundPrerequisite(sdkInt, granted);
        Set<String> requiredCandidates = new LinkedHashSet<>(
                healthForegroundPrerequisitePermissions(sdkInt));
        List<String> missingRequired = new ArrayList<>();
        List<String> missingOptional = new ArrayList<>();
        for (String permission : missing) {
            if (!healthGranted && requiredCandidates.contains(permission)) {
                missingRequired.add(permission);
            } else {
                missingOptional.add(permission);
            }
        }

        List<String> permanentlyDenied = new ArrayList<>();
        List<String> requestable = new ArrayList<>();
        List<String> requestableRequired = new ArrayList<>();
        for (String permission : missing) {
            boolean permanent = requestedBefore.isGranted(permission)
                    && deniedBefore.isGranted(permission)
                    && !shouldShowRationale.isGranted(permission);
            if (permanent) {
                permanentlyDenied.add(permission);
                continue;
            }
            requestable.add(permission);
            if (missingRequired.contains(permission)) {
                requestableRequired.add(permission);
            }
        }

        return new RuntimeState(
                healthGranted,
                missing,
                missingRequired,
                missingOptional,
                permanentlyDenied,
                requestable,
                requestableRequired);
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
