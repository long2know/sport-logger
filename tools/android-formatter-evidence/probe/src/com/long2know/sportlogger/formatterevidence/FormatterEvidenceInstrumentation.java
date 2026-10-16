package com.long2know.sportlogger.formatterevidence;

import android.app.Activity;
import android.app.Instrumentation;
import android.icu.lang.UCharacter;
import android.icu.text.NumberingSystem;
import android.icu.util.VersionInfo;
import android.os.Build;
import android.os.Bundle;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.DecimalFormat;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;

public final class FormatterEvidenceInstrumentation extends Instrumentation {
    private static final String PATTERN = "yyyyMMddHHmmss";
    private static final String CANDIDATE_ALGORITHM = "android-icu-numbering-system-v2";
    private static final String CANDIDATE_ALGORITHM_DETAIL =
            "getAvailableNames;record-nulls;retain-all-non-null;String.compareTo;"
                    + "probe-unique-decimal;metadata-only-non-decimal;batch-size=8";
    private static final int BATCH_SIZE = 8;
    private static final String PACKAGE_NAME =
            "com.long2know.sportlogger.formatterevidence";
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final Locale BASE_LOCALE = Locale.forLanguageTag("en-US");

    private static final InstantSpec[] INSTANTS = {
            new InstantSpec("i1", "1900-01-02T03:04:05.000Z", -2208891355000L),
            new InstantSpec("i2", "1941-12-07T08:09:10.000Z", -885743450000L),
            new InstantSpec("i3", "1970-01-01T00:00:00.000Z", 0L),
            new InstantSpec("i4", "2009-08-17T16:45:32.000Z", 1250527532000L),
            new InstantSpec("i5", "2024-02-29T23:59:58.000Z", 1709251198000L),
            new InstantSpec("i6", "2038-01-19T03:14:07.000Z", 2147483647000L)
    };

    private static final LocaleSpec[] REPRESENTATIVE_LOCALES = {
            new LocaleSpec("ar_eg", "ar-EG"),
            new LocaleSpec("ar_sa", "ar-SA"),
            new LocaleSpec("bn_bd", "bn-BD"),
            new LocaleSpec("en_us", "en-US"),
            new LocaleSpec("fa_ir", "fa-IR"),
            new LocaleSpec("hi_in", "hi-IN"),
            new LocaleSpec("ja_jp", "ja-JP"),
            new LocaleSpec("ja_jp_japanese", "ja-JP-u-ca-japanese"),
            new LocaleSpec("my_mm", "my-MM"),
            new LocaleSpec("th_th", "th-TH"),
            new LocaleSpec("th_th_buddhist", "th-TH-u-ca-buddhist")
    };

    private static final String CANDIDATE_HEADER =
            "candidate_name\tresolved_name\tlocale_tag\tcandidate_kind\tradix\talgorithmic"
                    + "\tdescription_text\tdescription_code_points\tdescription_digit_values"
                    + "\tlocale_numbering_system_name"
                    + "\tnumber_format_class\tformatter_zero_digit_code_point\tcategory"
                    + "\tobserved_output\tfallback\tobserved_digit_code_points"
                    + "\tstrict_round_trip_all\trow_count\terror_stage\terror_class\tattempts";

    private static final String FORMAT_HEADER =
            "source_kind\tsource_id\tlocale_tag\tcandidate_name\tinstant_id\tinstant_utc"
                    + "\tepoch_ms\tformatted_text\tformatted_code_points"
                    + "\tformatted_code_point_count\tformatted_utf16_length\tformatter_class"
                    + "\tformatter_pattern\ttimezone_id\tcalendar_class\tcalendar_type"
                    + "\tnumber_format_class\tformatter_zero_digit_code_point\tparse_index"
                    + "\tparse_error_index\tparse_consumed\tparsed_epoch_ms"
                    + "\tparsed_matches_input\treformat_text\treformat_code_points"
                    + "\treformat_equal\tresult";

    private Bundle arguments;

    @Override
    public void onCreate(Bundle suppliedArguments) {
        super.onCreate(suppliedArguments);
        arguments = suppliedArguments == null ? new Bundle() : suppliedArguments;
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            Locale.setDefault(BASE_LOCALE);
            TimeZone.setDefault(UTC);
            String mode = requiredArgument("mode");
            if ("discover".equals(mode)) {
                writeDiscovery();
            } else if ("batch".equals(mode)) {
                writeCandidateBatch(Integer.parseInt(requiredArgument("start_index")));
            } else if ("candidate".equals(mode)) {
                writeCandidate(requiredArgument("candidate"));
            } else if ("representatives".equals(mode)) {
                writeRepresentatives();
            } else {
                throw new IllegalArgumentException("unsupported mode");
            }
            result.putString("probe_status", "ok");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("probe_status", "error");
            result.putString("error_class", failure.getClass().getName());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void writeDiscovery() throws Exception {
        String[] advertised = NumberingSystem.getAvailableNames();
        List<String> names = new ArrayList<String>();
        int nullCount = 0;
        for (String name : advertised) {
            if (name == null) {
                nullCount++;
            } else {
                names.add(name);
            }
        }
        Collections.sort(names);

        Map<String, Integer> occurrences = new HashMap<String, Integer>();
        for (String name : names) {
            Integer count = occurrences.get(name);
            occurrences.put(name, count == null ? 1 : count + 1);
        }

        TreeMap<String, String> metadata = new TreeMap<String, String>();
        metadata.put("abi.list", join(Build.SUPPORTED_ABIS, ","));
        metadata.put("android.api_level", Integer.toString(Build.VERSION.SDK_INT));
        metadata.put("android.release", safe(Build.VERSION.RELEASE));
        metadata.put("build.fingerprint", safe(Build.FINGERPRINT));
        metadata.put("candidate.advertised_count", Integer.toString(advertised.length));
        metadata.put("candidate.algorithm", CANDIDATE_ALGORITHM);
        metadata.put("candidate.algorithm.detail", CANDIDATE_ALGORITHM_DETAIL);
        metadata.put("candidate.duplicate_count",
                Integer.toString(names.size() - occurrences.size()));
        metadata.put("candidate.null_count", Integer.toString(nullCount));
        metadata.put("candidate.sorted_occurrence_sha256", sha256Lines(names));
        metadata.put("candidate.unique_count", Integer.toString(occurrences.size()));
        metadata.put("default.locale.observed", Locale.getDefault().toLanguageTag());
        metadata.put("default.locale.requested", BASE_LOCALE.toLanguageTag());
        metadata.put("fixed_instant.count", Integer.toString(INSTANTS.length));
        for (InstantSpec instant : INSTANTS) {
            metadata.put("fixed_instant." + instant.id + ".epoch_ms",
                    Long.toString(instant.epochMillis));
            metadata.put("fixed_instant." + instant.id + ".utc", instant.utc);
        }
        metadata.put("formatter.class", SimpleDateFormat.class.getName());
        metadata.put("formatter.constructor",
                "java.text.SimpleDateFormat(String)");
        metadata.put("formatter.pattern", PATTERN);
        metadata.put("icu.version", VersionInfo.ICU_VERSION.toString());
        metadata.put("java.class.version", systemProperty("java.class.version"));
        metadata.put("java.runtime.name", systemProperty("java.runtime.name"));
        metadata.put("java.runtime.version", systemProperty("java.runtime.version"));
        metadata.put("java.specification.version",
                systemProperty("java.specification.version"));
        metadata.put("java.vendor", systemProperty("java.vendor"));
        metadata.put("java.version", systemProperty("java.version"));
        metadata.put("java.vm.name", systemProperty("java.vm.name"));
        metadata.put("java.vm.vendor", systemProperty("java.vm.vendor"));
        metadata.put("java.vm.version", systemProperty("java.vm.version"));
        metadata.put("output.encoding", "UTF-8");
        metadata.put("probe.instrumentation_class", getClass().getName());
        metadata.put("probe.package", PACKAGE_NAME);
        metadata.put("representative_locale.count",
                Integer.toString(REPRESENTATIVE_LOCALES.length));
        for (LocaleSpec locale : REPRESENTATIVE_LOCALES) {
            metadata.put("representative_locale." + locale.id, locale.languageTag);
        }
        metadata.put("timezone.observed", TimeZone.getDefault().getID());
        metadata.put("timezone.requested", UTC.getID());
        metadata.put("unicode.version", UCharacter.getUnicodeVersion().toString());

        String[] pinKeys = {
                "build_tools_package", "build_tools_revision", "cmdline_tools_package",
                "cmdline_tools_revision", "compile_sdk_package", "compile_sdk_revision",
                "emulator_package", "emulator_reported_version", "emulator_revision",
                "host_java_runtime_version", "host_java_vendor", "host_java_version",
                "host_javac_version", "legacy_config_sha256", "legacy_writer_sha256",
                "manifest_sha256", "platform_tools_package", "platform_tools_revision",
                "probe_source_sha256", "system_image_package", "system_image_revision",
                "toolchain_sha256"
        };
        for (String key : pinKeys) {
            metadata.put("pin." + key, requiredArgument(key));
        }

        StringBuilder metadataText = new StringBuilder("key\tvalue\n");
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            appendRow(metadataText, entry.getKey(), entry.getValue());
        }
        writeAtomic("metadata.tsv", metadataText.toString());

        StringBuilder advertisedText =
                new StringBuilder("sorted_index\tvalue_kind\tcandidate_name\toccurrence_index\n");
        int index = 0;
        for (int nullIndex = 0; nullIndex < nullCount; nullIndex++) {
            appendRow(advertisedText, Integer.toString(index++), "null", "",
                    Integer.toString(nullIndex + 1));
        }
        Map<String, Integer> seen = new HashMap<String, Integer>();
        for (String name : names) {
            Integer previous = seen.get(name);
            int occurrence = previous == null ? 1 : previous + 1;
            seen.put(name, occurrence);
            appendRow(advertisedText, Integer.toString(index++), "name", name,
                    Integer.toString(occurrence));
        }
        writeAtomic("advertised.tsv", advertisedText.toString());
    }

    private void writeCandidate(String candidateName) throws Exception {
        deleteOutput("candidate.tsv");
        deleteOutput("candidate-formats.tsv");

        CandidateResult result;
        try {
            result = probeCandidate(candidateName);
        } catch (Throwable failure) {
            result = CandidateResult.error(candidateName, "probe",
                    failure.getClass().getName());
        }

        writeAtomic("candidate.tsv", CANDIDATE_HEADER + "\n" + result.candidateRow + "\n");
        StringBuilder formats = new StringBuilder(FORMAT_HEADER).append('\n');
        for (String row : result.formatRows) {
            formats.append(row).append('\n');
        }
        writeAtomic("candidate-formats.tsv", formats.toString());
    }

    private void writeCandidateBatch(int startIndex) throws Exception {
        deleteOutput("batch-current.tsv");
        deleteOutput("batch-candidates.tsv");
        deleteOutput("batch-formats.tsv");

        String[] advertised = NumberingSystem.getAvailableNames();
        Set<String> unique = new TreeSet<String>();
        for (String name : advertised) {
            if (name != null) {
                unique.add(name);
            }
        }
        List<String> names = new ArrayList<String>(unique);
        if (startIndex < 0 || startIndex > names.size()) {
            throw new IllegalArgumentException("invalid batch start index");
        }

        StringBuilder candidates = new StringBuilder(CANDIDATE_HEADER).append('\n');
        StringBuilder formats = new StringBuilder(FORMAT_HEADER).append('\n');
        writeAtomic("batch-candidates.tsv", candidates.toString());
        writeAtomic("batch-formats.tsv", formats.toString());

        int endIndex = Math.min(startIndex + BATCH_SIZE, names.size());
        for (int index = startIndex; index < endIndex; index++) {
            String candidateName = names.get(index);
            writeAtomic(
                    "batch-current.tsv",
                    "index\tcandidate_name\tstate\n"
                            + row(Integer.toString(index), candidateName, "probing")
                            + "\n");
            CandidateResult result;
            try {
                result = probeCandidate(candidateName);
            } catch (Throwable failure) {
                result = CandidateResult.error(candidateName, "probe",
                        failure.getClass().getName());
            }
            candidates.append(result.candidateRow).append('\n');
            for (String formatRow : result.formatRows) {
                formats.append(formatRow).append('\n');
            }
            writeAtomic("batch-candidates.tsv", candidates.toString());
            writeAtomic("batch-formats.tsv", formats.toString());
        }
        writeAtomic(
                "batch-current.tsv",
                "index\tcandidate_name\tstate\n"
                        + row(Integer.toString(endIndex), "", "complete")
                        + "\n");
    }

    private CandidateResult probeCandidate(String candidateName) throws Exception {
        NumberingSystem requested = NumberingSystem.getInstanceByName(candidateName);
        if (requested == null) {
            return CandidateResult.error(candidateName, "resolve", "null_numbering_system");
        }

        Locale locale = new Locale.Builder()
                .setLocale(BASE_LOCALE)
                .setUnicodeLocaleKeyword("nu", candidateName)
                .build();
        NumberingSystem localeNumberingSystem = NumberingSystem.getInstance(locale);
        int[] descriptionDigits = decimalDescriptionDigits(requested);
        String candidateKind;
        if (requested.isAlgorithmic()) {
            candidateKind = "algorithmic";
        } else if (descriptionDigits != null) {
            candidateKind = "decimal";
        } else {
            candidateKind = "non_decimal";
        }

        if (!"decimal".equals(candidateKind)) {
            String row = row(
                    candidateName,
                    safe(requested.getName()),
                    locale.toLanguageTag(),
                    candidateKind,
                    Integer.toString(requested.getRadix()),
                    Boolean.toString(requested.isAlgorithmic()),
                    safe(requested.getDescription()),
                    codePoints(safe(requested.getDescription())),
                    digitValues(safe(requested.getDescription())),
                    localeNumberingSystem == null ? "" : safe(localeNumberingSystem.getName()),
                    "",
                    "",
                    "algorithmic_non_decimal",
                    "not_decimal",
                    "false",
                    "",
                    "false",
                    "0",
                    "",
                    "",
                    "1");
            return new CandidateResult(row, Collections.<String>emptyList());
        }

        boolean allRequested = true;
        boolean allAscii = true;
        boolean allStrictRoundTrip = true;
        Set<Integer> observedDigits = new TreeSet<Integer>();
        List<String> formatRows = new ArrayList<String>();
        String numberFormatClass = "";
        String zeroDigitCodePoint = "";

        for (InstantSpec instant : INSTANTS) {
            SimpleDateFormat formatter = formatter(locale);
            numberFormatClass = formatter.getNumberFormat().getClass().getName();
            zeroDigitCodePoint = formatterZeroDigitCodePoint(formatter);
            FormatResult formatResult = format(
                    "candidate", candidateName, locale, candidateName, instant, formatter);
            formatRows.add(formatResult.row);
            allStrictRoundTrip &= formatResult.strictRoundTrip;

            String ascii = formatter(BASE_LOCALE).format(new Date(instant.epochMillis));
            String requestedText = descriptionDigits == null
                    ? null : replaceAsciiDigits(ascii, descriptionDigits);
            allRequested &= requestedText != null && requestedText.equals(formatResult.text);
            allAscii &= ascii.equals(formatResult.text);
            collectObservedDigits(ascii, formatResult.text, observedDigits);
        }

        String category;
        String observedOutput;
        boolean fallback;
        if (allRequested) {
            category = "supported_decimal_output";
            observedOutput = "requested_decimal";
            fallback = false;
        } else {
            category = "unsupported_fallback";
            observedOutput = allAscii ? "ascii_fallback" : "other_fallback";
            fallback = true;
        }

        String row = row(
                candidateName,
                safe(requested.getName()),
                locale.toLanguageTag(),
                candidateKind,
                Integer.toString(requested.getRadix()),
                Boolean.toString(requested.isAlgorithmic()),
                safe(requested.getDescription()),
                codePoints(safe(requested.getDescription())),
                digitValues(safe(requested.getDescription())),
                localeNumberingSystem == null ? "" : safe(localeNumberingSystem.getName()),
                numberFormatClass,
                zeroDigitCodePoint,
                category,
                observedOutput,
                Boolean.toString(fallback),
                codePointSet(observedDigits),
                Boolean.toString(allStrictRoundTrip),
                Integer.toString(formatRows.size()),
                "",
                "",
                "1");
        return new CandidateResult(row, formatRows);
    }

    private void writeRepresentatives() throws Exception {
        StringBuilder formats = new StringBuilder(FORMAT_HEADER).append('\n');
        for (LocaleSpec localeSpec : REPRESENTATIVE_LOCALES) {
            Locale locale = Locale.forLanguageTag(localeSpec.languageTag);
            for (InstantSpec instant : INSTANTS) {
                SimpleDateFormat formatter = formatter(locale);
                FormatResult result = format("representative_locale", localeSpec.id, locale, "",
                        instant, formatter);
                formats.append(result.row).append('\n');
            }
        }
        writeAtomic("representative-formats.tsv", formats.toString());
    }

    private static FormatResult format(
            String sourceKind,
            String sourceId,
            Locale locale,
            String candidateName,
            InstantSpec instant,
            SimpleDateFormat formatter) {
        Date input = new Date(instant.epochMillis);
        String text = formatter.format(input);
        ParsePosition position = new ParsePosition(0);
        Date parsed = formatter.parse(text, position);
        boolean consumed = parsed != null
                && position.getErrorIndex() < 0
                && position.getIndex() == text.length();
        String parsedEpoch = parsed == null ? "" : Long.toString(parsed.getTime());
        boolean parsedMatches = parsed != null && parsed.getTime() == instant.epochMillis;
        String reformatted = parsed == null ? "" : formatter.format(parsed);
        boolean reformatEqual = parsed != null && text.equals(reformatted);
        boolean strictRoundTrip = consumed && parsedMatches && reformatEqual;
        String result = strictRoundTrip ? "round_trip" : "parse_failure";

        String calendarType;
        try {
            calendarType = formatter.getCalendar().getCalendarType();
        } catch (Throwable unavailable) {
            calendarType = "<unavailable>";
        }

        String row = row(
                sourceKind,
                sourceId,
                locale.toLanguageTag(),
                candidateName,
                instant.id,
                instant.utc,
                Long.toString(instant.epochMillis),
                text,
                codePoints(text),
                Integer.toString(text.codePointCount(0, text.length())),
                Integer.toString(text.length()),
                formatter.getClass().getName(),
                formatter.toPattern(),
                formatter.getTimeZone().getID(),
                formatter.getCalendar().getClass().getName(),
                calendarType,
                formatter.getNumberFormat().getClass().getName(),
                formatterZeroDigitCodePoint(formatter),
                Integer.toString(position.getIndex()),
                Integer.toString(position.getErrorIndex()),
                Boolean.toString(consumed),
                parsedEpoch,
                Boolean.toString(parsedMatches),
                reformatted,
                codePoints(reformatted),
                Boolean.toString(reformatEqual),
                result);
        return new FormatResult(row, text, strictRoundTrip);
    }

    private static SimpleDateFormat formatter(Locale locale) {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(locale);
            SimpleDateFormat formatter = new SimpleDateFormat(PATTERN);
            formatter.setTimeZone(UTC);
            formatter.setLenient(false);
            return formatter;
        } finally {
            Locale.setDefault(previous);
        }
    }

    private static int[] decimalDescriptionDigits(NumberingSystem numberingSystem) {
        if (numberingSystem.isAlgorithmic() || numberingSystem.getRadix() != 10) {
            return null;
        }
        String description = numberingSystem.getDescription();
        int[] codePoints = toCodePointArray(description);
        if (codePoints.length != 10) {
            return null;
        }
        for (int index = 0; index < codePoints.length; index++) {
            if (UCharacter.digit(codePoints[index], 10) != index) {
                return null;
            }
        }
        return codePoints;
    }

    private static String replaceAsciiDigits(String value, int[] digits) {
        StringBuilder replaced = new StringBuilder();
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            if (codePoint >= '0' && codePoint <= '9') {
                replaced.appendCodePoint(digits[codePoint - '0']);
            } else {
                replaced.appendCodePoint(codePoint);
            }
            index += Character.charCount(codePoint);
        }
        return replaced.toString();
    }

    private static void collectObservedDigits(
            String ascii,
            String actual,
            Set<Integer> observedDigits) {
        int[] asciiCodePoints = toCodePointArray(ascii);
        int[] actualCodePoints = toCodePointArray(actual);
        if (asciiCodePoints.length == actualCodePoints.length) {
            for (int index = 0; index < asciiCodePoints.length; index++) {
                if (asciiCodePoints[index] >= '0' && asciiCodePoints[index] <= '9') {
                    observedDigits.add(actualCodePoints[index]);
                }
            }
            return;
        }
        for (int codePoint : actualCodePoints) {
            if (Character.getType(codePoint) == Character.DECIMAL_DIGIT_NUMBER) {
                observedDigits.add(codePoint);
            }
        }
    }

    private static String formatterZeroDigitCodePoint(SimpleDateFormat formatter) {
        if (formatter.getNumberFormat() instanceof DecimalFormat) {
            char zeroDigit = ((DecimalFormat) formatter.getNumberFormat())
                    .getDecimalFormatSymbols()
                    .getZeroDigit();
            return codePoint(zeroDigit);
        }
        return "<unavailable>";
    }

    private static int[] toCodePointArray(String value) {
        int[] result = new int[value.codePointCount(0, value.length())];
        int resultIndex = 0;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            result[resultIndex++] = codePoint;
            index += Character.charCount(codePoint);
        }
        return result;
    }

    private static String codePoints(String value) {
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            if (output.length() > 0) {
                output.append(' ');
            }
            output.append(codePoint(codePoint));
            index += Character.charCount(codePoint);
        }
        return output.toString();
    }

    private static String codePointSet(Set<Integer> codePoints) {
        StringBuilder output = new StringBuilder();
        for (Integer codePoint : codePoints) {
            if (output.length() > 0) {
                output.append(' ');
            }
            output.append(codePoint(codePoint));
        }
        return output.toString();
    }

    private static String digitValues(String value) {
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            if (output.length() > 0) {
                output.append(' ');
            }
            output.append(UCharacter.digit(codePoint, 10));
            index += Character.charCount(codePoint);
        }
        return output.toString();
    }

    private static String codePoint(int codePoint) {
        return String.format(Locale.ROOT, "U+%04X", codePoint);
    }

    private static String sha256Lines(List<String> values) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String value : values) {
            digest.update(value.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            output.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return output.toString();
    }

    private String requiredArgument(String name) {
        String value = arguments.getString(name);
        if (value == null || value.length() == 0) {
            throw new IllegalArgumentException("missing argument");
        }
        return value;
    }

    private void deleteOutput(String name) {
        File output = new File(getTargetContext().getFilesDir(), name);
        if (output.exists() && !output.delete()) {
            throw new IllegalStateException("cannot delete output");
        }
    }

    private void writeAtomic(String name, String contents) throws IOException {
        File directory = getTargetContext().getFilesDir();
        File temporary = new File(directory, name + ".new");
        File destination = new File(directory, name);
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("cannot delete temporary output");
        }
        FileOutputStream stream = new FileOutputStream(temporary);
        try {
            Writer writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8);
            writer.write(contents);
            writer.flush();
            stream.getFD().sync();
        } finally {
            stream.close();
        }
        if (destination.exists() && !destination.delete()) {
            throw new IOException("cannot replace output");
        }
        if (!temporary.renameTo(destination)) {
            throw new IOException("cannot publish output");
        }
    }

    private static void appendRow(StringBuilder output, String... values) {
        output.append(row(values)).append('\n');
    }

    private static String row(String... values) {
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                output.append('\t');
            }
            String value = safe(values[index]);
            if (value.indexOf('\t') >= 0 || value.indexOf('\r') >= 0
                    || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("TSV control character");
            }
            output.append(value);
        }
        return output.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String systemProperty(String name) {
        String value = System.getProperty(name);
        return value == null || value.length() == 0 ? "<unavailable>" : value;
    }

    private static String join(String[] values, String separator) {
        StringBuilder output = new StringBuilder();
        for (String value : values) {
            if (output.length() > 0) {
                output.append(separator);
            }
            output.append(value);
        }
        return output.toString();
    }

    private static final class CandidateResult {
        final String candidateRow;
        final List<String> formatRows;

        CandidateResult(String candidateRow, List<String> formatRows) {
            this.candidateRow = candidateRow;
            this.formatRows = formatRows;
        }

        static CandidateResult error(String candidateName, String stage, String errorClass) {
            String row = row(
                    candidateName, "", "", "unknown", "", "", "", "", "", "", "",
                    "", "error", "no_output", "false", "", "false", "0", stage,
                    errorClass, "1");
            return new CandidateResult(row, Collections.<String>emptyList());
        }
    }

    private static final class FormatResult {
        final String row;
        final String text;
        final boolean strictRoundTrip;

        FormatResult(String row, String text, boolean strictRoundTrip) {
            this.row = row;
            this.text = text;
            this.strictRoundTrip = strictRoundTrip;
        }
    }

    private static final class InstantSpec {
        final String id;
        final String utc;
        final long epochMillis;

        InstantSpec(String id, String utc, long epochMillis) {
            this.id = id;
            this.utc = utc;
            this.epochMillis = epochMillis;
        }
    }

    private static final class LocaleSpec {
        final String id;
        final String languageTag;

        LocaleSpec(String id, String languageTag) {
            this.id = id;
            this.languageTag = languageTag;
        }
    }
}
