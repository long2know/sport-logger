package com.long2know.sportlogger.acquisition;

public final class MeasurementUnits {
    private MeasurementUnits() {
    }

    public static double paceMillisecondsPerKilometerToSecondsPerKilometer(
            double paceMillisecondsPerKilometer) {
        requireNonNegativeFinite("pace", paceMillisecondsPerKilometer);
        return paceMillisecondsPerKilometer / 1_000D;
    }

    public static double metersPerSecondToKilometersPerHour(double metersPerSecond) {
        requireNonNegativeFinite("speed", metersPerSecond);
        return metersPerSecond * 3.6D;
    }

    public static double metersToKilometers(double meters) {
        requireNonNegativeFinite("distance", meters);
        return meters / 1_000D;
    }

    public static int countToLegacyInt(long count) {
        if (count <= 0L) {
            return 0;
        }
        return count >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    private static void requireNonNegativeFinite(String name, double value) {
        if (!Double.isFinite(value) || value < 0D) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
