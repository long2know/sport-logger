package com.long2know.sportlogger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
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
        assertTrue(coordinator.acknowledgmentStarted(51L));
        assertTrue(coordinator.acknowledgmentFinished(51L, true));
        assertFalse(coordinator.isInFlight());

        TerminalExportCoordinator.Attempt second = coordinator.begin(51L);
        assertNotNull(second);
        assertTrue(coordinator.succeeded(second));
        assertTrue(coordinator.acknowledgmentStarted(51L));
        assertTrue(coordinator.acknowledgmentFinished(51L, true));
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
        assertFalse(coordinator.acknowledgmentFinished(60L, true));
        assertTrue(coordinator.isInFlight());
        assertTrue(coordinator.failed(current));
        assertFalse(coordinator.isInFlight());
    }

    @Test
    public void teardownAfterSendSuccessLeavesAckRetryableOnReconnect() {
        TerminalExportCoordinator coordinator =
                new TerminalExportCoordinator();
        TerminalExportCoordinator.Attempt attempt = coordinator.begin(71L);

        assertTrue(coordinator.succeeded(attempt));
        assertEquals(71L, coordinator.acknowledgmentOperationId());
        assertTrue(coordinator.acknowledgmentStarted(71L));
        assertTrue(coordinator.isAcknowledgmentInProgress());
        assertFalse(coordinator.acknowledgmentStarted(71L));

        assertTrue(coordinator.acknowledgmentRetryRequired(71L));
        assertFalse(coordinator.isAcknowledgmentInProgress());
        assertTrue(coordinator.acknowledgmentStarted(71L));
        assertTrue(coordinator.acknowledgmentFinished(71L, true));
        assertFalse(coordinator.isInFlight());
    }

    @Test
    public void acknowledgmentFailureAndExecutorRejectionKeepExactAckPending() {
        TerminalExportCoordinator coordinator =
                new TerminalExportCoordinator();
        TerminalExportCoordinator.Attempt attempt = coordinator.begin(81L);

        assertTrue(coordinator.succeeded(attempt));
        assertTrue(coordinator.acknowledgmentStarted(81L));
        assertTrue(coordinator.acknowledgmentStartFailed(81L));
        assertEquals(81L, coordinator.acknowledgmentOperationId());

        assertTrue(coordinator.acknowledgmentStarted(81L));
        assertTrue(coordinator.acknowledgmentFinished(81L, false));
        assertEquals(81L, coordinator.acknowledgmentOperationId());
        assertTrue(coordinator.acknowledgmentStarted(81L));
        assertTrue(coordinator.acknowledgmentFinished(81L, true));
        assertFalse(coordinator.isInFlight());
    }

    @Test
    public void staleAckCannotReleaseNewerExportAfterRecreation() {
        TerminalExportCoordinator coordinator =
                new TerminalExportCoordinator();
        TerminalExportCoordinator.Attempt first = coordinator.begin(91L);
        assertTrue(coordinator.succeeded(first));
        assertTrue(coordinator.acknowledgmentStarted(91L));
        assertTrue(coordinator.acknowledgmentFinished(91L, true));

        TerminalExportCoordinator.Attempt second = coordinator.begin(92L);
        assertNotNull(second);
        assertFalse(coordinator.acknowledgmentFinished(91L, true));
        assertTrue(coordinator.isInFlight());
        assertTrue(coordinator.failed(second));
    }

    @Test
    public void completedAckAfterRecreationAllowsASubsequentStop() {
        TerminalExportCoordinator coordinator =
                new TerminalExportCoordinator();
        TerminalExportCoordinator.Attempt first = coordinator.begin(101L);
        assertTrue(coordinator.succeeded(first));
        assertTrue(coordinator.acknowledgmentStarted(101L));

        assertTrue(coordinator.acknowledgmentFinished(101L, true));

        TerminalExportCoordinator.Attempt subsequent =
                coordinator.begin(102L);
        assertNotNull(subsequent);
        assertTrue(coordinator.failed(subsequent));
    }
}
