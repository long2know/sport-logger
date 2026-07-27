package com.long2know.sportlogger;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecordingRecoveryContractTest {
    @Test
    public void replacementUsesPersistedTupleAndExactGenerationFence() throws Exception {
        String service = read(
                "wear/src/main/java/com/long2know/sportlogger/services/"
                        + "SportLoggerService.java");
        String store = read(
                "wear/src/main/java/com/long2know/sportlogger/services/"
                        + "SharedPreferencesRecordingRecoveryStore.java");
        String coordinator = read(
                "wear/src/main/java/com/long2know/sportlogger/services/"
                        + "RecordingWriterCoordinator.java");
        String logger = read(
                "utilities/src/main/java/com/long2know/utilities/data_access/"
                        + "SqlLogger.java");

        assertTrue(service.contains("restoreRetainedRecording()"));
        assertTrue(service.contains("SqlLogger.activityExists("));
        assertTrue(service.contains("WRITERS.restoreGenerationFloor("));
        assertTrue(service.contains("WRITERS.fenceGeneration("));
        assertTrue(store.contains(".commit()"));
        assertFalse(store.contains("static RecordingRecoveryState"));
        assertTrue(coordinator.contains("generationFor("));
        assertTrue(logger.contains("public static boolean activityExists("));
    }

    @Test
    public void startupFailureAndOwnedFailureRenderDifferentFlows() throws Exception {
        String service = read(
                "wear/src/main/java/com/long2know/sportlogger/services/"
                        + "SportLoggerService.java");
        String activity = read(
                "wear/src/main/java/com/long2know/sportlogger/MainActivity.java");

        assertTrue(service.contains("RecoveryAction.RETURN_TO_START"));
        assertTrue(service.contains("RecoveryAction.SHOW_PAUSED_CONTROLS"));
        assertTrue(service.contains("RecoveryAction.SHOW_RECOVERY_RETRY"));
        assertTrue(activity.contains("RecoveryActivityFragment"));
        assertTrue(activity.contains("retryRecordingRecovery()"));
        assertTrue(activity.contains("renderRecordingStatus("));

        int callback = activity.indexOf(
                "public void onRecordingLifecycleFailure(RecordingOperationResult result)");
        int nextMethod = activity.indexOf(
                "private void startAndBindServiceIfPermitted()", callback);
        String failureCallback = activity.substring(callback, nextMethod);
        assertFalse(failureCallback.contains(
                "handleRecordingFailure(result);\n"
                        + "        stopLoggingServiceForMissingPermissions();"));
    }

    @Test
    public void recoveredControlsReachResumeStopAndDiscardServiceOperations()
            throws Exception {
        String activity = read(
                "wear/src/main/java/com/long2know/sportlogger/MainActivity.java");
        String service = read(
                "wear/src/main/java/com/long2know/sportlogger/services/"
                        + "SportLoggerService.java");

        assertTrue(activity.contains("_loggingService.retryRecovery()"));
        assertTrue(activity.contains("_loggingService.resumeActivity()"));
        assertTrue(activity.contains("_loggingService.stopActivity()"));
        assertTrue(activity.contains("_loggingService.discardActivity()"));
        assertTrue(service.contains("_recoveryState.recordRecording("));
        assertTrue(service.contains("_recoveryState.clearAfterStop("));
        assertTrue(service.contains("_recoveryState.clearAfterDiscard("));
    }

    private static String read(String relativePath) throws Exception {
        return new String(
                Files.readAllBytes(findRepositoryFile(relativePath)),
                StandardCharsets.UTF_8);
    }

    private static Path findRepositoryFile(String relativePath) {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "Could not find repository file: " + relativePath);
    }
}
