package com.long2know.sportlogger;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public final class RecordingPermissions {
    static final String READ_HEART_RATE = "android.permission.health.READ_HEART_RATE";
    static final String BODY_SENSORS_BACKGROUND =
            "android.permission.BODY_SENSORS_BACKGROUND";
    static final String READ_HEALTH_DATA_IN_BACKGROUND =
            "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND";
    static final String ACTIVITY_RECOGNITION = "android.permission.ACTIVITY_RECOGNITION";

    public interface PermissionChecker {
        boolean isGranted(String permission);
    }

    private RecordingPermissions() {
    }

    static String[] requiredWhileInUseForRecording(int sdkInt, int targetSdkInt) {
        List<String> permissions = new ArrayList<>();
        permissions.add(
                sdkInt >= 36 && targetSdkInt >= 36
                        ? READ_HEART_RATE
                        : Manifest.permission.BODY_SENSORS);
        if (sdkInt >= 29) {
            permissions.add(ACTIVITY_RECOGNITION);
        }
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        return permissions.toArray(new String[0]);
    }

    static String backgroundSensorPermissionForRecording(int sdkInt, int targetSdkInt) {
        if (sdkInt < 33 || targetSdkInt < 33) {
            return null;
        }
        if (sdkInt >= 36 && targetSdkInt >= 36) {
            return READ_HEALTH_DATA_IN_BACKGROUND;
        }
        return BODY_SENSORS_BACKGROUND;
    }

    static String[] requiredForRecording(int sdkInt, int targetSdkInt) {
        List<String> permissions = new ArrayList<>();
        for (String permission : requiredWhileInUseForRecording(sdkInt, targetSdkInt)) {
            permissions.add(permission);
        }
        String backgroundSensorPermission =
                backgroundSensorPermissionForRecording(sdkInt, targetSdkInt);
        if (backgroundSensorPermission != null) {
            permissions.add(backgroundSensorPermission);
        }
        return permissions.toArray(new String[0]);
    }

    static String[] requestedOnStartup(int sdkInt, int targetSdkInt) {
        List<String> permissions = new ArrayList<>();
        for (String permission : requiredWhileInUseForRecording(sdkInt, targetSdkInt)) {
            permissions.add(permission);
        }
        if (sdkInt >= 33) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return permissions.toArray(new String[0]);
    }

    static boolean allWhileInUseRequiredForRecordingGranted(
            int sdkInt, int targetSdkInt, PermissionChecker checker) {
        for (String permission : requiredWhileInUseForRecording(sdkInt, targetSdkInt)) {
            if (!checker.isGranted(permission)) {
                return false;
            }
        }
        return true;
    }

    static boolean backgroundSensorPermissionGranted(
            int sdkInt, int targetSdkInt, PermissionChecker checker) {
        String permission = backgroundSensorPermissionForRecording(sdkInt, targetSdkInt);
        return permission == null || checker.isGranted(permission);
    }

    static boolean allRequiredForRecordingGranted(
            int sdkInt, int targetSdkInt, PermissionChecker checker) {
        return allWhileInUseRequiredForRecordingGranted(sdkInt, targetSdkInt, checker)
                && backgroundSensorPermissionGranted(sdkInt, targetSdkInt, checker);
    }

    public static boolean allWhileInUseRequiredForRecordingGranted(Context context) {
        return allWhileInUseRequiredForRecordingGranted(
                Build.VERSION.SDK_INT,
                context.getApplicationInfo().targetSdkVersion,
                checkerFor(context));
    }

    public static boolean backgroundSensorPermissionGranted(Context context) {
        return backgroundSensorPermissionGranted(
                Build.VERSION.SDK_INT,
                context.getApplicationInfo().targetSdkVersion,
                checkerFor(context));
    }

    public static boolean allRequiredForRecordingGranted(Context context) {
        return allRequiredForRecordingGranted(
                Build.VERSION.SDK_INT,
                context.getApplicationInfo().targetSdkVersion,
                checkerFor(context));
    }

    private static PermissionChecker checkerFor(Context context) {
        return new PermissionChecker() {
            @Override
            public boolean isGranted(String permission) {
                return ContextCompat.checkSelfPermission(context, permission)
                        == PackageManager.PERMISSION_GRANTED;
            }
        };
    }
}
