package com.long2know.sportlogger.localeprobe;

import android.content.Context;
import android.icu.lang.UCharacter;
import android.icu.text.NumberingSystem;
import android.icu.util.LocaleData;
import android.icu.util.VersionInfo;
import android.os.Build;
import android.os.Bundle;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import java.util.TreeMap;

public final class AndroidLocaleTimestampProbeTest extends InstrumentationTestCase {
    private static final String OUTPUT_FILE = "android-locale-timestamp-probe.tsv";
    private static final String PATTERN = "yyyy-MM-dd HH:mm:ss.SSS";
    private static final String LEGACY_PATTERN = "yyyyMMddHHmmss";
    private static final String NUMBERING_CANDIDATE_SOURCE =
            "android.icu.text.NumberingSystem.getAvailableNames";
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final String[][] FIXED_TIMES = {
            {"2000-02-29T12:34:56.789Z", "2000", "2", "29", "12", "34", "56", "789"},
            {"2024-07-08T09:10:11.123Z", "2024", "7", "8", "9", "10", "11", "123"},
            {"2032-02-29T23:59:59.999Z", "2032", "2", "29", "23", "59", "59", "999"},
            {"2567-07-08T09:10:11.123Z", "2567", "7", "8", "9", "10", "11", "123"},
    };
    private static final String[] THAI_CONTROLS = {
            "th-TH",
            "th-TH-u-nu-thai",
            "th-TH-u-nu-latn",
            "th-TH-u-ca-buddhist",
            "th-TH-u-ca-buddhist-nu-thai",
            "th-TH-u-ca-gregory-nu-thai",
    };

    private static final class DigitShape {
        final int zeroCodePoint;
        final String normalized;
        final String controlLayout;

        DigitShape(int zeroCodePoint, String normalized, String controlLayout) {
            this.zeroCodePoint = zeroCodePoint;
            this.normalized = normalized;
            this.controlLayout = controlLayout;
        }
    }

    private static final class Signature implements Comparable<Signature> {
        final int zeroCodePoint;
        final String controlLayout;
        final String calendarClass;
        final String calendarType;
        final String formatted;
        final String legacyFormatted;
        final String numberDigits;

        Signature(
                int zeroCodePoint,
                String controlLayout,
                String calendarClass,
                String calendarType,
                String formatted,
                String legacyFormatted,
                String numberDigits) {
            this.zeroCodePoint = zeroCodePoint;
            this.controlLayout = controlLayout;
            this.calendarClass = calendarClass;
            this.calendarType = calendarType;
            this.formatted = formatted;
            this.legacyFormatted = legacyFormatted;
            this.numberDigits = numberDigits;
        }

        @Override
        public int compareTo(Signature other) {
            int result = Integer.compare(zeroCodePoint, other.zeroCodePoint);
            if (result != 0) {
                return result;
            }
            result = controlLayout.compareTo(other.controlLayout);
            if (result != 0) {
                return result;
            }
            result = calendarClass.compareTo(other.calendarClass);
            if (result != 0) {
                return result;
            }
            result = calendarType.compareTo(other.calendarType);
            if (result != 0) {
                return result;
            }
            result = formatted.compareTo(other.formatted);
            if (result != 0) {
                return result;
            }
            result = legacyFormatted.compareTo(other.legacyFormatted);
            return result != 0 ? result : numberDigits.compareTo(other.numberDigits);
        }

        @Override
        public boolean equals(Object value) {
            if (!(value instanceof Signature)) {
                return false;
            }
            Signature other = (Signature) value;
            return zeroCodePoint == other.zeroCodePoint
                    && controlLayout.equals(other.controlLayout)
                    && calendarClass.equals(other.calendarClass)
                    && calendarType.equals(other.calendarType)
                    && formatted.equals(other.formatted)
                    && legacyFormatted.equals(other.legacyFormatted)
                    && numberDigits.equals(other.numberDigits);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    zeroCodePoint,
                    controlLayout,
                    calendarClass,
                    calendarType,
                    formatted,
                    legacyFormatted,
                    numberDigits);
        }
    }

    private static final class LocaleObservation {
        final Locale locale;
        final int zeroCodePoint;
        final String controlLayout;
        final String calendarClass;
        final String calendarType;
        final String formatted;
        final String legacyFormatted;
        final String numberDigits;

        LocaleObservation(Locale locale, Date instant) {
            this.locale = locale;
            SimpleDateFormat formatter = formatter(PATTERN, locale);
            Calendar calendar = formatter.getCalendar();
            calendarClass = calendar.getClass().getName();
            calendarType = calendar.getCalendarType();
            formatted = formatter.format(instant);
            legacyFormatted = formatter(LEGACY_PATTERN, locale).format(instant);
            DigitShape shape = analyzeLegacyDigits(legacyFormatted);
            zeroCodePoint = shape.zeroCodePoint;
            controlLayout = shape.controlLayout;
            numberDigits = numberDigits(locale);
            requireNormalizedDigits(numberDigits, zeroCodePoint, "1234567890");
        }

        Signature signature() {
            return new Signature(
                    zeroCodePoint,
                    controlLayout,
                    calendarClass,
                    calendarType,
                    formatted,
                    legacyFormatted,
                    numberDigits);
        }

        String hashRow() {
            return row(
                    locale.toLanguageTag(),
                    locale.toString(),
                    codePoint(zeroCodePoint),
                    controlLayout,
                    calendarClass,
                    calendarType,
                    formatted,
                    legacyFormatted,
                    numberDigits);
        }
    }

    public void testRecordsAndroidFormatterEvidence() throws Exception {
        Bundle arguments = ((InstrumentationTestRunner) getInstrumentation()).getArguments();
        String mode = arguments.getString("probe_mode", "base");
        String evidence;
        if ("base".equals(mode)) {
            evidence = buildBaseEvidence();
        } else if ("candidate".equals(mode)) {
            evidence = buildNumberingCandidateEvidence(
                    arguments.getString("numbering_candidate", ""));
        } else {
            throw new AssertionError("Unknown probe mode " + mode);
        }
        assertFalse(evidence.contains("\r"));
        assertTrue(evidence.endsWith("\n"));
        Context context = getInstrumentation().getTargetContext();
        File output = new File(context.getFilesDir(), OUTPUT_FILE);
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(output, false), StandardCharsets.UTF_8)) {
            writer.write(evidence);
        }
        assertTrue(output.isFile());
        assertTrue(output.length() > 0);
    }

    private static String buildBaseEvidence() throws Exception {
        Date signatureInstant = fixedDate(FIXED_TIMES[1]);
        Locale[] locales = Locale.getAvailableLocales();
        Arrays.sort(
                locales,
                Comparator.comparing(Locale::toLanguageTag)
                        .thenComparing(Locale::toString));

        Map<Signature, List<String>> localesBySignature = new TreeMap<>();
        MessageDigest localeDigest = MessageDigest.getInstance("SHA-256");
        for (Locale locale : locales) {
            LocaleObservation observation = new LocaleObservation(locale, signatureInstant);
            localesBySignature
                    .computeIfAbsent(observation.signature(), ignored -> new ArrayList<>())
                    .add(locale.toLanguageTag());
            localeDigest.update(observation.hashRow().getBytes(StandardCharsets.UTF_8));
        }

        String[] numberingCandidates = NumberingSystem.getAvailableNames();
        Arrays.sort(numberingCandidates);
        MessageDigest candidateDigest = MessageDigest.getInstance("SHA-256");
        for (String candidate : numberingCandidates) {
            candidateDigest.update((candidate + "\n").getBytes(StandardCharsets.UTF_8));
        }

        StringBuilder output = new StringBuilder();
        output.append(row("meta", "format_version", "2"));
        output.append(row("meta", "platform", "Android"));
        output.append(row("meta", "api_level", Integer.toString(Build.VERSION.SDK_INT)));
        output.append(row("meta", "release", Build.VERSION.RELEASE));
        output.append(row("meta", "build_fingerprint", Build.FINGERPRINT));
        output.append(row("meta", "abi", Build.SUPPORTED_ABIS[0]));
        output.append(row("meta", "supported_abis", String.join(",", Build.SUPPORTED_ABIS)));
        output.append(row("meta", "java_version", property("java.version")));
        output.append(row("meta", "java_runtime_version", property("java.runtime.version")));
        output.append(row("meta", "java_vm_name", property("java.vm.name")));
        output.append(row("meta", "java_vm_version", property("java.vm.version")));
        output.append(row("meta", "icu_version", VersionInfo.ICU_VERSION.toString()));
        output.append(row("meta", "unicode_version", UCharacter.getUnicodeVersion().toString()));
        output.append(row("meta", "cldr_version", LocaleData.getCLDRVersion().toString()));
        output.append(row("meta", "pattern", PATTERN));
        output.append(row("meta", "legacy_pattern", LEGACY_PATTERN));
        output.append(row("meta", "timezone", UTC.getID()));
        output.append(row("meta", "available_locale_count", Integer.toString(locales.length)));
        output.append(row("meta", "locale_rows_sha256", hex(localeDigest.digest())));
        output.append(row("meta", "numbering_candidate_source", NUMBERING_CANDIDATE_SOURCE));
        output.append(row(
                "meta",
                "numbering_candidate_version",
                "ICU-" + VersionInfo.ICU_VERSION.toString()));
        output.append(row(
                "meta",
                "numbering_candidate_count",
                Integer.toString(numberingCandidates.length)));
        output.append(row(
                "meta",
                "numbering_candidate_names_sha256",
                hex(candidateDigest.digest())));
        output.append(row(
                "meta",
                "numbering_candidate_instant_utc",
                FIXED_TIMES[1][0]));
        output.append(row(
                "meta",
                "fixed_instants_utc",
                String.join(",", Arrays.stream(FIXED_TIMES).map(row -> row[0]).toArray(String[]::new))));

        for (Map.Entry<Signature, List<String>> entry : localesBySignature.entrySet()) {
            Signature signature = entry.getKey();
            List<String> tags = entry.getValue();
            output.append(row(
                    "signature",
                    codePoint(signature.zeroCodePoint),
                    signature.controlLayout,
                    signature.calendarClass,
                    signature.calendarType,
                    signature.formatted,
                    signature.legacyFormatted,
                    signature.numberDigits,
                    Integer.toString(tags.size()),
                    tags.get(0)));
        }

        for (String name : numberingCandidates) {
            NumberingSystem numberingSystem = null;
            String definitionStatus = "ok";
            try {
                numberingSystem = NumberingSystem.getInstanceByName(name);
                if (numberingSystem == null) {
                    definitionStatus = "null";
                }
            } catch (RuntimeException | AssertionError error) {
                definitionStatus = error.getClass().getSimpleName();
            }
            if (numberingSystem == null) {
                output.append(row(
                        "candidate_definition",
                        name,
                        definitionStatus));
            } else {
                output.append(row(
                        "candidate_definition",
                        name,
                        definitionStatus,
                        numberingSystem.getName(),
                        Integer.toString(numberingSystem.getRadix()),
                        Boolean.toString(numberingSystem.isAlgorithmic()),
                        numberingSystem.getDescription()));
            }
        }

        Map<String, Locale> controls = new LinkedHashMap<>();
        for (String tag : THAI_CONTROLS) {
            controls.put(tag, Locale.forLanguageTag(tag));
        }
        for (Map.Entry<String, Locale> control : controls.entrySet()) {
            for (String[] fixedTime : FIXED_TIMES) {
                output.append(detailedRow(
                        "thai_control",
                        control.getValue(),
                        "",
                        null,
                        "not_applicable",
                        "ok",
                        fixedTime[0],
                        fixedDate(fixedTime)));
            }
        }
        return output.toString();
    }

    private static String buildNumberingCandidateEvidence(String candidateName) {
        String[] candidates = NumberingSystem.getAvailableNames();
        Arrays.sort(candidates);
        if (Arrays.binarySearch(candidates, candidateName) < 0) {
            throw new AssertionError("Unknown numbering candidate " + candidateName);
        }
        NumberingSystem numberingSystem = null;
        String definitionStatus = "ok";
        try {
            numberingSystem = NumberingSystem.getInstanceByName(candidateName);
            if (numberingSystem == null) {
                definitionStatus = "null";
            }
        } catch (RuntimeException | AssertionError error) {
            definitionStatus = error.getClass().getSimpleName();
        }
        Locale locale = new Locale.Builder()
                .setLanguageTag("en-US")
                .setUnicodeLocaleKeyword("nu", candidateName)
                .build();
        String[] fixedTime = FIXED_TIMES[1];
        return numberingCandidateRow(
                locale,
                candidateName,
                numberingSystem,
                definitionStatus,
                fixedTime[0],
                fixedDate(fixedTime));
    }

    private static String numberingCandidateRow(
            Locale locale,
            String candidateName,
            NumberingSystem numberingSystem,
            String definitionStatus,
            String instantLabel,
            Date instant) {
        try {
            return detailedRow(
                    "numbering_candidate",
                    locale,
                    candidateName,
                    numberingSystem,
                    definitionStatus,
                    "ok",
                    instantLabel,
                    instant);
        } catch (RuntimeException | AssertionError error) {
            return row(
                    "candidate_failure",
                    candidateName,
                    error.getClass().getSimpleName());
        }
    }

    private static String detailedRow(
            String kind,
            Locale locale,
            String candidateName,
            NumberingSystem numberingSystem,
            String definitionStatus,
            String observationStatus,
            String instantLabel,
            Date instant) {
        SimpleDateFormat formatter = formatter(PATTERN, locale);
        String formatted = formatter.format(instant);
        String legacyFormatted = formatter(LEGACY_PATTERN, locale).format(instant);
        DigitShape shape = analyzeLegacyDigits(legacyFormatted);

        SimpleDateFormat parser = formatter(PATTERN, locale);
        parser.setLenient(false);
        ParsePosition position = new ParsePosition(0);
        Date parsed = parser.parse(formatted, position);
        boolean roundTrip = parsed != null
                && position.getIndex() == formatted.length()
                && parsed.getTime() == instant.getTime();

        String defaultFormatted;
        String defaultCalendarClass;
        String defaultCalendarType;
        Locale oldFormat = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.FORMAT, locale);
            SimpleDateFormat defaultFormatter = new SimpleDateFormat(PATTERN);
            defaultFormatter.setTimeZone(UTC);
            defaultFormatted = defaultFormatter.format(instant);
            defaultCalendarClass = defaultFormatter.getCalendar().getClass().getName();
            defaultCalendarType = defaultFormatter.getCalendar().getCalendarType();
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, oldFormat);
        }

        String numberDigits = numberDigits(locale);
        requireNormalizedDigits(numberDigits, shape.zeroCodePoint, "1234567890");
        return row(
                kind,
                candidateName,
                numberingSystem == null ? "" : numberingSystem.getName(),
                definitionStatus,
                observationStatus,
                numberingSystem == null ? "" : Integer.toString(numberingSystem.getRadix()),
                numberingSystem == null ? "" : Boolean.toString(numberingSystem.isAlgorithmic()),
                numberingSystem == null ? "" : numberingSystem.getDescription(),
                codePoint(shape.zeroCodePoint),
                shape.controlLayout,
                locale.toLanguageTag(),
                locale.toString(),
                nullToEmpty(locale.getUnicodeLocaleType("nu")),
                nullToEmpty(locale.getUnicodeLocaleType("ca")),
                formatter.getCalendar().getClass().getName(),
                formatter.getCalendar().getCalendarType(),
                instantLabel,
                formatted,
                legacyFormatted,
                numberDigits,
                Boolean.toString(roundTrip),
                parsed == null ? "" : Long.toString(parsed.getTime()),
                Boolean.toString(formatted.equals(defaultFormatted)),
                defaultCalendarClass,
                defaultCalendarType);
    }

    private static DigitShape analyzeLegacyDigits(String value) {
        int zeroCodePoint = -1;
        int digitIndex = 0;
        StringBuilder normalized = new StringBuilder();
        StringBuilder controls = new StringBuilder();
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int decimal = UCharacter.digit(codePoint, 10);
            if (decimal >= 0) {
                int candidateZero = codePoint - decimal;
                if (zeroCodePoint < 0) {
                    zeroCodePoint = candidateZero;
                } else if (zeroCodePoint != candidateZero) {
                    throw new AssertionError("Formatter output mixed numbering systems");
                }
                normalized.append((char) ('0' + decimal));
                digitIndex++;
            } else if (Character.getType(codePoint) == Character.FORMAT) {
                if (controls.length() > 0) {
                    controls.append(',');
                }
                controls.append(digitIndex).append(':').append(codePoint(codePoint));
            } else {
                throw new AssertionError(
                        "Legacy formatter emitted non-digit U+"
                                + Integer.toHexString(codePoint).toUpperCase(Locale.ROOT));
            }
        }
        if (zeroCodePoint < 0 || digitIndex != 14) {
            throw new AssertionError("Legacy formatter did not emit exactly 14 digits");
        }
        return new DigitShape(zeroCodePoint, normalized.toString(), controls.toString());
    }

    private static void requireNormalizedDigits(
            String value,
            int expectedZeroCodePoint,
            String expectedNormalized) {
        int zeroCodePoint = -1;
        StringBuilder normalized = new StringBuilder();
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int decimal = UCharacter.digit(codePoint, 10);
            if (decimal < 0) {
                throw new AssertionError("Number formatter emitted a non-decimal character");
            }
            int candidateZero = codePoint - decimal;
            if (zeroCodePoint < 0) {
                zeroCodePoint = candidateZero;
            } else if (zeroCodePoint != candidateZero) {
                throw new AssertionError("Number formatter mixed numbering systems");
            }
            normalized.append((char) ('0' + decimal));
        }
        if (zeroCodePoint != expectedZeroCodePoint
                || !normalized.toString().equals(expectedNormalized)) {
            throw new AssertionError("Number and date formatter digit systems differ");
        }
    }

    private static String numberDigits(Locale locale) {
        DecimalFormat digits = new DecimalFormat(
                "0000000000",
                DecimalFormatSymbols.getInstance(locale));
        digits.setGroupingUsed(false);
        return digits.format(1234567890L);
    }

    private static SimpleDateFormat formatter(String pattern, Locale locale) {
        SimpleDateFormat formatter = new SimpleDateFormat(pattern, locale);
        formatter.setTimeZone(UTC);
        return formatter;
    }

    private static Date fixedDate(String[] fields) {
        GregorianCalendar calendar = new GregorianCalendar(UTC, Locale.US);
        calendar.clear();
        calendar.setLenient(false);
        calendar.set(
                Integer.parseInt(fields[1]),
                Integer.parseInt(fields[2]) - 1,
                Integer.parseInt(fields[3]),
                Integer.parseInt(fields[4]),
                Integer.parseInt(fields[5]),
                Integer.parseInt(fields[6]));
        calendar.set(Calendar.MILLISECOND, Integer.parseInt(fields[7]));
        return calendar.getTime();
    }

    private static String row(String... fields) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < fields.length; index++) {
            if (index > 0) {
                result.append('\t');
            }
            result.append(escape(fields[index]));
        }
        return result.append('\n').toString();
    }

    private static String escape(String value) {
        return nullToEmpty(value)
                .replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String property(String name) {
        return nullToEmpty(System.getProperty(name));
    }

    private static String codePoint(int value) {
        return String.format(Locale.ROOT, "U+%04X", value);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }
}
