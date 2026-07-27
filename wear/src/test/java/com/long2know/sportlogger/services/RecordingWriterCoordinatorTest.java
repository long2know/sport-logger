package com.long2know.sportlogger.services;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecordingWriterCoordinatorTest {
    @Test
    public void duplicateStartDoesNotReplaceOrOrphanScheduler() {
        FakeSchedulerFactory schedulers = new FakeSchedulerFactory();
        RecordingWriterCoordinator coordinator =
                new RecordingWriterCoordinator(schedulers);
        Object owner = new Object();

        assertEquals(
                RecordingWriterCoordinator.StartStatus.STARTED,
                coordinator.start(owner, 11, token -> () -> { }));
        assertEquals(
                RecordingWriterCoordinator.StartStatus.ALREADY_RUNNING,
                coordinator.start(owner, 11, token -> () -> { }));
        assertEquals(1, schedulers.schedulers.size());

        assertEquals(
                LifecycleTermination.TERMINATED,
                coordinator.fenceOwned(owner, 50));
        assertEquals(1, schedulers.schedulers.get(0).shutdownCalls);
    }

    @Test
    public void taskKeepsImmutableActivityBindingAcrossLaterGlobalChanges() {
        FakeSchedulerFactory schedulers = new FakeSchedulerFactory();
        RecordingWriterCoordinator coordinator =
                new RecordingWriterCoordinator(schedulers);
        AtomicInteger mutableGlobalActivityId = new AtomicInteger(41);
        AtomicReference<Integer> writtenActivityId = new AtomicReference<>();

        coordinator.start(new Object(), mutableGlobalActivityId.get(), token ->
                () -> writtenActivityId.set(token.getActivityId()));
        mutableGlobalActivityId.set(99);
        schedulers.schedulers.get(0).runTask();

        assertEquals(Integer.valueOf(41), writtenActivityId.get());
    }

    @Test
    public void timedOutWriterBlocksReplacementUntilTerminationIsObserved() {
        FakeSchedulerFactory schedulers = new FakeSchedulerFactory();
        RecordingWriterCoordinator coordinator =
                new RecordingWriterCoordinator(schedulers);
        Object firstOwner = new Object();
        Object secondOwner = new Object();

        coordinator.start(firstOwner, 1, token -> () -> { });
        FakeScheduler first = schedulers.schedulers.get(0);
        first.terminateOnAwait = false;

        assertEquals(
                LifecycleTermination.TIMED_OUT,
                coordinator.fenceOwned(firstOwner, 25));
        assertEquals(
                RecordingWriterCoordinator.StartStatus.PREVIOUS_GENERATION_ACTIVE,
                coordinator.start(secondOwner, 2, token -> () -> { }));
        assertEquals(1, schedulers.schedulers.size());

        first.terminated = true;
        assertEquals(
                RecordingWriterCoordinator.StartStatus.STARTED,
                coordinator.start(secondOwner, 2, token -> () -> { }));
        assertEquals(2, schedulers.schedulers.size());
    }

    @Test
    public void cancelledOrInterruptedGenerationDoesNotEnterWriteTask() {
        FakeSchedulerFactory schedulers = new FakeSchedulerFactory();
        RecordingWriterCoordinator coordinator =
                new RecordingWriterCoordinator(schedulers);
        AtomicInteger writes = new AtomicInteger();
        Object owner = new Object();

        coordinator.start(owner, 7, token -> writes::incrementAndGet);
        FakeScheduler scheduler = schedulers.schedulers.get(0);
        coordinator.fenceOwned(owner, 25);

        scheduler.runTask();

        assertEquals(0, writes.get());
        assertTrue(scheduler.shutdownRequested);
    }

    @Test
    public void staleOwnerCannotFenceAReplacementGeneration() {
        FakeSchedulerFactory schedulers = new FakeSchedulerFactory();
        RecordingWriterCoordinator coordinator =
                new RecordingWriterCoordinator(schedulers);
        Object firstOwner = new Object();
        Object replacementOwner = new Object();

        coordinator.start(firstOwner, 1, token -> () -> { });
        assertEquals(
                LifecycleTermination.TERMINATED,
                coordinator.fenceOwned(firstOwner, 50));
        coordinator.start(replacementOwner, 2, token -> () -> { });

        assertEquals(
                LifecycleTermination.TERMINATED,
                coordinator.fenceOwned(firstOwner, 50));
        assertEquals(0, schedulers.schedulers.get(1).shutdownCalls);
        assertEquals(
                LifecycleTermination.TERMINATED,
                coordinator.fenceOwned(replacementOwner, 50));
    }

    @Test
    public void fenceDoesNotReturnBeforeInFlightTaskCompletes() throws Exception {
        BlockingScheduler scheduler = new BlockingScheduler();
        RecordingWriterCoordinator coordinator =
                new RecordingWriterCoordinator(() -> scheduler);
        Object owner = new Object();
        coordinator.start(owner, 8, token -> scheduler::blockUntilReleased);
        assertTrue(scheduler.entered.await(1, TimeUnit.SECONDS));

        AtomicReference<LifecycleTermination> result = new AtomicReference<>();
        CountDownLatch fenceReturned = new CountDownLatch(1);
        Thread fenceThread = new Thread(() -> {
            result.set(coordinator.fenceOwned(owner, 1_000));
            fenceReturned.countDown();
        });
        fenceThread.start();

        assertTrue(scheduler.shutdownRequestedLatch.await(1, TimeUnit.SECONDS));
        assertEquals(1L, fenceReturned.getCount());
        scheduler.allowCompletion.countDown();
        assertTrue(fenceReturned.await(1, TimeUnit.SECONDS));
        fenceThread.join(1_000L);

        assertEquals(LifecycleTermination.TERMINATED, result.get());
        assertEquals(0L, scheduler.completed.getCount());
    }

    @Test
    public void taskFailureIsReportedAndTheFailedGenerationQuiesces() {
        FakeSchedulerFactory schedulers = new FakeSchedulerFactory();
        RecordingWriterCoordinator coordinator =
                new RecordingWriterCoordinator(schedulers);
        Object owner = new Object();
        RuntimeException failure = new RuntimeException("write failed");
        AtomicReference<RuntimeException> reported = new AtomicReference<>();

        assertEquals(
                RecordingWriterCoordinator.StartStatus.STARTED,
                coordinator.start(
                        owner,
                        9,
                        token -> () -> {
                            throw failure;
                        },
                        (token, exception) -> reported.set(exception)));
        schedulers.schedulers.get(0).runTask();

        assertEquals(failure, reported.get());
        assertTrue(schedulers.schedulers.get(0).shutdownRequested);
        assertEquals(
                LifecycleTermination.TERMINATED_WITH_FAILURE,
                coordinator.fenceOwned(owner, 50));
    }

    @Test
    public void replacementCanFenceOnlyTheExactRetainedGeneration() {
        FakeSchedulerFactory schedulers = new FakeSchedulerFactory();
        RecordingWriterCoordinator coordinator =
                new RecordingWriterCoordinator(schedulers);
        Object oldOwner = new Object();

        coordinator.restoreGenerationFloor(20L);
        coordinator.start(oldOwner, 101, token -> () -> { });
        assertEquals(21L, coordinator.generationFor(oldOwner, 101));

        assertEquals(
                LifecycleTermination.TIMED_OUT,
                coordinator.fenceGeneration(101, 20L, 50));
        assertEquals(0, schedulers.schedulers.get(0).shutdownCalls);
        assertEquals(
                LifecycleTermination.TIMED_OUT,
                coordinator.fenceGeneration(100, 21L, 50));
        assertEquals(0, schedulers.schedulers.get(0).shutdownCalls);

        assertEquals(
                LifecycleTermination.TERMINATED,
                coordinator.fenceGeneration(101, 21L, 50));
        assertEquals(1, schedulers.schedulers.get(0).shutdownCalls);
    }

    @Test
    public void restoredGenerationFloorKeepsReplacementGenerationMonotonic() {
        FakeSchedulerFactory schedulers = new FakeSchedulerFactory();
        RecordingWriterCoordinator coordinator =
                new RecordingWriterCoordinator(schedulers);
        Object replacement = new Object();

        coordinator.restoreGenerationFloor(37L);
        assertEquals(
                RecordingWriterCoordinator.StartStatus.STARTED,
                coordinator.start(replacement, 111, token -> () -> { }));
        assertEquals(38L, coordinator.generationFor(replacement, 111));
    }

    private static final class FakeSchedulerFactory
            implements RecordingWriterCoordinator.SchedulerFactory {
        final List<FakeScheduler> schedulers = new ArrayList<>();

        @Override
        public RecordingWriterCoordinator.Scheduler create() {
            FakeScheduler scheduler = new FakeScheduler();
            schedulers.add(scheduler);
            return scheduler;
        }
    }

    private static final class FakeScheduler
            implements RecordingWriterCoordinator.Scheduler {
        Runnable task;
        boolean shutdownRequested;
        boolean terminateOnAwait = true;
        boolean terminated;
        int shutdownCalls;

        @Override
        public void scheduleAtFixedRate(
                Runnable task, long initialDelay, long period, TimeUnit unit) {
            this.task = task;
        }

        @Override
        public void shutdownNow() {
            shutdownRequested = true;
            shutdownCalls++;
        }

        @Override
        public boolean awaitTermination(long timeoutMillis) {
            if (terminateOnAwait) {
                terminated = true;
            }
            return terminated;
        }

        @Override
        public boolean isTerminated() {
            return terminated;
        }

        void runTask() {
            assertFalse(task == null);
            task.run();
        }
    }

    private static final class BlockingScheduler
            implements RecordingWriterCoordinator.Scheduler {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch allowCompletion = new CountDownLatch(1);
        final CountDownLatch completed = new CountDownLatch(1);
        final CountDownLatch shutdownRequestedLatch = new CountDownLatch(1);
        Thread worker;
        volatile boolean terminated;

        @Override
        public void scheduleAtFixedRate(
                Runnable task, long initialDelay, long period, TimeUnit unit) {
            worker = new Thread(() -> {
                task.run();
                terminated = true;
                completed.countDown();
            });
            worker.start();
        }

        @Override
        public void shutdownNow() {
            shutdownRequestedLatch.countDown();
            worker.interrupt();
        }

        @Override
        public boolean awaitTermination(long timeoutMillis) throws InterruptedException {
            return completed.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        @Override
        public boolean isTerminated() {
            return terminated;
        }

        void blockUntilReleased() {
            entered.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    allowCompletion.await();
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
