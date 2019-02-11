package com.long2know.sportlogger;

import android.os.Handler;

import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;

public final class Config {
    public static MainActivity context = null;

    public static Handler handler = null;

    public static final DecimalFormat SevenSigDigits = new DecimalFormat("0.#######");
    public static final DecimalFormat TwoSigDigits = new DecimalFormat("0.##");
    public static final DateFormat TimestampFormat = new SimpleDateFormat("yyyyMMddHHmmss");
    public static final DateFormat DotnetTimestampFormat = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss");
}

