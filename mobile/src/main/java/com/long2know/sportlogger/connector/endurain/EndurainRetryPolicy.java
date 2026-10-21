package com.long2know.sportlogger.connector.endurain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;

public final class EndurainRetryPolicy {
    private static final Duration DEFAULT_RATE_LIMIT_DELAY = Duration.ofMinutes(1);
    private static final Duration MIN_RATE_LIMIT_DELAY = Duration.ofSeconds(1);
    private static final Duration MAX_RATE_LIMIT_DELAY = Duration.ofHours(24);

    private final Duration initialDelay;
    private final Duration maximumDelay;

    public EndurainRetryPolicy(Duration initialDelay, Duration maximumDelay) {
        this.initialDelay = requirePositive(initialDelay, "initialDelay");
        this.maximumDelay = requirePositive(maximumDelay, "maximumDelay");
        if (initialDelay.compareTo(maximumDelay) > 0) {
            throw new IllegalArgumentException(
                    "Initial retry delay must not exceed maximum delay");
        }
    }

    public static EndurainRetryPolicy defaults() {
        return new EndurainRetryPolicy(
                Duration.ofSeconds(2),
                Duration.ofMinutes(5));
    }

    public Duration preTransmissionDelay(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("Retry attempt must be positive");
        }
        long delayMillis = initialDelay.toMillis();
        long maximumMillis = maximumDelay.toMillis();
        for (int current = 1;
                current < attempt && delayMillis < maximumMillis;
                current++) {
            if (delayMillis > maximumMillis / 2) {
                delayMillis = maximumMillis;
            } else {
                delayMillis *= 2;
            }
        }
        return Duration.ofMillis(Math.min(delayMillis, maximumMillis));
    }

    public Duration rateLimitDelay(String retryAfter, Instant now) {
        Objects.requireNonNull(now, "now");
        if (retryAfter == null || retryAfter.trim().isEmpty()) {
            return DEFAULT_RATE_LIMIT_DELAY;
        }

        String value = retryAfter.trim();
        try {
            long seconds = Long.parseLong(value);
            if (seconds < 0) {
                return DEFAULT_RATE_LIMIT_DELAY;
            }
            return clamp(Duration.ofSeconds(seconds));
        } catch (ArithmeticException | NumberFormatException ignored) {
        }

        try {
            Instant retryAt = ZonedDateTime.parse(
                    value,
                    DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return clamp(Duration.between(now, retryAt));
        } catch (DateTimeParseException ignored) {
            return DEFAULT_RATE_LIMIT_DELAY;
        }
    }

    private static Duration clamp(Duration delay) {
        if (delay.compareTo(MIN_RATE_LIMIT_DELAY) < 0) {
            return MIN_RATE_LIMIT_DELAY;
        }
        if (delay.compareTo(MAX_RATE_LIMIT_DELAY) > 0) {
            return MAX_RATE_LIMIT_DELAY;
        }
        return delay;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
