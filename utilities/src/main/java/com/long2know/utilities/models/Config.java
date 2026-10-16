package com.long2know.utilities.models;

import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class Config {
    public static final DecimalFormat SevenSigDigits = new DecimalFormat("0.#######");
    public static final DecimalFormat TwoSigDigits = new DecimalFormat("0.##");
    private static final DateFormat TIMESTAMP_FORMAT =
            new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
    private static final DateFormat DOTNET_TIMESTAMP_FORMAT =
            new SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.US);

    private Config() {
    }

    public static synchronized String formatTimestamp(Date date) {
        return TIMESTAMP_FORMAT.format(date);
    }

    public static synchronized Date parseTimestamp(String timestamp) throws ParseException {
        return TIMESTAMP_FORMAT.parse(timestamp);
    }

    public static synchronized String formatDotnetTimestamp(Date date) {
        return DOTNET_TIMESTAMP_FORMAT.format(date);
    }
}
