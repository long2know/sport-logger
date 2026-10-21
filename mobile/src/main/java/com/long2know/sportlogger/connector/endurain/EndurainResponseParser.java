package com.long2know.sportlogger.connector.endurain;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class EndurainResponseParser {
    private EndurainResponseParser() {
    }

    static List<Long> parseActivityIds(byte[] body) throws ParseException {
        Parser parser = new Parser(decodeUtf8(body));
        List<Long> ids = parser.readActivityList();
        parser.requireEnd();
        return ids;
    }

    static About parseAbout(byte[] body) throws ParseException {
        Parser parser = new Parser(decodeUtf8(body));
        About about = parser.readAbout();
        parser.requireEnd();
        return about;
    }

    private static String decodeUtf8(byte[] body) throws ParseException {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body));
            return decoded.toString();
        } catch (CharacterCodingException error) {
            throw new ParseException("Response is not valid UTF-8", error);
        }
    }

    static final class About {
        private final String name;
        private final String version;

        private About(String name, String version) {
            this.name = name;
            this.version = version;
        }

        String name() {
            return name;
        }

        String version() {
            return version;
        }
    }

    static final class ParseException extends Exception {
        private ParseException(String message) {
            super(message);
        }

        private ParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class Parser {
        private static final int MAX_DEPTH = 64;
        private static final int MAX_VALUES = 100_000;

        private final String input;
        private int index;
        private int values;

        private Parser(String input) {
            this.input = input;
        }

        private List<Long> readActivityList() throws ParseException {
            skipWhitespace();
            expect('[');
            skipWhitespace();
            if (consume(']')) {
                throw error("Activity list must not be empty");
            }

            List<Long> ids = new ArrayList<>();
            Set<Long> uniqueIds = new HashSet<>();
            while (true) {
                long id = readActivityObject();
                if (!uniqueIds.add(id)) {
                    throw error("Activity list contains a duplicate ID");
                }
                ids.add(id);
                skipWhitespace();
                if (consume(']')) {
                    return ids;
                }
                expect(',');
            }
        }

        private long readActivityObject() throws ParseException {
            countValue();
            skipWhitespace();
            expect('{');
            skipWhitespace();
            boolean hasId = false;
            long id = 0;
            if (consume('}')) {
                throw error("Activity object does not contain an ID");
            }
            while (true) {
                String name = readString();
                skipWhitespace();
                expect(':');
                if ("id".equals(name)) {
                    if (hasId) {
                        throw error("Activity object contains multiple IDs");
                    }
                    id = readPositiveLong();
                    hasId = true;
                } else {
                    skipValue(1);
                }
                skipWhitespace();
                if (consume('}')) {
                    if (!hasId) {
                        throw error("Activity object does not contain an ID");
                    }
                    return id;
                }
                expect(',');
            }
        }

        private About readAbout() throws ParseException {
            countValue();
            skipWhitespace();
            expect('{');
            skipWhitespace();
            String name = null;
            String version = null;
            if (consume('}')) {
                throw error("About response is empty");
            }
            while (true) {
                String field = readString();
                skipWhitespace();
                expect(':');
                if ("name".equals(field)) {
                    if (name != null) {
                        throw error("About response contains multiple names");
                    }
                    name = readStringValue();
                } else if ("version".equals(field)) {
                    if (version != null) {
                        throw error("About response contains multiple versions");
                    }
                    version = readStringValue();
                } else {
                    skipValue(1);
                }
                skipWhitespace();
                if (consume('}')) {
                    if (name == null || version == null || version.isEmpty()) {
                        throw error("About response is missing required fields");
                    }
                    return new About(name, version);
                }
                expect(',');
            }
        }

        private String readStringValue() throws ParseException {
            countValue();
            skipWhitespace();
            return readString();
        }

        private void skipValue(int depth) throws ParseException {
            if (depth > MAX_DEPTH) {
                throw error("JSON nesting is too deep");
            }
            countValue();
            skipWhitespace();
            if (index >= input.length()) {
                throw error("Unexpected end of JSON");
            }
            char current = input.charAt(index);
            if (current == '"') {
                readString();
                return;
            }
            if (current == '{') {
                index++;
                skipWhitespace();
                if (consume('}')) {
                    return;
                }
                while (true) {
                    readString();
                    skipWhitespace();
                    expect(':');
                    skipValue(depth + 1);
                    skipWhitespace();
                    if (consume('}')) {
                        return;
                    }
                    expect(',');
                }
            }
            if (current == '[') {
                index++;
                skipWhitespace();
                if (consume(']')) {
                    return;
                }
                while (true) {
                    skipValue(depth + 1);
                    skipWhitespace();
                    if (consume(']')) {
                        return;
                    }
                    expect(',');
                }
            }
            if (current == 't') {
                expectLiteral("true");
                return;
            }
            if (current == 'f') {
                expectLiteral("false");
                return;
            }
            if (current == 'n') {
                expectLiteral("null");
                return;
            }
            readNumberToken();
        }

        private long readPositiveLong() throws ParseException {
            countValue();
            skipWhitespace();
            int start = index;
            if (index >= input.length() || !isDigit(input.charAt(index))) {
                throw error("Activity ID must be an integer");
            }
            if (input.charAt(index) == '0') {
                index++;
                if (index < input.length() && isDigit(input.charAt(index))) {
                    throw error("Activity ID has an invalid leading zero");
                }
            } else {
                while (index < input.length() && isDigit(input.charAt(index))) {
                    index++;
                }
            }
            if (index < input.length()) {
                char next = input.charAt(index);
                if (next == '.' || next == 'e' || next == 'E') {
                    throw error("Activity ID must be an integer");
                }
            }
            requireDelimiter();
            try {
                long value = Long.parseLong(input.substring(start, index));
                if (value < 1) {
                    throw error("Activity ID must be positive");
                }
                return value;
            } catch (NumberFormatException error) {
                throw new ParseException("Activity ID is outside the supported range", error);
            }
        }

        private void readNumberToken() throws ParseException {
            int start = index;
            if (consume('-') && index >= input.length()) {
                throw error("Invalid JSON number");
            }
            if (consume('0')) {
                if (index < input.length() && isDigit(input.charAt(index))) {
                    throw error("Invalid JSON number");
                }
            } else {
                requireDigits();
            }
            if (consume('.')) {
                requireDigits();
            }
            if (consume('e') || consume('E')) {
                if (!consume('+')) {
                    consume('-');
                }
                requireDigits();
            }
            if (index == start) {
                throw error("Invalid JSON value");
            }
            requireDelimiter();
        }

        private String readString() throws ParseException {
            skipWhitespace();
            expect('"');
            StringBuilder value = new StringBuilder();
            while (index < input.length()) {
                char current = input.charAt(index++);
                if (current == '"') {
                    return value.toString();
                }
                if (current < 0x20) {
                    throw error("JSON string contains a control character");
                }
                if (current != '\\') {
                    if (Character.isSurrogate(current)) {
                        throw error("JSON string contains an unescaped surrogate");
                    }
                    value.append(current);
                    continue;
                }
                if (index >= input.length()) {
                    throw error("JSON string ends after an escape");
                }
                char escaped = input.charAt(index++);
                switch (escaped) {
                    case '"':
                    case '\\':
                    case '/':
                        value.append(escaped);
                        break;
                    case 'b':
                        value.append('\b');
                        break;
                    case 'f':
                        value.append('\f');
                        break;
                    case 'n':
                        value.append('\n');
                        break;
                    case 'r':
                        value.append('\r');
                        break;
                    case 't':
                        value.append('\t');
                        break;
                    case 'u':
                        appendUnicodeEscape(value);
                        break;
                    default:
                        throw error("JSON string contains an invalid escape");
                }
            }
            throw error("Unterminated JSON string");
        }

        private void appendUnicodeEscape(StringBuilder value) throws ParseException {
            char decoded = readHexCharacter();
            if (Character.isHighSurrogate(decoded)) {
                if (index + 1 >= input.length()
                        || input.charAt(index) != '\\'
                        || input.charAt(index + 1) != 'u') {
                    throw error("JSON string contains an incomplete surrogate pair");
                }
                index += 2;
                char low = readHexCharacter();
                if (!Character.isLowSurrogate(low)) {
                    throw error("JSON string contains an invalid surrogate pair");
                }
                value.append(decoded).append(low);
            } else if (Character.isLowSurrogate(decoded)) {
                throw error("JSON string contains an unexpected low surrogate");
            } else {
                value.append(decoded);
            }
        }

        private char readHexCharacter() throws ParseException {
            if (index + 4 > input.length()) {
                throw error("JSON unicode escape is incomplete");
            }
            int decoded = 0;
            for (int offset = 0; offset < 4; offset++) {
                int digit = Character.digit(input.charAt(index++), 16);
                if (digit < 0) {
                    throw error("JSON unicode escape contains a non-hex character");
                }
                decoded = decoded * 16 + digit;
            }
            return (char) decoded;
        }

        private void requireDigits() throws ParseException {
            int start = index;
            while (index < input.length() && isDigit(input.charAt(index))) {
                index++;
            }
            if (index == start) {
                throw error("JSON number is missing digits");
            }
        }

        private void expectLiteral(String literal) throws ParseException {
            if (!input.regionMatches(index, literal, 0, literal.length())) {
                throw error("Invalid JSON literal");
            }
            index += literal.length();
            requireDelimiter();
        }

        private void requireDelimiter() throws ParseException {
            if (index >= input.length()) {
                return;
            }
            char next = input.charAt(index);
            if (!Character.isWhitespace(next)
                    && next != ','
                    && next != '}'
                    && next != ']') {
                throw error("JSON value is not delimited");
            }
        }

        private void requireEnd() throws ParseException {
            skipWhitespace();
            if (index != input.length()) {
                throw error("JSON response contains trailing data");
            }
        }

        private void expect(char expected) throws ParseException {
            skipWhitespace();
            if (index >= input.length() || input.charAt(index) != expected) {
                throw error("Unexpected JSON token");
            }
            index++;
        }

        private boolean consume(char expected) {
            if (index < input.length() && input.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (index < input.length()) {
                char current = input.charAt(index);
                if (current != ' '
                        && current != '\t'
                        && current != '\r'
                        && current != '\n') {
                    return;
                }
                index++;
            }
        }

        private void countValue() throws ParseException {
            values++;
            if (values > MAX_VALUES) {
                throw error("JSON response contains too many values");
            }
        }

        private ParseException error(String message) {
            return new ParseException(message);
        }

        private static boolean isDigit(char value) {
            return value >= '0' && value <= '9';
        }
    }
}
