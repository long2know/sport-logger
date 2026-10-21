package com.long2know.sportlogger.connector.endurain;

import org.junit.Test;

import java.io.File;
import java.time.Duration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class EndurainContractTypesTest {
    @Test
    public void endpointNormalizesHttpsOriginAndOptionalBasePath() {
        EndurainEndpoint endpoint =
                EndurainEndpoint.parse(" https://EXAMPLE.com:443/endurain/ ");

        assertEquals("https://example.com/endurain", endpoint.normalizedBaseUrl());
        assertEquals(
                "https://example.com/endurain/api/v1/about",
                endpoint.aboutUri().toString());
        assertEquals(
                "https://example.com/endurain/api/v1/activities/create/upload",
                endpoint.uploadUri().toString());
    }

    @Test
    public void endpointRejectsCleartextCredentialsQueryFragmentAndUnsafePaths() {
        assertInvalidEndpoint("http://example.com");
        assertInvalidEndpoint("https://user:secret@example.com");
        assertInvalidEndpoint("https://example.com?api_key=forbidden");
        assertInvalidEndpoint("https://example.com/#fragment");
        assertInvalidEndpoint("https://example.com/a/../b");
        assertInvalidEndpoint("https://example.com/a%2fb");
        assertInvalidEndpoint("https://example.com/a//b");
    }

    @Test
    public void uploadArtifactRejectsMultipartFilenameInjection() {
        try {
            new TcxUploadArtifact(
                    "artifact-1",
                    1,
                    new File("activity.tcx"),
                    "activity.tcx\"\r\nX-Evil: true",
                    10,
                    repeat('a', 64));
            fail("Expected unsafe multipart filename to be rejected");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().contains("X-Evil"));
        }
    }

    @Test
    public void connectionIdentityScopesRemoteIdsAndDeclaresNoDelete() {
        EndurainConnectionConfig config = new EndurainConnectionConfig(
                EndurainEndpoint.parse("https://example.com"),
                "connection-1");

        EndurainRemoteActivityId remote = config.remoteActivity(42);

        assertEquals("https://example.com", remote.normalizedBaseUrl());
        assertEquals("connection-1", remote.connectionIdentity());
        assertEquals(42, remote.activityId());
        assertTrue(config.capabilities().contains(EndurainCapability.PUBLISH_TCX));
        assertTrue(config.capabilities().contains(
                EndurainCapability.RECONCILE_UNKNOWN_DELIVERY));
        assertFalse(config.supportsDelete());
    }

    @Test
    public void transportBoundsCannotBeDisabledByCallers() {
        try {
            new EndurainHttpsTransport(
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    EndurainHttpsTransport.DEFAULT_MAX_TCX_BYTES + 1,
                    EndurainHttpsTransport.DEFAULT_MAX_RESPONSE_BYTES);
            fail("Expected payload bound to be enforced");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
        try {
            new EndurainHttpsTransport(
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    EndurainHttpsTransport.DEFAULT_MAX_TCX_BYTES,
                    EndurainHttpsTransport.DEFAULT_MAX_RESPONSE_BYTES + 1);
            fail("Expected response bound to be enforced");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    private static void assertInvalidEndpoint(String value) {
        try {
            EndurainEndpoint.parse(value);
            fail("Expected endpoint to be rejected");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
