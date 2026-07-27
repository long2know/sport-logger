import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import java.util.TreeMap;

public final class LocaleTimestampProbe {
    private static final String EXPECTED_RUNTIME = "17.0.20+8";
    private static final String EXPECTED_VENDOR = "Eclipse Adoptium";
    private static final String PROBE_INSTANT = "2024-07-08T09:10:11Z";
    private static final Path DEFAULT_MATRIX =
            Path.of("tools", "legacy-fixtures", "LocaleTimestampProbe.expected.tsv");

    private static final Map<Integer, String> CANDIDATE_LOCALES =
            Map.of(
                    0x0030, "en-US",
                    0x0660, "ar-EG",
                    0x06F0, "fa-IR",
                    0x0966, "mr-IN",
                    0x09E6, "bn-BD",
                    0x0E50, "th-TH-u-nu-thai-x-lvariant-TH",
                    0x0F20, "dz-BT",
                    0x1040, "my-MM",
                    0x1C50, "sat-IN");

    private LocaleTimestampProbe() {
    }

    private static final class Signature implements Comparable<Signature> {
        final int zero;
        final String calendar;
        final String formatted;

        Signature(int zero, String calendar, String formatted) {
            this.zero = zero;
            this.calendar = calendar;
            this.formatted = formatted;
        }

        @Override
        public int compareTo(Signature other) {
            int result = Integer.compare(zero, other.zero);
            if (result != 0) {
                return result;
            }
            result = calendar.compareTo(other.calendar);
            return result != 0 ? result : formatted.compareTo(other.formatted);
        }

        @Override
        public boolean equals(Object value) {
            if (!(value instanceof Signature)) {
                return false;
            }
            Signature other = (Signature) value;
            return zero == other.zero
                    && calendar.equals(other.calendar)
                    && formatted.equals(other.formatted);
        }

        @Override
        public int hashCode() {
            return Objects.hash(zero, calendar, formatted);
        }
    }

    private static final class Observation {
        final String localeTag;
        final int zero;
        final String calendar;
        final String formatted;

        Observation(String localeTag, int zero, String calendar, String formatted) {
            this.localeTag = localeTag;
            this.zero = zero;
            this.calendar = calendar;
            this.formatted = formatted;
        }

        Signature signature() {
            return new Signature(zero, calendar, formatted);
        }

        String hashRow() {
            return String.join(
                            "\t",
                            localeTag,
                            codePoint(zero),
                            calendar,
                            formatted)
                    + "\n";
        }
    }

    private static String codePoint(int value) {
        return String.format("U+%04X", value);
    }

    private static Observation observe(Locale locale, Instant instant) {
        SimpleDateFormat formatter =
                new SimpleDateFormat("yyyyMMddHHmmss", locale);
        formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        return new Observation(
                locale.toLanguageTag(),
                DecimalFormatSymbols.getInstance(locale).getZeroDigit(),
                formatter.getCalendar().getCalendarType(),
                formatter.format(Date.from(instant)));
    }

    private static String sha256(List<Observation> observations) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Observation observation : observations) {
                digest.update(observation.hashRow().getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError("SHA-256 is required by every Java runtime", error);
        }
    }

    private static void requirePinnedRuntime() {
        String runtime = System.getProperty("java.runtime.version");
        String vendor = System.getProperty("java.vendor");
        if (!EXPECTED_RUNTIME.equals(runtime) || !EXPECTED_VENDOR.equals(vendor)) {
            throw new AssertionError(
                    "Run with pinned Temurin "
                            + EXPECTED_RUNTIME
                            + "; found runtime="
                            + runtime
                            + " vendor="
                            + vendor);
        }
    }

    private static String buildMatrix() {
        requirePinnedRuntime();
        Instant probeInstant = Instant.parse(PROBE_INSTANT);
        Locale[] locales = Locale.getAvailableLocales();
        Arrays.sort(
                locales,
                Comparator.comparing(Locale::toLanguageTag)
                        .thenComparing(Locale::toString));

        List<Observation> observations = new ArrayList<>();
        Map<Signature, List<String>> localesBySignature = new TreeMap<>();
        for (Locale locale : locales) {
            Observation observation = observe(locale, probeInstant);
            observations.add(observation);
            localesBySignature
                    .computeIfAbsent(observation.signature(), ignored -> new ArrayList<>())
                    .add(observation.localeTag);
        }

        Map<Integer, Observation> candidates = new TreeMap<>();
        for (Map.Entry<Integer, String> entry : CANDIDATE_LOCALES.entrySet()) {
            Observation candidate =
                    observe(Locale.forLanguageTag(entry.getValue()), probeInstant);
            if (candidate.zero != entry.getKey()
                    || candidate.formatted.length() != 14
                    || (!candidate.calendar.equals("gregory")
                            && !candidate.calendar.equals("buddhist"))) {
                throw new AssertionError(
                        "Candidate locale "
                                + entry.getValue()
                                + " no longer represents "
                                + codePoint(entry.getKey()));
            }
            if (!localesBySignature
                    .getOrDefault(candidate.signature(), List.of())
                    .contains(candidate.localeTag)) {
                throw new AssertionError(
                        "Candidate locale "
                                + candidate.localeTag
                                + " is not in the available-locale matrix");
            }
            candidates.put(entry.getKey(), candidate);
        }

        Map<String, Observation> controls = new LinkedHashMap<>();
        controls.put(
                "thai_digits_gregorian",
                observe(
                        Locale.forLanguageTag("th-TH-u-ca-gregory-nu-thai"),
                        probeInstant));
        controls.put(
                "thai_digits_buddhist",
                observe(Locale.forLanguageTag("th-TH-u-nu-thai"), probeInstant));
        controls.put(
                "latin_digits_buddhist",
                observe(Locale.forLanguageTag("th-TH-u-nu-latn"), probeInstant));
        controls.put(
                "latin_year_2567_gregorian",
                observe(
                        Locale.forLanguageTag("en-US"),
                        Instant.parse("2567-07-08T09:10:11Z")));

        StringBuilder matrix = new StringBuilder();
        matrix.append("meta\tformat_version\t1\n");
        matrix.append("meta\tjava_runtime_version\t").append(EXPECTED_RUNTIME).append('\n');
        matrix.append("meta\tjava_vendor\t").append(EXPECTED_VENDOR).append('\n');
        matrix.append("meta\tlocale_providers\tdefault\n");
        matrix.append("meta\tprobe_instant_utc\t").append(PROBE_INSTANT).append('\n');
        matrix.append("meta\tavailable_locale_count\t").append(locales.length).append('\n');
        matrix.append("meta\tlocale_rows_sha256\t").append(sha256(observations)).append('\n');
        for (Map.Entry<Signature, List<String>> entry : localesBySignature.entrySet()) {
            Signature signature = entry.getKey();
            List<String> tags = entry.getValue();
            matrix.append("signature\t")
                    .append(codePoint(signature.zero))
                    .append('\t')
                    .append(signature.calendar)
                    .append('\t')
                    .append(signature.formatted)
                    .append('\t')
                    .append(tags.size())
                    .append('\t')
                    .append(tags.get(0))
                    .append('\n');
        }
        for (Observation candidate : candidates.values()) {
            matrix.append("candidate\t")
                    .append(codePoint(candidate.zero))
                    .append('\t')
                    .append(candidate.localeTag)
                    .append('\t')
                    .append(candidate.calendar)
                    .append('\t')
                    .append(PROBE_INSTANT)
                    .append('\t')
                    .append(candidate.formatted)
                    .append('\n');
        }
        for (Map.Entry<String, Observation> entry : controls.entrySet()) {
            Observation control = entry.getValue();
            String instant =
                    entry.getKey().equals("latin_year_2567_gregorian")
                            ? "2567-07-08T09:10:11Z"
                            : PROBE_INSTANT;
            matrix.append("control\t")
                    .append(entry.getKey())
                    .append('\t')
                    .append(control.localeTag)
                    .append('\t')
                    .append(codePoint(control.zero))
                    .append('\t')
                    .append(control.calendar)
                    .append('\t')
                    .append(instant)
                    .append('\t')
                    .append(control.formatted)
                    .append('\n');
        }
        return matrix.toString();
    }

    public static void main(String[] args) throws IOException {
        boolean write = args.length == 1 && args[0].equals("--write-matrix");
        if (args.length > 1 || (args.length == 1 && !write)) {
            throw new IllegalArgumentException(
                    "Usage: java LocaleTimestampProbe.java [--write-matrix]");
        }

        String actual = buildMatrix();
        if (write) {
            Files.writeString(
                    DEFAULT_MATRIX,
                    actual,
                    StandardCharsets.UTF_8);
            System.out.println("Wrote " + DEFAULT_MATRIX);
            return;
        }

        String expected = Files.readString(DEFAULT_MATRIX, StandardCharsets.UTF_8);
        if (!expected.equals(actual)) {
            throw new AssertionError(
                    "Pinned formatter matrix differs from "
                            + DEFAULT_MATRIX
                            + "; do not regenerate it with an unpinned runtime");
        }
        System.out.print(actual);
    }
}
