package com.long2know.sportlogger;

import android.Manifest;

import java.util.ArrayList;
import java.util.List;

final class RecordingPermissions {
    static final String READ_HEART_RATE = "android.permission.health.READ_HEART_RATE";

    private RecordingPermissions() {
    }

    static String[] requiredForRecording(int sdkInt, int targetSdkInt) {
        List<String> permissions = new ArrayList<>();
        permissions.add(
                sdkInt >= 36 && targetSdkInt >= 36
                        ? READ_HEART_RATE
                        : Manifest.permission.BODY_SENSORS);
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        return permissions.toArray(new String[0]);
    }

    static String[] requestedOnStartup(int sdkInt, int targetSdkInt) {
        List<String> permissions = new ArrayList<>();
        for (String permission : requiredForRecording(sdkInt, targetSdkInt)) {
            permissions.add(permission);
        }
        if (sdkInt >= 33) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return permissions.toArray(new String[0]);
    }
}
