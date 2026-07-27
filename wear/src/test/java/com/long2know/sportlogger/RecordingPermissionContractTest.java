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
        assertTrue(documentation.contains("pauses and resets the stopwatch"));
        assertTrue(documentation.contains("rejects start and resume"));
    }

    @Test
    public void permissionLossStopsAndResetsStopwatchBeforeServiceShutdown() throws Exception {
        String service = new String(
                Files.readAllBytes(findRepositoryFile(
                        "wear/src/main/java/com/long2know/sportlogger/services/"
                                + "SportLoggerService.java")),
                StandardCharsets.UTF_8);
        int cleanup = service.indexOf("private synchronized void handleRecordingPermissionLoss()");
        int cancelWrites = service.indexOf("cancelScheduledWrites();", cleanup);
        int stopListeners = service.indexOf("requestListenerShutdown();", cleanup);
        int pause = service.indexOf("_stopWatch.pauseTimer();", cleanup);
        int reset = service.indexOf("_stopWatch.resetTimer();", cleanup);
        int callback = service.indexOf("_serviceClient.onRecordingPermissionLost();", cleanup);
        int shutdown = service.indexOf("stopSelf();", cleanup);

        assertTrue(cleanup >= 0);
        assertTrue(cancelWrites > cleanup);
        assertTrue(stopListeners > cancelWrites);
        assertTrue(pause > stopListeners);
        assertTrue(reset > pause);
        assertTrue(callback > reset);
        assertTrue(shutdown > callback);
        assertTrue(service.contains("return recordingMayContinue()"));

        String start = service.substring(
                service.indexOf("public synchronized boolean startNewActivity()"),
                service.indexOf("public synchronized void stopActivity()"));
        String resume = service.substring(
                service.indexOf("public synchronized boolean resumeActivity()"),
                service.indexOf("public synchronized void discardActivity()"));
        assertTrue(start.contains("if (_permissionLossHandled)"));
        assertTrue(resume.contains("if (_permissionLossHandled)"));
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
