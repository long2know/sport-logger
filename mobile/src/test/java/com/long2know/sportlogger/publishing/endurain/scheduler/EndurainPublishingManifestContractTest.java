package com.long2know.sportlogger.publishing.endurain.scheduler;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public final class EndurainPublishingManifestContractTest {
    private static final String ANDROID_NAMESPACE =
            "http://schemas.android.com/apk/res/android";

    @Test
    public void persistedConnectivityJobHasRequiredManifestContract()
            throws Exception {
        Document manifest = manifest();

        assertNotNull(permission(
                manifest,
                "android.permission.ACCESS_NETWORK_STATE"));
        assertNotNull(permission(
                manifest,
                "android.permission.INTERNET"));
        assertNotNull(permission(
                manifest,
                "android.permission.RECEIVE_BOOT_COMPLETED"));

        Element service = service(
                manifest,
                ".publishing.endurain.scheduler.EndurainPublishingJobService");
        assertNotNull(service);
        assertEquals(
                "false",
                service.getAttributeNS(ANDROID_NAMESPACE, "exported"));
        assertEquals(
                "android.permission.BIND_JOB_SERVICE",
                service.getAttributeNS(ANDROID_NAMESPACE, "permission"));
    }

    private static Document manifest() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(
                modulePath("src/main/AndroidManifest.xml").toFile());
    }

    private static Element permission(Document document, String name) {
        NodeList permissions = document.getElementsByTagName("uses-permission");
        for (int index = 0; index < permissions.getLength(); index++) {
            Element permission = (Element) permissions.item(index);
            if (name.equals(permission.getAttributeNS(
                    ANDROID_NAMESPACE,
                    "name"))) {
                return permission;
            }
        }
        return null;
    }

    private static Element service(Document document, String name) {
        NodeList services = document.getElementsByTagName("service");
        for (int index = 0; index < services.getLength(); index++) {
            Element service = (Element) services.item(index);
            if (name.equals(service.getAttributeNS(
                    ANDROID_NAMESPACE,
                    "name"))) {
                return service;
            }
        }
        return null;
    }

    private static Path modulePath(String relative) {
        Path direct = Paths.get(relative);
        if (Files.exists(direct)) {
            return direct;
        }
        return Paths.get("mobile").resolve(relative);
    }
}
