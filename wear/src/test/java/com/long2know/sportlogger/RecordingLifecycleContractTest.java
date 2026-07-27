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
    public void writerAndAsyncExportUseCapturedActivityId() throws Exception {
        String service = read(
                "wear/src/main/java/com/long2know/sportlogger/services/"
                        + "SportLoggerService.java");
        String logger = read(
                "utilities/src/main/java/com/long2know/utilities/data_access/"
                        + "SqlLogger.java");
        String activity = read(
                "wear/src/main/java/com/long2know/sportlogger/MainActivity.java");

        assertTrue(service.contains(
                "SqlLogger sqlLogger = new SqlLogger(activityId)"));
        assertTrue(logger.contains("public SqlLogger(int activityId)"));
        assertTrue(logger.contains(
                "implements Runnable, AutoCloseable"));
        assertTrue(logger.contains("public synchronized void close()"));
        assertTrue(logger.contains("int activityId = _activityId;"));
        assertFalse(logger.contains("int activityId = singleton.ActivityId"));
        assertTrue(activity.contains("exportActivityAsync(result)"));
        assertTrue(activity.contains(
                "getTrackPointsByActivity(activityId)"));
        assertTrue(service.contains("sqlLogger::close"));
    }

    @Test
    public void uiLifecycleEntryPointsOnlySubmitSerializedWork() throws Exception {
        String service = read(
                "wear/src/main/java/com/long2know/sportlogger/services/"
                        + "SportLoggerService.java");

        assertActionSubmitsOnly(service, "startNewActivity", "pauseActivity");
        assertActionSubmitsOnly(service, "pauseActivity", "resumeActivity");
        assertActionSubmitsOnly(service, "resumeActivity", "stopActivity");
        assertActionSubmitsOnly(service, "stopActivity", "discardActivity");
        assertActionSubmitsOnly(service, "discardActivity", "retryRecovery");
        String recovery = method(service, "public RecordingOperationResult retryRecovery()",
                "private RecordingOperationResult submitOperation");
        assertTrue(recovery.contains("submitOperation("));
        assertFalse(recovery.contains("fence"));
        assertFalse(recovery.contains("SqlLogger"));

        assertFalse(service.contains(
                "public synchronized RecordingOperationResult startNewActivity()"));
        assertFalse(service.contains(
                "public synchronized RecordingOperationResult pauseActivity()"));
        assertFalse(service.contains(
                "public synchronized RecordingOperationResult resumeActivity()"));
        assertFalse(service.contains(
                "public synchronized RecordingOperationResult stopActivity()"));
        assertFalse(service.contains(
                "public synchronized RecordingOperationResult discardActivity()"));
        assertTrue(service.contains("RecordingOperationDispatcher"));
        assertTrue(service.contains("_operations.tryExecute(token"));
    }

    @Test
    public void serviceStateLocksContainNoFencePersistenceOrClientCallback()
            throws Exception {
        String service = read(
                "wear/src/main/java/com/long2know/sportlogger/services/"
                        + "SportLoggerService.java");
        String recovery = read(
                "wear/src/main/java/com/long2know/sportlogger/services/"
                        + "RecordingRecoveryState.java");

        assertSynchronizedBlocksExclude(
                service,
                "WRITERS.fence",
                "LISTENERS.replace",
                "LISTENERS.release",
                "releaseOwnedListeners()",
                "SqlLogger.",
                "deleteActivity(",
                "onRecordingOperationCompleted(",
                "onRecordingLifecycleFailure(",
                "onRecordingPermissionLost(");
        assertSynchronizedBlocksExclude(recovery, "_store.save", "_store.clear");
    }

    private static void assertSynchronizedBlocksExclude(
            String source, String... forbiddenValues) {
        int searchFrom = 0;
        while (true) {
            int marker = source.indexOf("synchronized (this) {", searchFrom);
            if (marker < 0) {
                break;
            }
            int open = source.indexOf('{', marker);
            int close = matchingBrace(source, open);
            String block = source.substring(open, close + 1);
            for (String forbidden : forbiddenValues) {
                assertFalse(block.contains(forbidden));
            }
            searchFrom = close + 1;
        }
    }

    @Test
    public void terminalEffectsRequireOwnedCommittedOperation() throws Exception {
        String service = read(
                "wear/src/main/java/com/long2know/sportlogger/services/"
                        + "SportLoggerService.java");
        String stop = method(
                service, "private void runStop(", "private void runDiscard(");
        String discard = method(
                service, "private void runDiscard(", "private void runRecovery(");

        assertTrue(stop.contains("RecordingTerminalTransition.finish("));
        assertTrue(discard.contains("RecordingTerminalTransition.finish("));
        assertTrue(stop.contains("if (!operationOwns(token))"));
        assertTrue(discard.contains("if (!operationOwns(token))"));
        assertTrue(stop.contains("releaseOwnedListeners()"));
        assertTrue(discard.contains("releaseOwnedListeners()"));
        assertTrue(
                stop.indexOf("RecordingTerminalTransition.finish(")
                        < stop.indexOf("_recoveryState.clearAfterStop("));
        assertTrue(
                stop.indexOf("_terminalCompletions.recordSuccessfulStop(")
                        < stop.indexOf(
                                "RecordingTerminalTransition.finish("));
        int terminalRecord = stop.indexOf(
                "_terminalCompletions.recordSuccessfulStop(");
        int finalOwnershipCheck = stop.lastIndexOf(
                "if (!operationOwns(token))", terminalRecord);
        assertTrue(finalOwnershipCheck >= 0);
        assertTrue(finalOwnershipCheck < terminalRecord);
        assertTrue(
                discard.indexOf("RecordingTerminalTransition.finish(")
                        < discard.indexOf("sqlLogger.deleteActivity("));
    }

    @Test
    public void exportAndNavigationExistOnlyInAsyncSuccessCallback()
            throws Exception {
        String activity = read(
                "wear/src/main/java/com/long2know/sportlogger/MainActivity.java");
        String stopAction = method(
                activity, "public void stopActivity()", "public void pauseActivity()");
        String completion = method(
                activity,
                "public void onRecordingOperationCompleted(",
                "public void onRecordingLifecycleFailure(");

        assertFalse(stopAction.contains("SqlLogger"));
        assertFalse(stopAction.contains("exportActivityAsync("));
        assertTrue(completion.contains("if (!result.isSuccess())"));
        assertTrue(completion.contains("case STOP:"));
        assertTrue(completion.contains("exportActivityAsync(result)"));
        assertTrue(completion.contains("showStartScreenIfPossible()"));
        assertTrue(activity.contains("_exportExecutor.execute("));
        assertTrue(activity.contains("addOnSuccessListener"));
        assertTrue(activity.contains("acknowledgeTerminalCompletion("));
        assertTrue(activity.contains("addOnFailureListener"));
        assertTrue(activity.contains("releaseTerminalCompletion("));
    }

    @Test
    public void stopwatchStartupAndCleanupShareFinalOwnershipBoundary()
            throws Exception {
        String service = read(
                "wear/src/main/java/com/long2know/sportlogger/services/"
                        + "SportLoggerService.java");
        String start = method(
                service, "private void runStart(", "private void runPause(");
        String resume = method(
                service, "private void runResume(", "private void runStop(");
        String writerFailure = method(
                service,
                "private void runWriterFailureCleanup(",
                "private void handleRecordingPermissionLoss(");
        String permissionLoss = method(
                service,
                "private void runPermissionLoss(",
                "private void failWithoutRecovery(");
        String destroyCleanup = method(
                service,
                "private void scheduleDestroyCleanup(",
                "private void initializeRetainedMirror(");

        assertTrue(start.contains("RecordingStartCommit.commit("));
        assertTrue(resume.contains("RecordingStartCommit.commit("));
        assertTrue(start.contains("_stopWatch.startTImer();"));
        assertTrue(resume.contains("_stopWatch.startTImer();"));
        assertTrue(
                writerFailure.indexOf("WRITERS.fenceGeneration(")
                        < writerFailure.lastIndexOf(
                                "_stopWatch.pauseTimer();"));
        assertTrue(
                permissionLoss.indexOf("WRITERS.fence")
                        < permissionLoss.lastIndexOf(
                                "_stopWatch.pauseTimer();"));
        assertTrue(
                destroyCleanup.indexOf("WRITERS.fence")
                        < destroyCleanup.lastIndexOf(
                                "_stopWatch.pauseTimer();"));
    }

    @Test
    public void writerFactoriesRunOutsideCoordinatorMonitor() throws Exception {
        String coordinator = read(
                "wear/src/main/java/com/long2know/sportlogger/services/"
                        + "RecordingWriterCoordinator.java");

        assertFalse(coordinator.contains(
                "synchronized StartStatus start("));
        assertSynchronizedBlocksExclude(
                coordinator,
                "taskFactory.create(",
                "_schedulerFactory.create()");
        assertTrue(coordinator.contains("Reservation"));
        assertTrue(coordinator.contains("reservationOwns("));
        assertTrue(coordinator.contains("closePrepared("));
    }

    @Test
    public void stopExportIsDurableUntilExplicitSuccessAcknowledgment()
            throws Exception {
        String service = read(
                "wear/src/main/java/com/long2know/sportlogger/services/"
                        + "SportLoggerService.java");
        String activity = read(
                "wear/src/main/java/com/long2know/sportlogger/MainActivity.java");
        String stop = method(
                service, "private void runStop(", "private void runDiscard(");
        String destroy = method(
                service, "public void onDestroy()", "public void setServiceClient(");

        assertTrue(
                stop.indexOf("_terminalCompletions.recordSuccessfulStop(")
                        < stop.indexOf("_recoveryState.clearAfterStop("));
        assertTrue(service.contains(
                "SharedPreferencesRecordingTerminalCompletionStore"));
        assertTrue(service.contains("deliverPendingTerminalCompletion()"));
        assertTrue(service.contains(
                "public boolean acknowledgeTerminalCompletion("));
        assertTrue(service.contains(
                "_terminalCompletions.protectsActivity("));
        assertFalse(destroy.contains(
                "_terminalCompletions.acknowledge("));
        assertTrue(activity.contains(
                "service.acknowledgeTerminalCompletion("));
        assertTrue(activity.contains(
                "service.releaseTerminalCompletion("));
    }

    @Test
    public void acceptedOrDuplicateRequestsDisableRecordingControls()
            throws Exception {
        String activity = read(
                "wear/src/main/java/com/long2know/sportlogger/MainActivity.java");
        String start = read(
                "wear/src/main/java/com/long2know/sportlogger/"
                        + "StartActivityFragment.java");
        String end = read(
                "wear/src/main/java/com/long2know/sportlogger/"
                        + "EndActivityFragment.java");
        String recovery = read(
                "wear/src/main/java/com/long2know/sportlogger/"
                        + "RecoveryActivityFragment.java");

        assertTrue(activity.contains(
                "if (result.isAccepted() || result.isPending())"));
        assertTrue(activity.contains("getPendingOperation()"));
        assertTrue(activity.contains("setRecordingControlsPending(true)"));
        assertTrue(activity.contains("clearPendingOperation()"));
        assertTrue(start.contains("_start.setEnabled(!_operationPending)"));
        assertTrue(end.contains("button.setEnabled(!_operationPending)"));
        assertTrue(recovery.contains("_retry.setEnabled(!_operationPending)"));
    }

    @Test
    public void completionIsPublishedBeforeItsOperationTokenIsReleased()
            throws Exception {
        String service = read(
                "wear/src/main/java/com/long2know/sportlogger/services/"
                        + "SportLoggerService.java");
        String completion = method(
                service,
                "private void postOperationCompletion(",
                "private void postLifecycleFailure(");
        String failure = method(
                service,
                "private void postLifecycleFailure(",
                "private void postMainForGeneration(");
        String submission = method(
                service,
                "private RecordingOperationResult submitOperation(",
                "private void runOperation(");

        assertTrue(completion.contains("_operations.owns(token)"));
        assertTrue(
                completion.indexOf("_pendingOperationCompletion = completion;")
                        < completion.indexOf("_operations.finish(token);"));
        assertTrue(failure.contains("_operations.owns(token)"));
        assertTrue(
                failure.indexOf("_pendingLifecycleFailure = publishedFailure;")
                        < failure.indexOf("_operations.finish(token);"));
        assertTrue(submission.contains("_pendingLifecycleFailure"));
    }

    @Test
    public void listenersHaveNoProcessWideWorkerHandlerOrRegistryWaitLock()
            throws Exception {
        String sensors = read(
                "wear/src/main/java/com/long2know/sportlogger/services/"
                        + "SensorListener.java");
        String gps = read(
                "wear/src/main/java/com/long2know/sportlogger/services/"
                        + "GpsListener.java");
        String registry = read(
                "wear/src/main/java/com/long2know/sportlogger/services/"
                        + "OwnedListenerRegistry.java");
        String service = read(
                "wear/src/main/java/com/long2know/sportlogger/services/"
                        + "SportLoggerService.java");

        assertFalse(sensors.contains("static Handler"));
        assertFalse(gps.contains("static Handler"));
        assertTrue(sensors.contains("synchronized (_ownerActive)"));
        assertTrue(gps.contains("synchronized (_ownerActive)"));
        assertFalse(sensors.contains(
                "SharedData.getInstance().setHeartRate(0)"));
        assertFalse(gps.contains(
                "SharedData.getInstance().setLocation(new LocationData())"));
        assertFalse(registry.contains(
                "synchronized LifecycleTermination replace"));
        assertFalse(registry.contains(
                "synchronized LifecycleTermination release"));
        assertTrue(registry.contains("previous.shutdown(timeoutMillis)"));
        assertTrue(registry.contains("owner.shutdown(timeoutMillis)"));
        assertTrue(service.contains(
                "final AtomicBoolean ownerActive = new AtomicBoolean(false)"));
        assertTrue(service.contains("_startingListenerGroup = group;"));
        assertTrue(service.contains(
                "startingListenerGroup.requestShutdown();"));
        assertTrue(service.contains(
                "new OwnedListenerRegistry.OwnershipClaim()"));
        assertTrue(service.contains(
                "_listenerGeneration != listenerGeneration"));
        assertTrue(service.contains(
                "&& _operations.owns(operationToken)"));
        assertTrue(service.contains("listenerGroup.requestShutdown();"));
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

    private static void assertActionSubmitsOnly(
            String service, String action, String nextAction) {
        String body = method(
                service,
                "public RecordingOperationResult " + action + "()",
                "public RecordingOperationResult " + nextAction + "()");
        assertTrue(body.contains("submitOperation("));
        assertFalse(body.contains("WRITERS."));
        assertFalse(body.contains("releaseOwnedListeners"));
        assertFalse(body.contains("SqlLogger"));
        assertFalse(body.contains("await"));
    }

    private static String method(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        if (startIndex < 0 || endIndex < 0) {
            throw new IllegalStateException(
                    "Could not find method range: " + start + " -> " + end);
        }
        return source.substring(startIndex, endIndex);
    }

    private static int matchingBrace(String source, int openBrace) {
        int depth = 0;
        for (int index = openBrace; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return index;
            }
        }
        throw new IllegalStateException("Unbalanced source block");
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
