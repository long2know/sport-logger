import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class LocaleTimestampProbe {
    private LocaleTimestampProbe() {
    }

    private static void check(
            Date value,
            String tag,
            int expectedZero,
            String expectedCalendar,
            String expectedFormatted) {
        Locale locale = Locale.forLanguageTag(tag);
        SimpleDateFormat formatter =
                new SimpleDateFormat("yyyyMMddHHmmss", locale);
        formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        char zero = DecimalFormatSymbols.getInstance(locale).getZeroDigit();
        String calendar = formatter.getCalendar().getCalendarType();
        String formatted = formatter.format(value);
        if (zero != expectedZero
                || !calendar.equals(expectedCalendar)
                || !formatted.equals(expectedFormatted)) {
            throw new AssertionError(
                    tag
                            + " expected zero=U+"
                            + String.format("%04X", expectedZero)
                            + " calendar="
                            + expectedCalendar
                            + " formatted="
                            + expectedFormatted
                            + " but found zero=U+"
                            + String.format("%04X", (int) zero)
                            + " calendar="
                            + calendar
                            + " formatted="
                            + formatted);
        }
        System.out.printf(
                "%s zero=U+%04X calendar=%s formatted=%s%n",
                tag,
                (int) zero,
                calendar,
                formatted);
    }

    public static void main(String[] args) {
        Date value = Date.from(Instant.parse("2024-07-08T09:10:11Z"));
        System.out.println(
                "java.runtime.version=" + System.getProperty("java.runtime.version"));
        check(value, "ar-EG", 0x0660, "gregory", "٢٠٢٤٠٧٠٨٠٩١٠١١");
        check(value, "bn-BD", 0x09E6, "gregory", "২০২৪০৭০৮০৯১০১১");
        check(value, "en-US", 0x0030, "gregory", "20240708091011");
        check(value, "th-TH-u-nu-thai", 0x0E50, "buddhist", "๒๕๖๗๐๗๐๘๐๙๑๐๑๑");
        check(
                value,
                "th-TH-u-ca-gregory-nu-thai",
                0x0E50,
                "gregory",
                "๒๐๒๔๐๗๐๘๐๙๑๐๑๑");
        check(value, "th-TH-u-nu-latn", 0x0030, "buddhist", "25670708091011");
    }
}
