package com.long2know.sportlogger.services;

import android.content.pm.ServiceInfo;

import androidx.annotation.RequiresApi;

import java.util.Objects;

@RequiresApi(34)
final class ForegroundServiceController {
    interface Starter {
        void start(int types);
    }

    interface ErrorReporter {
        void report(String message, Throwable error);
    }

    private final Starter starter;
    private final ErrorReporter errorReporter;
    private int activeTypes;
    private boolean missingPrerequisiteReported;

    ForegroundServiceController(Starter starter, ErrorReporter errorReporter) {
        this.starter = Objects.requireNonNull(starter);
        this.errorReporter = Objects.requireNonNull(errorReporter);
    }

    boolean ensure(
            boolean hasLocationPermission,
            boolean locationEnabled,
            boolean hasHealthPermission) {
        int requestedTypes = selectTypes(
                hasLocationPermission,
                locationEnabled,
                hasHealthPermission);
        if (requestedTypes == 0) {
            if (!missingPrerequisiteReported) {
                missingPrerequisiteReported = true;
                errorReporter.report(
                        "An enabled location provider or health permission is required to record",
                        null);
            }
            return false;
        }
        missingPrerequisiteReported = false;
        if (activeTypes == requestedTypes) {
            return true;
        }
        try {
            starter.start(requestedTypes);
            activeTypes = requestedTypes;
            return true;
        } catch (RuntimeException primaryError) {
            errorReporter.report(
                    "Could not promote recording service with requested foreground types",
                    primaryError);
            int healthOnly = requestedTypes & ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH;
            boolean includedLocation =
                    (requestedTypes & ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) != 0;
            if (includedLocation && healthOnly != 0) {
                try {
                    starter.start(healthOnly);
                    activeTypes = healthOnly;
                    return true;
                } catch (RuntimeException fallbackError) {
                    errorReporter.report(
                            "Could not preserve health-only foreground recording",
                            fallbackError);
                }
            }
            return activeTypes != 0;
        }
    }

    boolean isForeground() {
        return activeTypes != 0;
    }

    void clear() {
        activeTypes = 0;
        missingPrerequisiteReported = false;
    }

    static int selectTypes(
            boolean hasLocationPermission,
            boolean locationEnabled,
            boolean hasHealthPermission) {
        int types = 0;
        if (hasLocationPermission && locationEnabled) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        }
        if (hasHealthPermission) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH;
        }
        return types;
    }
}
