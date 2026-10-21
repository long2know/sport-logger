package com.long2know.sportlogger.acquisition;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ExerciseOwnershipPolicyTest {
    @Test
    public void anotherAppsExerciseIsNeverClaimedOrEnded() {
        assertEquals(
                ExerciseOwnershipPolicy.Action.BLOCKED_BY_OTHER_APP,
                ExerciseOwnershipPolicy.forUserStart(
                        ExerciseOwnershipPolicy.PlatformStatus.OTHER_APP_IN_PROGRESS,
                        true));
        assertEquals(
                ExerciseOwnershipPolicy.Action.BLOCKED_BY_OTHER_APP,
                ExerciseOwnershipPolicy.forRecovery(
                        true,
                        ExerciseOwnershipPolicy.PlatformStatus.OTHER_APP_IN_PROGRESS,
                        true,
                        false));
    }

    @Test
    public void ownedExerciseIsReattachedAfterProcessRestart() {
        assertEquals(
                ExerciseOwnershipPolicy.Action.REATTACH_OWNED,
                ExerciseOwnershipPolicy.forRecovery(
                        true,
                        ExerciseOwnershipPolicy.PlatformStatus.OWNED_EXERCISE_IN_PROGRESS,
                        true,
                        false));
    }

    @Test
    public void missingPlatformSessionKeepsLocalDataAsInterrupted() {
        assertEquals(
                ExerciseOwnershipPolicy.Action.MARK_LOCAL_INTERRUPTED,
                ExerciseOwnershipPolicy.forRecovery(
                        true,
                        ExerciseOwnershipPolicy.PlatformStatus.NO_EXERCISE_IN_PROGRESS,
                        true,
                        false));
        assertEquals(
                ExerciseOwnershipPolicy.Action.MARK_LOCAL_INTERRUPTED,
                ExerciseOwnershipPolicy.forRecovery(
                        true,
                        ExerciseOwnershipPolicy.PlatformStatus.UNKNOWN,
                        true,
                        true));
    }
}
