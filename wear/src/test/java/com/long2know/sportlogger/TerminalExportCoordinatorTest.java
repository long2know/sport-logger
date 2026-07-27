package com.long2know.sportlogger;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TerminalExportCoordinatorTest {
    @Test
    public void failedExportCanBeRetried() {
        TerminalExportCoordinator coordinator =
                new TerminalExportCoordinator();

        TerminalExportCoordinator.Attempt first = coordinator.begin(41L);
        assertNotNull(first);
        assertNull(coordinator.begin(41L));
        assertTrue(coordinator.failed(first));
        assertNotNull(coordinator.begin(41L));
    }

    @Test
    public void handoffRemainsInFlightUntilAsyncAcknowledgmentFinishes() {
        TerminalExportCoordinator coordinator =
                new TerminalExportCoordinator();

        TerminalExportCoordinator.Attempt first = coordinator.begin(51L);
        assertNotNull(first);
        assertTrue(coordinator.succeeded(first));
        assertFalse(coordinator.failed(first));
        assertNull(coordinator.begin(51L));
        assertTrue(coordinator.acknowledgmentFinished(51L));
        assertFalse(coordinator.isInFlight());

        TerminalExportCoordinator.Attempt second = coordinator.begin(51L);
        assertNotNull(second);
        assertTrue(coordinator.succeeded(second));
        assertTrue(coordinator.acknowledgmentFinished(51L));
        assertFalse(coordinator.isInFlight());
    }

    @Test
    public void staleCallbacksCannotReleaseAnotherAttempt() {
        TerminalExportCoordinator coordinator =
                new TerminalExportCoordinator();

        TerminalExportCoordinator.Attempt stale = coordinator.begin(61L);
        assertNotNull(stale);
        assertTrue(coordinator.failed(stale));
        TerminalExportCoordinator.Attempt current = coordinator.begin(61L);
        assertNotNull(current);

        assertFalse(coordinator.succeeded(stale));
        assertFalse(coordinator.failed(stale));
        assertTrue(coordinator.isInFlight());
        assertFalse(coordinator.acknowledgmentFinished(60L));
        assertTrue(coordinator.isInFlight());
        assertTrue(coordinator.failed(current));
        assertFalse(coordinator.isInFlight());
    }
}
