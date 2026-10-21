package com.long2know.sportlogger.persistence.watch;

import com.long2know.utilities.contract.CanonicalContractException;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;

public final class LegacyTimestampParser {
    private static final int[] VERIFIED_ZERO_CODE_POINTS = {
            0x0030, 0x0660, 0x06F0, 0x07C0, 0x0966, 0x09E6, 0x0A66, 0x0AE6,
            0x0B66, 0x0BE6, 0x0C66, 0x0CE6, 0x0D66, 0x0DE6, 0x0E50, 0x0ED0,
            0x0F20, 0x1040, 0x1090, 0x17E0, 0x1810, 0x1946, 0x19D0, 0x1A80,
            0x1A90, 0x1B50, 0x1BB0, 0x1C40, 0x1C50, 0xA620, 0xA8D0, 0xA900,
            0xA9D0, 0xA9F0, 0xAA50, 0xABF0, 0xFF10, 0x104A0, 0x10D30, 0x10D40,
            0x11066, 0x110F0, 0x11136, 0x111D0, 0x112F0, 0x11450, 0x114D0,
            0x11650, 0x116C0, 0x116D0, 0x116DA, 0x11730, 0x118E0, 0x11950,
            0x11BF0, 0x11C50, 0x11D50, 0x11DA0, 0x11F50, 0x16130, 0x16A60,
            0x16AC0, 0x16B50, 0x16D70, 0x1CCF0, 0x1D7CE, 0x1D7D8, 0x1D7E2,
            0x1D7EC, 0x1D7F6, 0x1E140, 0x1E2F0, 0x1E4F0, 0x1E5F1, 0x1E950,
            0x1FBF0
    };

    private LegacyTimestampParser() {
    }

    public static long parseUtcMillis(String value) {
        if (value == null) {
            throw invalid("Legacy timestamp is required");
        }
        int[] codePoints = value.codePoints().toArray();
        if (codePoints.length != 14) {
            throw invalid("Legacy timestamp must contain exactly 14 decimal digits");
        }
        int firstDigit = Character.digit(codePoints[0], 10);
        if (firstDigit < 0) {
            throw invalid("Legacy timestamp contains a non-decimal character");
        }
        int zeroCodePoint = codePoints[0] - firstDigit;
        if (Arrays.binarySearch(VERIFIED_ZERO_CODE_POINTS, zeroCodePoint) < 0) {
            throw invalid("Legacy timestamp uses an unverified decimal digit block");
        }
        int[] digits = new int[14];
        for (int index = 0; index < codePoints.length; index++) {
            int digit = Character.digit(codePoints[index], 10);
            if (digit < 0 || codePoints[index] - digit != zeroCodePoint) {
                throw invalid("Legacy timestamp mixes digit blocks or non-decimal characters");
            }
            digits[index] = digit;
        }
        int year = number(digits, 0, 4);
        int month = number(digits, 4, 2);
        int day = number(digits, 6, 2);
        int hour = number(digits, 8, 2);
        int minute = number(digits, 10, 2);
        int second = number(digits, 12, 2);
        try {
            return LocalDateTime.of(year, month, day, hour, minute, second)
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli();
        } catch (DateTimeException error) {
            throw new CanonicalContractException(
                    "INVALID_LEGACY_TIMESTAMP",
                    "Legacy timestamp contains an invalid Gregorian UTC date",
                    error);
        }
    }

    private static int number(int[] digits, int offset, int count) {
        int value = 0;
        for (int index = offset; index < offset + count; index++) {
            value = value * 10 + digits[index];
        }
        return value;
    }

    private static CanonicalContractException invalid(String message) {
        return new CanonicalContractException("INVALID_LEGACY_TIMESTAMP", message);
    }
}
