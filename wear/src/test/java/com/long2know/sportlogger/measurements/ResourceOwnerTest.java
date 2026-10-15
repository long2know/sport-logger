package com.long2know.sportlogger.measurements;

import com.long2know.utilities.models.LocationData;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ResourceOwnerTest {
    private final List<ResourceOwner> owners = new ArrayList<>();
    private final Map<ResourceOwner, ScheduledThreadPoolExecutor> workers =
            new IdentityHashMap<>();

    @After
    public void tearDown() {
        for (ResourceOwner owner : owners) {
            owner.stopAsync();
        }
    }

    @Test
    public void stopReturnsWithin100msWhileMultiSecondCleanupIsBlockedThenTerminates() throws Exception {
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);

        ResourceOwner owner = owner(
                "delayed",
                (ownership, sink) -> {
                    ownership.own("platform-listener", () -> {
                        cleanupEntered.countDown();
                        releaseCleanup.await(5, TimeUnit.SECONDS);
                    });
                    opened.countDown();
                },
                new RecordingListener(),
                attempt -> 1L);

        owner.startAsync();
        assertTrue(opened.await(1, TimeUnit.SECONDS));

        long started = System.nanoTime();
        owner.stopAsync();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue("stopAsync took " + elapsedMillis + "ms", elapsedMillis < 100L);
        assertTrue(cleanupEntered.await(1, TimeUnit.SECONDS));
        assertFalse(worker(owner).isTerminated());

        releaseCleanup.countDown();
        assertTrue(worker(owner).awaitTermination(1, TimeUnit.SECONDS));
    }

    @Test
    public void partialRegistrationIsCleanedWhenLaterRegistrationFails() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        CountDownLatch failed = new CountDownLatch(1);
        RecordingListener listener = new RecordingListener(failed);
        ResourceOwner owner = owner(
                "partial",
                (ownership, sink) -> {
                    ownership.own("receiver", closed::incrementAndGet);
                    throw new ResourceException(
                            new ResourceFailure(
                                    "location",
                                    ResourceFailure.Code.REGISTRATION_FAILED,
                                    "request failed"),
                            false);
                },
                listener,
                attempt -> 1L);

        owner.startAsync();

        assertTrue(failed.await(1, TimeUnit.SECONDS));
        assertEquals(1, closed.get());
        assertEquals(ResourceFailure.Code.REGISTRATION_FAILED, listener.failures.get(0).getCode());
    }

    @Test
    public void cleanupFailureDoesNotPreventRemainingCleanup() throws Exception {
        AtomicInteger firstClosed = new AtomicInteger();
        AtomicInteger thirdClosed = new AtomicInteger();
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch cleanupFailed = new CountDownLatch(1);
        RecordingListener listener = new RecordingListener(cleanupFailed);
        ResourceOwner owner = owner(
                "cleanup",
                (ownership, sink) -> {
                    ownership.own("first", firstClosed::incrementAndGet);
                    ownership.own("failing", () -> {
                        throw new IllegalStateException("cleanup failed");
                    });
                    ownership.own("third", thirdClosed::incrementAndGet);
                    opened.countDown();
                },
                listener,
                attempt -> 1L);

        owner.startAsync();
        assertTrue(opened.await(1, TimeUnit.SECONDS));
        owner.stopAsync();

        assertTrue(cleanupFailed.await(1, TimeUnit.SECONDS));
        assertTrue(worker(owner).awaitTermination(1, TimeUnit.SECONDS));
        assertEquals(1, firstClosed.get());
        assertEquals(1, thirdClosed.get());
        assertEquals(ResourceFailure.Code.CLEANUP_FAILED, listener.failures.get(0).getCode());
    }

    @Test
    public void delayedRegistrationCannotResurrectAfterStop() throws Exception {
        CountDownLatch registrationEntered = new CountDownLatch(1);
        CountDownLatch releaseRegistration = new CountDownLatch(1);
        AtomicInteger closed = new AtomicInteger();
        RecordingListener listener = new RecordingListener();
        ResourceOwner owner = owner(
                "stale",
                (ownership, sink) -> {
                    ownership.own("listener", closed::incrementAndGet);
                    registrationEntered.countDown();
                    releaseRegistration.await(5, TimeUnit.SECONDS);
                    sink.onEvent(MeasurementEvent.location(1L, new LocationData()));
                },
                listener,
                attempt -> 1L);

        owner.startAsync();
        assertTrue(registrationEntered.await(1, TimeUnit.SECONDS));
        owner.stopAsync();
        releaseRegistration.countDown();

        assertTrue(worker(owner).awaitTermination(1, TimeUnit.SECONDS));
        assertEquals(1, closed.get());
        assertTrue(listener.events.isEmpty());
    }

    @Test
    public void duplicateAndOutOfOrderCallbacksAreDropped() throws Exception {
        AtomicReference<ResourceOwner.EventSink> sinkReference = new AtomicReference<>();
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(2);
        RecordingListener listener = new RecordingListener(null, delivered);
        ResourceOwner owner = owner(
                "dedupe",
                (ownership, sink) -> {
                    sinkReference.set(sink);
                    opened.countDown();
                },
                listener,
                attempt -> 1L);

        owner.startAsync();
        assertTrue(opened.await(1, TimeUnit.SECONDS));
        ResourceOwner.EventSink sink = sinkReference.get();
        sink.onEvent(MeasurementEvent.heartRate(10L, 120F));
        sink.onEvent(MeasurementEvent.heartRate(10L, 121F));
        sink.onEvent(MeasurementEvent.heartRate(9L, 122F));
        sink.onEvent(MeasurementEvent.heartRate(11L, 123F));

        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        assertEquals(2, listener.events.size());
        assertEquals(120F, listener.events.get(0).getHeartRate(), 0F);
        assertEquals(123F, listener.events.get(1).getHeartRate(), 0F);
    }

    @Test
    public void missingLocationRetriesWithoutStoppingSensorDimension() throws Exception {
        AtomicInteger locationAttempts = new AtomicInteger();
        CountDownLatch locationFailed = new CountDownLatch(1);
        CountDownLatch sensorDelivered = new CountDownLatch(1);
        RecordingListener listener = new RecordingListener(locationFailed, sensorDelivered);

        ResourceOwner location = owner(
                "location",
                (ownership, sink) -> {
                    locationAttempts.incrementAndGet();
                    throw new ResourceException(
                            new ResourceFailure(
                                    "location",
                                    ResourceFailure.Code.UNAVAILABLE,
                                    "provider missing"),
                            true);
                },
                listener,
                attempt -> 10L);
        ResourceOwner sensors = owner(
                "sensors",
                (ownership, sink) ->
                        sink.onEvent(MeasurementEvent.steps(1L, 1)),
                listener,
                attempt -> 10L);

        MeasurementRuntime runtime = new MeasurementRuntime(location, sensors);
        runtime.startAsync();

        assertTrue(locationFailed.await(1, TimeUnit.SECONDS));
        assertTrue(sensorDelivered.await(1, TimeUnit.SECONDS));
        assertTrue(waitUntil(() -> locationAttempts.get() >= 2, 1_000L));
        assertEquals(1, listener.events.size());
        runtime.stopAsync();
    }

    @Test
    public void permissionRevocationCleansOwnershipAndRetries() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        AtomicReference<ResourceOwner.EventSink> firstSink = new AtomicReference<>();
        CountDownLatch firstOpen = new CountDownLatch(1);
        CountDownLatch secondOpen = new CountDownLatch(1);
        RecordingListener listener = new RecordingListener();
        ResourceOwner owner = owner(
                "permission",
                (ownership, sink) -> {
                    ownership.own("location", closes::incrementAndGet);
                    if (opens.incrementAndGet() == 1) {
                        firstSink.set(sink);
                        firstOpen.countDown();
                    } else {
                        secondOpen.countDown();
                    }
                },
                listener,
                attempt -> 1L);

        owner.startAsync();
        assertTrue(firstOpen.await(1, TimeUnit.SECONDS));
        firstSink.get().onInvalidated(new ResourceException(
                new ResourceFailure(
                        "location",
                        ResourceFailure.Code.PERMISSION_DENIED,
                        "permission revoked"),
                true));

        assertTrue(secondOpen.await(1, TimeUnit.SECONDS));
        assertEquals(2, opens.get());
        assertEquals(1, closes.get());
    }

    @Test
    public void initialPermissionDenialIsReportedAndRetriedAfterGrant() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch denied = new CountDownLatch(1);
        CountDownLatch registered = new CountDownLatch(1);
        RecordingListener listener = new RecordingListener(denied);
        ResourceOwner owner = owner(
                "permission",
                (ownership, sink) -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new ResourceException(
                                new ResourceFailure(
                                        "location",
                                        ResourceFailure.Code.PERMISSION_DENIED,
                                        "permission denied"),
                                true);
                    }
                    ownership.own("location", () -> {
                    });
                    registered.countDown();
                },
                listener,
                attempt -> 1L);

        owner.startAsync();

        assertTrue(denied.await(1, TimeUnit.SECONDS));
        assertTrue(registered.await(1, TimeUnit.SECONDS));
        assertEquals(2, attempts.get());
        assertEquals(ResourceFailure.Code.PERMISSION_DENIED, listener.failures.get(0).getCode());
    }

    private ResourceOwner owner(
            String name,
            ResourceOwner.Source source,
            RecordingListener listener,
            ResourceOwner.RetryPolicy retryPolicy) {
        ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1);
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        ResourceOwner owner = new ResourceOwner(name, source, worker, listener, retryPolicy);
        owners.add(owner);
        workers.put(owner, worker);
        return owner;
    }

    private ScheduledThreadPoolExecutor worker(ResourceOwner owner) {
        return workers.get(owner);
    }

    private static boolean waitUntil(Check check, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (check.evaluate()) {
                return true;
            }
            Thread.sleep(5L);
        }
        return check.evaluate();
    }

    private interface Check {
        boolean evaluate();
    }

    private static final class RecordingListener implements ResourceOwner.Listener {
        private final List<MeasurementEvent> events =
                Collections.synchronizedList(new ArrayList<>());
        private final List<ResourceFailure> failures =
                Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch failureLatch;
        private final CountDownLatch eventLatch;

        private RecordingListener() {
            this(null, null);
        }

        private RecordingListener(CountDownLatch failureLatch) {
            this(failureLatch, null);
        }

        private RecordingListener(CountDownLatch failureLatch, CountDownLatch eventLatch) {
            this.failureLatch = failureLatch;
            this.eventLatch = eventLatch;
        }

        @Override
        public void onEvent(MeasurementEvent event) {
            events.add(event);
            if (eventLatch != null) {
                eventLatch.countDown();
            }
        }

        @Override
        public void onFailure(ResourceFailure failure) {
            failures.add(failure);
            if (failureLatch != null) {
                failureLatch.countDown();
            }
        }
    }
}
