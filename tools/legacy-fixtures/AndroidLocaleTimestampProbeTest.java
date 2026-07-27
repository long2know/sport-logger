package com.long2know.sportlogger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Build;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

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

public final class AndroidLocaleTimestampProbeTest {
    private static final String OUTPUT_FILE = "android-locale-timestamp-probe.tsv";
    private static final String PATTERN = "yyyy-MM-dd HH:mm:ss.SSS";
    private static final String LEGACY_PATTERN = "yyyyMMddHHmmss";
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

    private static final class Signature implements Comparable<Signature> {
        final int zeroCodePoint;
        final String calendarClass;
        final String calendarType;
        final String formatted;
        final String legacyFormatted;
        final String numberDigits;

        Signature(
                int zeroCodePoint,
                String calendarClass,
                String calendarType,
                String formatted,
                String legacyFormatted,
                String numberDigits) {
            this.zeroCodePoint = zeroCodePoint;
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
                    calendarClass,
                    calendarType,
                    formatted,
                    legacyFormatted,
                    numberDigits);
        }
    }

    private static final class Observation {
        final Locale locale;
        final int zeroCodePoint;
        final String calendarClass;
        final String calendarType;
        final String formatted;
        final String legacyFormatted;
        final String numberDigits;

        Observation(Locale locale, Date instant) {
            this.locale = locale;
            DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(locale);
            zeroCodePoint = symbols.getZeroDigit();
            SimpleDateFormat formatter = formatter(PATTERN, locale);
            Calendar calendar = formatter.getCalendar();
            calendarClass = calendar.getClass().getName();
            calendarType = calendar.getCalendarType();
            formatted = formatter.format(instant);
            legacyFormatted = formatter(LEGACY_PATTERN, locale).format(instant);
            DecimalFormat digits = new DecimalFormat("0000000000", symbols);
            digits.setGroupingUsed(false);
            numberDigits = digits.format(1234567890L);
        }

        Signature signature() {
            return new Signature(
                    zeroCodePoint,
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
                    calendarClass,
                    calendarType,
                    formatted,
                    legacyFormatted,
                    numberDigits);
        }
    }

    @Test
    public void recordsAndroidFormatterEvidence() throws Exception {
        String evidence = buildEvidence();
        assertFalse(evidence.contains("\r"));
        assertTrue(evidence.endsWith("\n"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File output = new File(context.getFilesDir(), OUTPUT_FILE);
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(output, false), StandardCharsets.UTF_8)) {
            writer.write(evidence);
        }
        assertTrue(output.isFile());
        assertTrue(output.length() > 0);
    }

    private static String buildEvidence() throws Exception {
        Date signatureInstant = fixedDate(FIXED_TIMES[1]);
        Locale[] locales = Locale.getAvailableLocales();
        Arrays.sort(
                locales,
                Comparator.comparing(Locale::toLanguageTag)
                        .thenComparing(Locale::toString));

        List<Observation> observations = new ArrayList<>();
        Map<Signature, List<String>> localesBySignature = new TreeMap<>();
        Map<Integer, Observation> candidateByZero = new TreeMap<>();
        MessageDigest localeDigest = MessageDigest.getInstance("SHA-256");
        for (Locale locale : locales) {
            Observation observation = new Observation(locale, signatureInstant);
            observations.add(observation);
            localesBySignature
                    .computeIfAbsent(observation.signature(), ignored -> new ArrayList<>())
                    .add(locale.toLanguageTag());
            candidateByZero.putIfAbsent(observation.zeroCodePoint, observation);
            localeDigest.update(observation.hashRow().getBytes(StandardCharsets.UTF_8));
        }

        StringBuilder output = new StringBuilder();
        output.append(row("meta", "format_version", "1"));
        output.append(row("meta", "platform", "Android"));
        output.append(row("meta", "api_level", Integer.toString(Build.VERSION.SDK_INT)));
        output.append(row("meta", "release", Build.VERSION.RELEASE));
        output.append(row("meta", "pattern", PATTERN));
        output.append(row("meta", "legacy_pattern", LEGACY_PATTERN));
        output.append(row("meta", "timezone", UTC.getID()));
        output.append(row("meta", "available_locale_count", Integer.toString(locales.length)));
        output.append(row("meta", "locale_rows_sha256", hex(localeDigest.digest())));
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
                    signature.calendarClass,
                    signature.calendarType,
                    signature.formatted,
                    signature.legacyFormatted,
                    signature.numberDigits,
                    Integer.toString(tags.size()),
                    tags.get(0)));
        }

        for (Observation candidate : candidateByZero.values()) {
            for (String[] fixedTime : FIXED_TIMES) {
                output.append(detailedRow(
                        "candidate",
                        candidate.locale,
                        fixedTime[0],
                        fixedDate(fixedTime)));
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
                        fixedTime[0],
                        fixedDate(fixedTime)));
            }
        }
        return output.toString();
    }

    private static String detailedRow(
            String kind,
            Locale locale,
            String instantLabel,
            Date instant) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(locale);
        SimpleDateFormat formatter = formatter(PATTERN, locale);
        String formatted = formatter.format(instant);
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

        DecimalFormat digits = new DecimalFormat("0000000000", symbols);
        digits.setGroupingUsed(false);
        return row(
                kind,
                codePoint(symbols.getZeroDigit()),
                locale.toLanguageTag(),
                locale.toString(),
                nullToEmpty(locale.getUnicodeLocaleType("nu")),
                nullToEmpty(locale.getUnicodeLocaleType("ca")),
                formatter.getCalendar().getClass().getName(),
                formatter.getCalendar().getCalendarType(),
                instantLabel,
                formatted,
                formatter(LEGACY_PATTERN, locale).format(instant),
                digits.format(1234567890L),
                Boolean.toString(roundTrip),
                parsed == null ? "" : Long.toString(parsed.getTime()),
                Boolean.toString(formatted.equals(defaultFormatted)),
                defaultCalendarClass,
                defaultCalendarType);
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
