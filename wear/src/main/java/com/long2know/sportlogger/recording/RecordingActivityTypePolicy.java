package com.long2know.sportlogger.recording;

import com.long2know.utilities.contract.proto.ActivityType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public final class RecordingActivityTypePolicy {
    private static final Set<ActivityType> SUPPORTED = Collections.unmodifiableSet(
            EnumSet.of(
                    ActivityType.ACTIVITY_TYPE_RUNNING,
                    ActivityType.ACTIVITY_TYPE_WALKING,
                    ActivityType.ACTIVITY_TYPE_CYCLING,
                    ActivityType.ACTIVITY_TYPE_HIKING,
                    ActivityType.ACTIVITY_TYPE_SWIMMING,
                    ActivityType.ACTIVITY_TYPE_STRENGTH,
                    ActivityType.ACTIVITY_TYPE_OTHER));

    private RecordingActivityTypePolicy() {
    }

    public static Set<ActivityType> supportedTypes() {
        return SUPPORTED;
    }

    public static boolean isSupported(ActivityType activityType) {
        return activityType != null && SUPPORTED.contains(activityType);
    }

    public static ActivityType requireSupported(ActivityType activityType) {
        if (!isSupported(activityType)) {
            throw new IllegalArgumentException(
                    "Unsupported recording activity type " + activityType);
        }
        return activityType;
    }
}
