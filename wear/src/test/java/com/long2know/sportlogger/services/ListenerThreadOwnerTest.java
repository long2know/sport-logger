package com.long2know.sportlogger.services;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ListenerThreadOwnerTest {
    @Test
    public void oldInstanceStopsBeforeAlreadyCreatedReplacementStarts() throws Exception {
        ListenerThreadOwner owner = new ListenerThreadOwner("location-test", 1000L);
        FakeManagedListener oldListener = new FakeManagedListener();
        owner.replace(oldListener);
        assertTrue(oldListener.awaitStarted());

        FakeManagedListener alreadyCreatedReplacement = new FakeManagedListener();
        owner.replace(alreadyCreatedReplacement);

        assertEquals(1, oldListener.stopCalls.get());
        assertTrue(oldListener.awaitStopped(1, TimeUnit.SECONDS));
        assertTrue(alreadyCreatedReplacement.awaitStarted());
        assertSame(alreadyCreatedReplacement, owner.getActiveListener());

        owner.stop();
        assertEquals(1, alreadyCreatedReplacement.stopCalls.get());
    }

    @Test
    public void serviceDestroyRecreateStopsEachExactListenerAndThread() throws Exception {
        ListenerThreadOwner destroyedServiceOwner =
                new ListenerThreadOwner("location-service-1", 1000L);
        FakeManagedListener destroyedServiceListener = new FakeManagedListener();
        destroyedServiceOwner.replace(destroyedServiceListener);
        assertTrue(destroyedServiceListener.awaitStarted());
        Thread destroyedServiceThread = destroyedServiceOwner.getActiveThread();

        destroyedServiceOwner.stop();

        ListenerThreadOwner recreatedServiceOwner =
                new ListenerThreadOwner("location-service-2", 1000L);
        FakeManagedListener recreatedServiceListener = new FakeManagedListener();
        recreatedServiceOwner.replace(recreatedServiceListener);
        assertTrue(recreatedServiceListener.awaitStarted());
        Thread recreatedServiceThread = recreatedServiceOwner.getActiveThread();
        recreatedServiceOwner.stop();

        assertEquals(1, destroyedServiceListener.stopCalls.get());
        assertEquals(1, recreatedServiceListener.stopCalls.get());
        assertFalse(destroyedServiceThread.isAlive());
        assertFalse(recreatedServiceThread.isAlive());
        assertNull(destroyedServiceOwner.getActiveListener());
        assertNull(recreatedServiceOwner.getActiveListener());
    }

    @Test
    public void repeatedDestroyIsIdempotent() throws Exception {
        ListenerThreadOwner owner = new ListenerThreadOwner("location-test", 1000L);
        FakeManagedListener listener = new FakeManagedListener();
        owner.replace(listener);
        assertTrue(listener.awaitStarted());

        owner.stop();
        owner.stop();

        assertEquals(1, listener.stopCalls.get());
        assertTrue(listener.awaitStopped(1, TimeUnit.SECONDS));
        assertNull(owner.getActiveListener());
        assertNull(owner.getActiveThread());
    }

    @Test
    public void stopFailureRemainsVisibleAfterThreadIsJoinedAndReleased() throws Exception {
        ListenerThreadOwner owner = new ListenerThreadOwner("location-test", 1000L);
        FakeManagedListener listener = new FakeManagedListener();
        listener.stopFailure = new IllegalStateException("stop failed");
        owner.replace(listener);
        assertTrue(listener.awaitStarted());

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, owner::stop);

        assertEquals("stop failed", exception.getMessage());
        assertEquals(1, listener.stopCalls.get());
        assertTrue(listener.awaitStopped(1, TimeUnit.SECONDS));
        assertNull(owner.getActiveListener());
        assertNull(owner.getActiveThread());
    }

    private static final class FakeManagedListener
            implements ListenerThreadOwner.ManagedListener {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch stopRequested = new CountDownLatch(1);
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final AtomicInteger stopCalls = new AtomicInteger();
        private RuntimeException stopFailure;

        @Override
        public void run() {
            started.countDown();
            try {
                stopRequested.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                stopped.countDown();
            }
        }

        @Override
        public void requestStop() {
            stopCalls.incrementAndGet();
            stopRequested.countDown();
            if (stopFailure != null) {
                throw stopFailure;
            }
        }

        @Override
        public boolean awaitStopped(long timeout, TimeUnit unit) throws InterruptedException {
            return stopped.await(timeout, unit);
        }

        private boolean awaitStarted() throws InterruptedException {
            return started.await(1, TimeUnit.SECONDS);
        }
    }
}
