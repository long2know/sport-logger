package com.long2know.sportlogger;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TerminalExportCoordinatorTest {
    @Test
    public void failedOrAbortedExportCanBeRetried() {
        TerminalExportCoordinator coordinator =
                new TerminalExportCoordinator();

        assertTrue(coordinator.begin(41L));
        assertFalse(coordinator.begin(41L));
        coordinator.failed(41L);
        assertTrue(coordinator.begin(41L));
        coordinator.abandon();
        assertTrue(coordinator.begin(41L));
    }

    @Test
    public void onlySuccessfulAcknowledgmentCompletesHandoff() {
        TerminalExportCoordinator coordinator =
                new TerminalExportCoordinator();
        AtomicInteger acknowledgments = new AtomicInteger();

        assertTrue(coordinator.begin(51L));
        assertFalse(coordinator.succeeded(51L, operationId -> {
            acknowledgments.incrementAndGet();
            return false;
        }));
        assertFalse(coordinator.isInFlight());

        assertTrue(coordinator.begin(51L));
        assertTrue(coordinator.succeeded(51L, operationId -> {
            acknowledgments.incrementAndGet();
            return true;
        }));
        assertFalse(coordinator.isInFlight());
        assertFalse(coordinator.succeeded(51L, operationId -> true));
        assertTrue(acknowledgments.get() == 2);
    }

    @Test
    public void acknowledgmentExceptionLeavesAttemptRetryable() {
        TerminalExportCoordinator coordinator =
                new TerminalExportCoordinator();

        assertTrue(coordinator.begin(61L));
        assertFalse(coordinator.succeeded(61L, operationId -> {
            throw new RuntimeException("service disappeared");
        }));
        assertFalse(coordinator.isInFlight());
        assertTrue(coordinator.begin(61L));
    }
}
