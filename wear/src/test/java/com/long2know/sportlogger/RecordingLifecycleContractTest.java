package com.long2know.sportlogger;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecordingLifecycleContractTest {
    @Test
    public void writerAndExportUseCapturedActivityId() throws Exception {
        String service = read(
                "wear/src/main/java/com/long2know/sportlogger/services/"
                        + "SportLoggerService.java");
        String logger = read(
                "utilities/src/main/java/com/long2know/utilities/data_access/"
                        + "SqlLogger.java");
        String activity = read(
                "wear/src/main/java/com/long2know/sportlogger/MainActivity.java");

        assertTrue(service.contains("new SqlLogger(activityId)"));
        assertTrue(logger.contains("public SqlLogger(int activityId)"));
        assertTrue(logger.contains("int activityId = _activityId;"));
        assertFalse(logger.contains("int activityId = singleton.ActivityId"));
        assertTrue(activity.contains("int activityId = result.getActivityId();"));
        assertTrue(activity.contains("getTrackPointsByActivity(activityId)"));
    }

    @Test
    public void listenersHaveNoProcessWideWorkerHandler() throws Exception {
        String sensors = read(
                "wear/src/main/java/com/long2know/sportlogger/services/"
                        + "SensorListener.java");
        String gps = read(
                "wear/src/main/java/com/long2know/sportlogger/services/"
                        + "GpsListener.java");
        String service = read(
                "wear/src/main/java/com/long2know/sportlogger/services/"
                        + "SportLoggerService.java");

        assertFalse(sensors.contains("static Handler"));
        assertFalse(gps.contains("static Handler"));
        assertFalse(service.contains("WorkerHandler"));
        assertTrue(service.contains("OwnedListenerRegistry"));
        assertTrue(service.contains("LISTENERS.replace("));
        assertTrue(service.contains("LISTENERS.release("));
        assertTrue(service.contains("if (_serviceClient == client)"));
    }

    @Test
    public void sensorFragmentUsesOneCancellableHandlerLoop() throws Exception {
        String fragment = read(
                "wear/src/main/java/com/long2know/sportlogger/SensorFragment.java");

        assertTrue(fragment.contains("CancellableCallbackLoop"));
        assertTrue(fragment.contains("public void onResume()"));
        assertTrue(fragment.contains("public void onPause()"));
        assertTrue(fragment.contains("public void onDestroyView()"));
        assertFalse(fragment.contains("Config.handler"));
        assertFalse(fragment.contains("postDelayed(this, 0)"));
    }

    @Test
    public void terminalEffectsRequireCleanWriterAndCommittedStateTransition()
            throws Exception {
        String service = read(
                "wear/src/main/java/com/long2know/sportlogger/services/"
                        + "SportLoggerService.java");
        String stop = service.substring(
                service.indexOf("public synchronized RecordingOperationResult stopActivity()"),
                service.indexOf(
                        "public synchronized RecordingOperationResult discardActivity()"));
        String discard = service.substring(
                service.indexOf(
                        "public synchronized RecordingOperationResult discardActivity()"),
                service.indexOf(
                        "public synchronized RecordingOperationResult retryRecovery()"));

        assertTrue(stop.contains("RecordingTerminalTransition.finish("));
        assertTrue(discard.contains("RecordingTerminalTransition.finish("));
        assertTrue(
                stop.indexOf("RecordingTerminalTransition.finish(")
                        < stop.indexOf("_recoveryState.clearAfterStop("));
        assertTrue(
                discard.indexOf("RecordingTerminalTransition.finish(")
                        < discard.indexOf("new SqlLogger().deleteActivity("));
        assertFalse(stop.contains(".quiesced()"));
        assertFalse(discard.contains(".quiesced()"));
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
        throw new IllegalStateException("Could not find repository file: " + relativePath);
    }
}
