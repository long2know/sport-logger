package com.long2know.sportlogger.acquisition;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class WearManifestContractTest {
    private static final String ANDROID_NAMESPACE =
            "http://schemas.android.com/apk/res/android";

    @Test
    public void api35And36HeartPermissionsAreDeclaredWithoutBackgroundAccess()
            throws Exception {
        Document manifest = manifest();
        Element bodySensors = permission(
                manifest,
                RecordingPermissionPolicy.BODY_SENSORS);
        assertNotNull(bodySensors);
        assertEquals(
                "35",
                bodySensors.getAttributeNS(ANDROID_NAMESPACE, "maxSdkVersion"));
        assertNotNull(permission(
                manifest,
                RecordingPermissionPolicy.READ_HEART_RATE));

        assertFalse(hasPermission(
                manifest,
                "android.permission.BODY_SENSORS_BACKGROUND"));
        assertFalse(hasPermission(
                manifest,
                "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"));
        assertFalse(hasPermission(
                manifest,
                "android.permission.ACCESS_BACKGROUND_LOCATION"));
    }

    @Test
    public void activeRecordingServiceDeclaresOnlyHealthAndLocationTypes()
            throws Exception {
        NodeList services = manifest().getElementsByTagName("service");
        Element recordingService = null;
        for (int index = 0; index < services.getLength(); index++) {
            Element service = (Element) services.item(index);
            if (".services.SportLoggerService".equals(
                    service.getAttributeNS(ANDROID_NAMESPACE, "name"))) {
                recordingService = service;
                break;
            }
        }
        assertNotNull(recordingService);
        assertEquals(
                "health|location",
                recordingService.getAttributeNS(
                        ANDROID_NAMESPACE,
                        "foregroundServiceType"));
        assertEquals(
                "false",
                recordingService.getAttributeNS(ANDROID_NAMESPACE, "exported"));
    }

    @Test
    public void wear30CompatibilityUsesReal110DependencyWithoutOverrideLibrary()
            throws Exception {
        String build = new String(
                Files.readAllBytes(modulePath("build.gradle")),
                StandardCharsets.UTF_8);
        String manifest = new String(
                Files.readAllBytes(modulePath("src/main/AndroidManifest.xml")),
                StandardCharsets.UTF_8);

        assertTrue(build.contains("minSdk = 30"));
        assertTrue(build.contains(
                "androidx.health:health-services-client:1.1.0"));
        assertFalse(manifest.contains("overrideLibrary"));
    }

    private static Document manifest() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(
                modulePath("src/main/AndroidManifest.xml").toFile());
    }

    private static boolean hasPermission(Document document, String name) {
        return permission(document, name) != null;
    }

    private static Element permission(Document document, String name) {
        NodeList permissions = document.getElementsByTagName("uses-permission");
        for (int index = 0; index < permissions.getLength(); index++) {
            Element permission = (Element) permissions.item(index);
            if (name.equals(permission.getAttributeNS(ANDROID_NAMESPACE, "name"))) {
                return permission;
            }
        }
        return null;
    }

    private static Path modulePath(String relative) {
        Path direct = Paths.get(relative);
        if (Files.exists(direct)) {
            return direct;
        }
        return Paths.get("wear").resolve(relative);
    }
}
