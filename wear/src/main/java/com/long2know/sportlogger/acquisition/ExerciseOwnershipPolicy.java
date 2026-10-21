package com.long2know.sportlogger.acquisition;

import java.util.Objects;

public final class ExerciseOwnershipPolicy {
    public enum PlatformStatus {
        UNKNOWN,
        NO_EXERCISE_IN_PROGRESS,
        OWNED_EXERCISE_IN_PROGRESS,
        OTHER_APP_IN_PROGRESS
    }

    public enum Action {
        START_NEW,
        REATTACH_OWNED,
        USE_RAW_FALLBACK,
        KEEP_IDLE,
        MARK_LOCAL_INTERRUPTED,
        BLOCKED_BY_OTHER_APP,
        WAIT_FOR_PLATFORM_STATUS
    }

    private ExerciseOwnershipPolicy() {
    }

    public static Action forUserStart(
            PlatformStatus platformStatus,
            boolean healthServicesAvailable) {
        Objects.requireNonNull(platformStatus);
        if (platformStatus == PlatformStatus.OTHER_APP_IN_PROGRESS) {
            return Action.BLOCKED_BY_OTHER_APP;
        }
        if (platformStatus == PlatformStatus.OWNED_EXERCISE_IN_PROGRESS) {
            return Action.REATTACH_OWNED;
        }
        if (platformStatus == PlatformStatus.UNKNOWN && healthServicesAvailable) {
            return Action.WAIT_FOR_PLATFORM_STATUS;
        }
        return healthServicesAvailable ? Action.START_NEW : Action.USE_RAW_FALLBACK;
    }

    public static Action forRecovery(
            boolean localRecordingActive,
            PlatformStatus platformStatus,
            boolean healthServicesAvailable,
            boolean bootInterrupted) {
        Objects.requireNonNull(platformStatus);
        if (platformStatus == PlatformStatus.OTHER_APP_IN_PROGRESS) {
            return Action.BLOCKED_BY_OTHER_APP;
        }
        if (platformStatus == PlatformStatus.OWNED_EXERCISE_IN_PROGRESS) {
            return Action.REATTACH_OWNED;
        }
        if (!localRecordingActive) {
            return Action.KEEP_IDLE;
        }
        if (bootInterrupted
                || platformStatus == PlatformStatus.NO_EXERCISE_IN_PROGRESS) {
            return Action.MARK_LOCAL_INTERRUPTED;
        }
        if (platformStatus == PlatformStatus.UNKNOWN && healthServicesAvailable) {
            return Action.WAIT_FOR_PLATFORM_STATUS;
        }
        return healthServicesAvailable
                ? Action.MARK_LOCAL_INTERRUPTED
                : Action.USE_RAW_FALLBACK;
    }
}
