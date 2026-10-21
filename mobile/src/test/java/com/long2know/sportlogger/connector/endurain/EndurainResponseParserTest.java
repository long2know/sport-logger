package com.long2know.sportlogger.connector.endurain;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class EndurainResponseParserTest {
    @Test
    public void parsesEveryTopLevelIntegerActivityId() throws Exception {
        byte[] body = ("["
                + "{\"id\":41,\"name\":\"Run\",\"import_info\":{\"id\":999}},"
                + "{\"name\":\"Ride\",\"id\":42,\"distance\":1200,"
                + "\"flags\":[true,false,null]}"
                + "]").getBytes(StandardCharsets.UTF_8);

        assertEquals(List.of(41L, 42L), EndurainResponseParser.parseActivityIds(body));
    }

    @Test
    public void parsesCanonicalAboutResponse() throws Exception {
        byte[] body = ("{\"name\":\"Endurain API\",\"version\":\"v0.19.0\","
                + "\"license\":{\"identifier\":\"AGPL-3.0-or-later\"}}")
                .getBytes(StandardCharsets.UTF_8);

        EndurainResponseParser.About about =
                EndurainResponseParser.parseAbout(body);

        assertEquals("Endurain API", about.name());
        assertEquals("v0.19.0", about.version());
    }

    @Test
    public void acceptsRawUtf8AndEscapedSupplementaryCharacters() throws Exception {
        assertEquals(
                List.of(41L),
                EndurainResponseParser.parseActivityIds(
                        "[{\"id\":41,\"name\":\"Run 🏃\"}]"
                                .getBytes(StandardCharsets.UTF_8)));
        assertEquals(
                List.of(42L),
                EndurainResponseParser.parseActivityIds(
                        "[{\"id\":42,\"name\":\"Run \\uD83C\\uDFC3\"}]"
                                .getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void rejectsEmptyMissingDuplicateFractionalAndOutOfRangeIds() {
        assertInvalid("[]");
        assertInvalid("[{}]");
        assertInvalid("[{\"id\":1,\"id\":2}]");
        assertInvalid("[{\"id\":1.5}]");
        assertInvalid("[{\"id\":0}]");
        assertInvalid("[{\"id\":9223372036854775808}]");
        assertInvalid("[{\"id\":7},{\"id\":7}]");
    }

    @Test
    public void rejectsMalformedJsonTrailingDataAndInvalidUtf8() {
        assertInvalid("[{\"id\":1]");
        assertInvalid("[{\"id\":1}]{}");
        assertInvalid("[{\"id\":1,\"value\":1e+-2}]");
        try {
            EndurainResponseParser.parseActivityIds(
                    new byte[]{'[', '{', '"', 'i', 'd', '"', ':', (byte) 0xc3, '}', ']'});
            fail("Expected invalid UTF-8 to be rejected");
        } catch (EndurainResponseParser.ParseException expected) {
            assertEquals("Response is not valid UTF-8", expected.getMessage());
        }
    }

    @Test
    public void rejectsUnmatchedSurrogateEscapes() {
        assertInvalid("[{\"id\":1,\"name\":\"\\uD83C\"}]");
        assertInvalid("[{\"id\":1,\"name\":\"\\uDFC3\"}]");
        assertInvalid("[{\"id\":1,\"name\":\"\\uD83C\\u0041\"}]");
    }

    private static void assertInvalid(String json) {
        try {
            EndurainResponseParser.parseActivityIds(
                    json.getBytes(StandardCharsets.UTF_8));
            fail("Expected JSON to be rejected");
        } catch (EndurainResponseParser.ParseException expected) {
            assertEquals(false, expected.getMessage().isEmpty());
        }
    }
}
