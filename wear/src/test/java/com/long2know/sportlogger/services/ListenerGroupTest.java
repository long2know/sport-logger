package com.long2know.sportlogger.services;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ListenerGroupTest {
    @Test
    public void shutdownWaitsForBothListenerAcknowledgements() {
        AtomicBoolean active = new AtomicBoolean();
        FakeListener sensors = new FakeListener(true);
        FakeListener location = new FakeListener(true);
        ListenerGroup group =
                new ListenerGroup(active, sensors, location, "test");

        assertEquals(LifecycleTermination.TERMINATED, group.start(100));
        assertTrue(active.get());

        assertEquals(LifecycleTermination.TERMINATED, group.shutdown(100));
        assertTrue(sensors.shutdownRequested);
        assertTrue(location.shutdownRequested);
        assertFalse(active.get());
    }

    @Test
    public void missingAcknowledgementReturnsBoundedTimeoutAndKeepsGateClosed() {
        AtomicBoolean active = new AtomicBoolean();
        FakeListener sensors = new FakeListener(true);
        FakeListener location = new FakeListener(false);
        ListenerGroup group =
                new ListenerGroup(active, sensors, location, "test");
        assertEquals(LifecycleTermination.TERMINATED, group.start(100));

        long startedAt = System.nanoTime();
        assertEquals(LifecycleTermination.TIMED_OUT, group.shutdown(20));
        long elapsedMillis =
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertTrue(elapsedMillis < 500L);
        assertFalse(active.get());
    }

    @Test
    public void startupRequiresBothListenerAcknowledgements() {
        AtomicBoolean active = new AtomicBoolean();
        FakeListener sensors = new FakeListener(true);
        FakeListener location = new FakeListener(true, false);
        ListenerGroup group =
                new ListenerGroup(active, sensors, location, "test");

        assertEquals(LifecycleTermination.TIMED_OUT, group.start(20));
        assertTrue(sensors.shutdownRequested);
        assertTrue(location.shutdownRequested);
        assertFalse(active.get());
    }

    @Test
    public void nonblockingShutdownRequestClosesListenerGenerationImmediately() {
        AtomicBoolean active = new AtomicBoolean();
        FakeListener sensors = new FakeListener(false);
        FakeListener location = new FakeListener(false);
        ListenerGroup group =
                new ListenerGroup(active, sensors, location, "test");
        assertEquals(LifecycleTermination.TERMINATED, group.start(100));

        group.requestShutdown();

        assertFalse(active.get());
        assertTrue(sensors.shutdownRequested);
        assertTrue(location.shutdownRequested);
    }

    @Test
    public void shutdownRequestedBeforeStartupNeverReopensTheListenerGate() {
        AtomicBoolean active = new AtomicBoolean();
        FakeListener sensors = new FakeListener(true);
        FakeListener location = new FakeListener(true);
        ListenerGroup group =
                new ListenerGroup(active, sensors, location, "test");

        group.requestShutdown();

        assertEquals(LifecycleTermination.TERMINATED, group.start(100));
        assertFalse(active.get());
        assertTrue(sensors.shutdownRequested);
        assertTrue(location.shutdownRequested);
    }

    private static final class FakeListener implements ManagedListener {
        private final boolean _acknowledgeShutdown;
        private final boolean _acknowledgeReady;
        private final CountDownLatch _ready = new CountDownLatch(1);
        private final CountDownLatch _stopped = new CountDownLatch(1);
        volatile boolean shutdownRequested;

        FakeListener(boolean acknowledgeShutdown) {
            this(acknowledgeShutdown, true);
        }

        FakeListener(boolean acknowledgeShutdown, boolean acknowledgeReady) {
            _acknowledgeShutdown = acknowledgeShutdown;
            _acknowledgeReady = acknowledgeReady;
        }

        @Override
        public void run() {
            if (_acknowledgeReady) {
                _ready.countDown();
            }
        }

        @Override
        public void requestShutdown() {
            shutdownRequested = true;
            if (_acknowledgeShutdown) {
                _stopped.countDown();
            }
        }

        @Override
        public boolean awaitStopped(long timeoutMillis) throws InterruptedException {
            return _stopped.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        @Override
        public boolean awaitReady(long timeoutMillis) throws InterruptedException {
            return _ready.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }
    }
}
