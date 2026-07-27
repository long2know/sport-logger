package com.long2know.sportlogger;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecordingPermissionContractTest {
    private static final String ANDROID_NAMESPACE =
            "http://schemas.android.com/apk/res/android";

    @Test
    public void manifestDeclaresLegacyAndApi36BackgroundSensorPermissions() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document manifest = factory.newDocumentBuilder()
                .parse(findRepositoryFile("wear/src/main/AndroidManifest.xml").toFile());
        NodeList permissionElements = manifest.getElementsByTagName("uses-permission");
        Set<String> permissions = new HashSet<>();
        for (int index = 0; index < permissionElements.getLength(); index++) {
            Element permission = (Element) permissionElements.item(index);
            permissions.add(permission.getAttributeNS(ANDROID_NAMESPACE, "name"));
        }

        assertTrue(permissions.contains("android.permission.BODY_SENSORS"));
        assertTrue(permissions.contains(RecordingPermissions.BODY_SENSORS_BACKGROUND));
        assertTrue(permissions.contains(RecordingPermissions.READ_HEART_RATE));
        assertTrue(permissions.contains(
                RecordingPermissions.READ_HEALTH_DATA_IN_BACKGROUND));
    }

    @Test
    public void buildFoundationDocumentsSeparateBackgroundPermissionGate() throws Exception {
        String documentation = new String(
                Files.readAllBytes(findRepositoryFile("docs/build-foundation.md")),
                StandardCharsets.UTF_8);

        assertTrue(documentation.contains("`BODY_SENSORS_BACKGROUND`"));
        assertTrue(documentation.contains("`READ_HEALTH_DATA_IN_BACKGROUND`"));
        assertTrue(documentation.contains("separate runtime request"));
        assertTrue(documentation.contains("recording remains gated"));
        assertTrue(documentation.contains("hard-restricted permission"));
        assertTrue(documentation.contains("single-generation state machine"));
        assertTrue(documentation.contains("immutable ID"));
        assertTrue(documentation.contains("typed failure"));
        assertTrue(documentation.contains("service-instance listener group"));
        assertTrue(documentation.contains("one main-thread handler"));
        assertTrue(documentation.contains("rejects start and resume"));
    }

    @Test
    public void permissionLossQueuesBoundedFencesBeforeNotificationAndShutdown()
            throws Exception {
        String service = new String(
                Files.readAllBytes(findRepositoryFile(
                        "wear/src/main/java/com/long2know/sportlogger/services/"
                                + "SportLoggerService.java")),
                StandardCharsets.UTF_8);
        int handlerStart = service.indexOf(
                "private void handleRecordingPermissionLoss(long serviceGeneration)");
        int handlerEnd = service.indexOf(
                "private void runPermissionLoss(", handlerStart);
        String handler = service.substring(handlerStart, handlerEnd);
        int workerStart = handlerEnd;
        int workerEnd = service.indexOf(
                "private void failWithoutRecovery(", workerStart);
        String worker = service.substring(workerStart, workerEnd);
        int completionStart = service.indexOf(
                "private void postPermissionLossCompletion(");
        int completionEnd = service.indexOf(
                "private void postOperationCompletion(", completionStart);
        String completion = service.substring(completionStart, completionEnd);

        assertTrue(handler.contains("_operations.invalidateActive();"));
        assertTrue(handler.contains("_operations.tryExecute(permissionToken"));
        assertFalse(handler.contains("WRITERS.fence"));
        assertFalse(handler.contains("releaseOwnedListeners();"));
        assertTrue(worker.contains("WRITERS.fenceGeneration("));
        assertTrue(worker.contains("WRITERS.fenceOwned("));
        assertTrue(worker.contains("releaseOwnedListeners();"));
        assertTrue(worker.contains("_stopWatch.resetTimer();"));
        assertTrue(worker.contains(
                "postPermissionLossCompletion(permissionToken);"));
        assertTrue(completion.contains("client.onRecordingPermissionLost();"));
        assertTrue(completion.contains("stopSelf();"));
        assertTrue(service.contains("WRITER_FENCE_TIMEOUT_MILLIS"));
        assertTrue(service.contains("LISTENER_FENCE_TIMEOUT_MILLIS"));
        assertTrue(service.contains("postLifecycleFailure"));
        assertTrue(service.contains("_permissionLossHandled"));
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
