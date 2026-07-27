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
    public void nonblockingFenceRequestInvalidatesWriterBeforeAwait() {
        FakeSchedulerFactory schedulers = new FakeSchedulerFactory();
        RecordingWriterCoordinator coordinator =
                new RecordingWriterCoordinator(schedulers);
        AtomicInteger writes = new AtomicInteger();
        Object owner = new Object();
        coordinator.start(owner, 13, token -> writes::incrementAndGet);
        long generation = coordinator.generationFor(owner, 13);

        assertTrue(coordinator.requestFenceGeneration(13, generation));
        schedulers.schedulers.get(0).runTask();

        assertEquals(0, writes.get());
        assertTrue(schedulers.schedulers.get(0).shutdownRequested);
    }

    @Test
    public void staleOperationCannotPublishOrScheduleANewWriterGeneration() {
        FakeSchedulerFactory schedulers = new FakeSchedulerFactory();
        RecordingWriterCoordinator coordinator =
                new RecordingWriterCoordinator(schedulers);
        Object owner = new Object();

        assertEquals(
                RecordingWriterCoordinator.StartStatus.START_FAILED,
                coordinator.start(
                        owner,
                        17,
                        token -> () -> { },
                        token -> false,
                        (token, exception) -> { }));

        assertEquals(0, schedulers.schedulers.size());
        assertEquals(
                RecordingWriterCoordinator.StartStatus.STARTED,
                coordinator.start(owner, 18, token -> () -> { }));
    }

    @Test
    public void slowTaskFactoryDoesNotBlockFenceAndClosesStaleTask()
            throws Exception {
        FakeSchedulerFactory schedulers = new FakeSchedulerFactory();
        RecordingWriterCoordinator coordinator =
                new RecordingWriterCoordinator(schedulers);
        Object owner = new Object();
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        CountDownLatch fenceReturned = new CountDownLatch(1);
        AtomicReference<RecordingWriterCoordinator.GenerationToken> token =
                new AtomicReference<>();
        AtomicReference<RecordingWriterCoordinator.StartStatus> startStatus =
                new AtomicReference<>();
        CloseTrackingTask task = new CloseTrackingTask();

        Thread startThread = new Thread(() -> startStatus.set(
                coordinator.start(owner, 31, generation -> {
                    token.set(generation);
                    factoryEntered.countDown();
                    awaitUninterruptibly(releaseFactory);
                    return task;
                })));
        startThread.start();
        assertTrue(factoryEntered.await(1, TimeUnit.SECONDS));

        Thread fenceThread = new Thread(() -> {
            assertTrue(coordinator.requestFenceGeneration(
                    token.get().getActivityId(),
                    token.get().getGeneration()));
            fenceReturned.countDown();
        });
        fenceThread.start();

        assertTrue(fenceReturned.await(1, TimeUnit.SECONDS));
        assertEquals(1L, releaseFactory.getCount());
        releaseFactory.countDown();
        startThread.join(1_000L);
        fenceThread.join(1_000L);

        assertEquals(
                RecordingWriterCoordinator.StartStatus.START_FAILED,
                startStatus.get());
        assertEquals(1, task.closeCalls.get());
        assertTrue(schedulers.schedulers.get(0).shutdownRequested);
        assertEquals(null, schedulers.schedulers.get(0).task);
    }

    @Test
    public void fencedReservationCannotReplaceANewerPublishedGeneration()
            throws Exception {
        FakeSchedulerFactory schedulers = new FakeSchedulerFactory();
        RecordingWriterCoordinator coordinator =
                new RecordingWriterCoordinator(schedulers);
        Object oldOwner = new Object();
        Object replacementOwner = new Object();
        CountDownLatch oldFactoryEntered = new CountDownLatch(1);
        CountDownLatch releaseOldFactory = new CountDownLatch(1);
        AtomicReference<RecordingWriterCoordinator.GenerationToken> oldToken =
                new AtomicReference<>();
        AtomicReference<RecordingWriterCoordinator.StartStatus> oldStatus =
                new AtomicReference<>();
        CloseTrackingTask oldTask = new CloseTrackingTask();

        Thread oldStart = new Thread(() -> oldStatus.set(
                coordinator.start(oldOwner, 32, generation -> {
                    oldToken.set(generation);
                    oldFactoryEntered.countDown();
                    awaitUninterruptibly(releaseOldFactory);
                    return oldTask;
                })));
        oldStart.start();
        assertTrue(oldFactoryEntered.await(1, TimeUnit.SECONDS));
        assertTrue(coordinator.requestFenceGeneration(
                oldToken.get().getActivityId(),
                oldToken.get().getGeneration()));

        assertEquals(
                RecordingWriterCoordinator.StartStatus.STARTED,
                coordinator.start(
                        replacementOwner, 33, generation -> () -> { }));
        long replacementGeneration =
                coordinator.generationFor(replacementOwner, 33);
        releaseOldFactory.countDown();
        oldStart.join(1_000L);

        assertEquals(
                RecordingWriterCoordinator.StartStatus.START_FAILED,
                oldStatus.get());
        assertEquals(1, oldTask.closeCalls.get());
        assertTrue(schedulers.schedulers.get(0).shutdownRequested);
        assertFalse(schedulers.schedulers.get(1).shutdownRequested);
        assertEquals(
                replacementGeneration,
                coordinator.generationFor(replacementOwner, 33));
    }

    @Test
    public void failingTaskFactoryReleasesReservationAndScheduler() {
        FakeSchedulerFactory schedulers = new FakeSchedulerFactory();
        RecordingWriterCoordinator coordinator =
                new RecordingWriterCoordinator(schedulers);
        Object owner = new Object();

        assertEquals(
                RecordingWriterCoordinator.StartStatus.START_FAILED,
                coordinator.start(owner, 34, generation -> {
                    throw new RuntimeException("open failed");
                }));

        assertTrue(schedulers.schedulers.get(0).shutdownRequested);
        assertEquals(
                RecordingWriterCoordinator.StartStatus.STARTED,
                coordinator.start(owner, 35, generation -> () -> { }));
    }

    @Test
    public void successfulFenceClosesConstructedTaskExactlyOnce() {
        FakeSchedulerFactory schedulers = new FakeSchedulerFactory();
        RecordingWriterCoordinator coordinator =
                new RecordingWriterCoordinator(schedulers);
        Object owner = new Object();
        CloseTrackingTask task = new CloseTrackingTask();

        assertEquals(
                RecordingWriterCoordinator.StartStatus.STARTED,
                coordinator.start(owner, 36, generation -> task));
        assertEquals(
                LifecycleTermination.TERMINATED,
                coordinator.fenceOwned(owner, 50));
        assertEquals(
                LifecycleTermination.TERMINATED,
                coordinator.fenceOwned(owner, 50));

        assertEquals(1, task.closeCalls.get());
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
    public void staleOperationCannotFenceWhateverWriterAReplacementOwns() {
        FakeSchedulerFactory schedulers = new FakeSchedulerFactory();
        RecordingWriterCoordinator coordinator =
                new RecordingWriterCoordinator(schedulers);
        Object replacementOwner = new Object();
        coordinator.start(replacementOwner, 3, token -> () -> { });

        assertEquals(
                LifecycleTermination.TERMINATED,
                coordinator.fenceAny(() -> false, 50));

        assertEquals(0, schedulers.schedulers.get(0).shutdownCalls);
        assertTrue(coordinator.generationFor(replacementOwner, 3) > 0L);
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
    public void writerFailureReleasedDuringServiceDestroyCannotSubmitLateCleanup() {
        FakeSchedulerFactory schedulers = new FakeSchedulerFactory();
        RecordingWriterCoordinator coordinator =
                new RecordingWriterCoordinator(schedulers);
        RecordingOperationDispatcher dispatcher =
                new RecordingOperationDispatcher(
                        new RecordingOperationDispatcher.Executor() {
                            boolean shutdown;

                            @Override
                            public void execute(Runnable task) {
                                if (shutdown) {
                                    throw new java.util.concurrent
                                            .RejectedExecutionException();
                                }
                                task.run();
                            }

                            @Override
                            public void shutdownNow() {
                                shutdown = true;
                            }
                        });
        long serviceGeneration = dispatcher.getServiceGeneration();
        AtomicReference<RecordingOperationDispatcher.DispatchStatus> dispatch =
                new AtomicReference<>();
        AtomicInteger staleUiNotifications = new AtomicInteger();

        coordinator.start(
                new Object(),
                19,
                token -> () -> {
                    throw new RuntimeException("write failed during destroy");
                },
                (token, exception) -> dispatch.set(
                        dispatcher.tryExecute(
                                serviceGeneration,
                                staleUiNotifications::incrementAndGet)));

        dispatcher.close();
        schedulers.schedulers.get(0).runTask();

        assertEquals(
                RecordingOperationDispatcher.DispatchStatus.STALE,
                dispatch.get());
        assertEquals(0, staleUiNotifications.get());
    }

    @Test
    public void concurrentTerminalFencesCannotHideGenerationFailure()
            throws Exception {
        ConcurrentFenceScheduler scheduler = new ConcurrentFenceScheduler();
        RecordingWriterCoordinator coordinator =
                new RecordingWriterCoordinator(() -> scheduler);
        Object owner = new Object();
        coordinator.start(
                owner,
                10,
                token -> () -> {
                    throw new RuntimeException("write failed");
                });
        scheduler.task.run();

        AtomicReference<LifecycleTermination> first = new AtomicReference<>();
        AtomicReference<LifecycleTermination> second = new AtomicReference<>();
        Thread firstFence = new Thread(
                () -> first.set(coordinator.fenceOwned(owner, 1_000)));
        Thread secondFence = new Thread(
                () -> second.set(coordinator.fenceOwned(owner, 1_000)));
        firstFence.start();
        secondFence.start();

        assertTrue(scheduler.awaitersEntered.await(1, TimeUnit.SECONDS));
        scheduler.terminated = true;
        scheduler.releaseAwaiters.countDown();
        firstFence.join(1_000L);
        secondFence.join(1_000L);

        assertEquals(
                LifecycleTermination.TERMINATED_WITH_FAILURE, first.get());
        assertEquals(
                LifecycleTermination.TERMINATED_WITH_FAILURE, second.get());
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

    @Test
    public void reservedPositiveGenerationExistsBeforeTaskConstruction() {
        FakeSchedulerFactory schedulers = new FakeSchedulerFactory();
        RecordingWriterCoordinator coordinator =
                new RecordingWriterCoordinator(schedulers);
        Object owner = new Object();
        long reservedGeneration = coordinator.allocateGeneration();
        AtomicReference<RecordingWriterCoordinator.GenerationToken>
                factoryGeneration = new AtomicReference<>();

        assertTrue(reservedGeneration > 0L);
        assertEquals(
                RecordingWriterCoordinator.StartStatus.STARTED,
                coordinator.start(
                        owner,
                        121,
                        reservedGeneration,
                        generation -> {
                            factoryGeneration.set(generation);
                            return () -> { };
                        },
                        generation -> generation.getGeneration()
                                == reservedGeneration,
                        (generation, exception) -> { }));
        assertEquals(
                reservedGeneration,
                factoryGeneration.get().getGeneration());
        assertEquals(
                reservedGeneration,
                coordinator.generationFor(owner, 121));
    }

    @Test
    public void invalidOwnershipIsRejectedBeforeFactoryConstruction() {
        FakeSchedulerFactory schedulers = new FakeSchedulerFactory();
        RecordingWriterCoordinator coordinator =
                new RecordingWriterCoordinator(schedulers);
        AtomicInteger factoryCalls = new AtomicInteger();

        assertEquals(
                RecordingWriterCoordinator.StartStatus.START_FAILED,
                coordinator.start(
                        new Object(),
                        0,
                        1L,
                        generation -> {
                            factoryCalls.incrementAndGet();
                            return () -> { };
                        },
                        generation -> true,
                        (generation, exception) -> { }));
        assertEquals(
                RecordingWriterCoordinator.StartStatus.START_FAILED,
                coordinator.start(
                        new Object(),
                        122,
                        -1L,
                        generation -> {
                            factoryCalls.incrementAndGet();
                            return () -> { };
                        },
                        generation -> true,
                        (generation, exception) -> { }));
        assertEquals(0, factoryCalls.get());
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

    private static final class CloseTrackingTask
            implements RecordingWriterCoordinator.Task {
        final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public void run() {
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
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

    private static final class ConcurrentFenceScheduler
            implements RecordingWriterCoordinator.Scheduler {
        final CountDownLatch awaitersEntered = new CountDownLatch(2);
        final CountDownLatch releaseAwaiters = new CountDownLatch(1);
        volatile boolean terminated;
        Runnable task;

        @Override
        public void scheduleAtFixedRate(
                Runnable task, long initialDelay, long period, TimeUnit unit) {
            this.task = task;
        }

        @Override
        public void shutdownNow() {
        }

        @Override
        public boolean awaitTermination(long timeoutMillis)
                throws InterruptedException {
            awaitersEntered.countDown();
            return releaseAwaiters.await(
                    timeoutMillis, TimeUnit.MILLISECONDS) && terminated;
        }

        @Override
        public boolean isTerminated() {
            return terminated;
        }
    }
}
