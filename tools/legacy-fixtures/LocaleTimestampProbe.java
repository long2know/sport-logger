import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class LocaleTimestampProbe {
    private LocaleTimestampProbe() {
    }

    public static void main(String[] args) {
        Date value = Date.from(Instant.parse("2024-07-08T09:10:11Z"));
        System.out.println(
                "java.runtime.version=" + System.getProperty("java.runtime.version"));
        for (String tag : new String[] {"ar-EG", "bn-BD", "en-US"}) {
            Locale locale = Locale.forLanguageTag(tag);
            SimpleDateFormat formatter =
                    new SimpleDateFormat("yyyyMMddHHmmss", locale);
            formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
            char zero = DecimalFormatSymbols.getInstance(locale).getZeroDigit();
            System.out.printf(
                    "%s zero=U+%04X formatted=%s%n",
                    tag,
                    (int) zero,
                    formatter.format(value));
        }
    }
}
