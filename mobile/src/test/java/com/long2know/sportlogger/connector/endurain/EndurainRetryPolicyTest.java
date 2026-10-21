package com.long2know.sportlogger.connector.endurain;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.Assert.assertEquals;

public class EndurainRetryPolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-21T04:00:00Z");

    @Test
    public void preTransmissionBackoffIsExponentialAndBounded() {
        EndurainRetryPolicy policy = new EndurainRetryPolicy(
                Duration.ofSeconds(2),
                Duration.ofSeconds(10));

        assertEquals(Duration.ofSeconds(2), policy.preTransmissionDelay(1));
        assertEquals(Duration.ofSeconds(4), policy.preTransmissionDelay(2));
        assertEquals(Duration.ofSeconds(8), policy.preTransmissionDelay(3));
        assertEquals(Duration.ofSeconds(10), policy.preTransmissionDelay(4));
        assertEquals(Duration.ofSeconds(10), policy.preTransmissionDelay(30));
    }

    @Test
    public void retryAfterSupportsDeltaAndHttpDateWithSafeBounds() {
        EndurainRetryPolicy policy = EndurainRetryPolicy.defaults();

        assertEquals(Duration.ofSeconds(120), policy.rateLimitDelay("120", NOW));
        assertEquals(
                Duration.ofMinutes(2),
                policy.rateLimitDelay("Wed, 21 Oct 2026 04:02:00 GMT", NOW));
        assertEquals(Duration.ofSeconds(1), policy.rateLimitDelay("0", NOW));
        assertEquals(Duration.ofHours(24), policy.rateLimitDelay("9999999", NOW));
        assertEquals(Duration.ofMinutes(1), policy.rateLimitDelay("invalid", NOW));
    }
}
