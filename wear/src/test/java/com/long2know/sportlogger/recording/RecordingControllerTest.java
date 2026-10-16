package com.long2know.sportlogger.recording;

import com.long2know.utilities.models.SportActivity;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RecordingControllerTest {
    private final List<ScheduledThreadPoolExecutor> workers = new ArrayList<>();

    @After
    public void tearDown() {
        for (ScheduledThreadPoolExecutor worker : workers) {
            worker.shutdownNow();
        }
    }

    @Test
    public void stopAndDestroyReturnWithin100msWhileFinishIsHeldForFiveSeconds()
            throws Exception {
        InMemoryJournal journal = new InMemoryJournal();
        FakeStore store = new FakeStore();
        FakeExporter exporter = new FakeExporter();
        MutableClock clock = new MutableClock(1_000L);
        SnapshotRecorder snapshots = new SnapshotRecorder();
        ScheduledThreadPoolExecutor firstWorker = worker();
        RecordingController first = controller(
                journal,
                store,
                exporter,
                firstWorker,
                clock,
                snapshots);

        first.start();
        snapshots.awaitState(RecordingState.RECORDING);
        store.blockFinish = true;

        long stopStarted = System.nanoTime();
        assertTrue(first.stop());
        long stopMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStarted);
        assertTrue("stop took " + stopMillis + "ms", stopMillis < 100L);
        assertTrue(store.finishEntered.await(1, TimeUnit.SECONDS));
        long holdStarted = System.nanoTime();

        long destroyStarted = System.nanoTime();
        first.closeAsync();
        long destroyMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - destroyStarted);
        assertTrue("close took " + destroyMillis + "ms", destroyMillis < 100L);

        assertFalse(firstWorker.awaitTermination(4_900L, TimeUnit.MILLISECONDS));
        long remainingNanos = TimeUnit.SECONDS.toNanos(5L)
                - (System.nanoTime() - holdStarted);
        if (remainingNanos > 0L) {
            TimeUnit.NANOSECONDS.sleep(remainingNanos);
        }
        long heldMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - holdStarted);
        store.releaseFinish.countDown();
        assertTrue(firstWorker.awaitTermination(1, TimeUnit.SECONDS));
        System.out.println(
                "TIMING_EVIDENCE controllerStopMillis="
                        + stopMillis
                        + " controllerCloseMillis="
                        + destroyMillis
                        + " heldFinishMillis="
                        + heldMillis);
        assertTrue("finish held only " + heldMillis + "ms", heldMillis >= 5_000L);
        assertEquals(RecordingState.STOPPING, journal.state.getState());

        store.blockFinish = false;
        SnapshotRecorder replacementSnapshots = new SnapshotRecorder();
        ScheduledThreadPoolExecutor replacementWorker = worker();
        RecordingController replacement = controller(
                journal,
                store,
                exporter,
                replacementWorker,
                clock,
                replacementSnapshots);
        replacement.recover();

        replacementSnapshots.awaitState(RecordingState.EXPORT_PENDING);
        assertTrue(exporter.awaitCount(1, 1_000L));
        exporter.queue(0);
        replacementSnapshots.awaitState(RecordingState.EXPORT_QUEUED);
        assertTrue(exporter.awaitCount(2, 1_000L));
        exporter.acknowledge(1);
        replacementSnapshots.awaitState(RecordingState.IDLE);
        replacement.closeAsync();
    }

    @Test
    public void consecutiveOfflineWorkoutsQueueIndependentlyWithoutReportingCompletion()
            throws Exception {
        InMemoryJournal journal = new InMemoryJournal();
        FakeStore store = new FakeStore();
        FakeExporter exporter = new FakeExporter();
        MutableClock clock = new MutableClock(2_000L);
        SnapshotRecorder snapshots = new SnapshotRecorder();
        RecordingController controller = controller(
                journal,
                store,
                exporter,
                worker(),
                clock,
                snapshots);

        controller.start();
        snapshots.awaitState(RecordingState.RECORDING);
        controller.stop();
        snapshots.awaitState(RecordingState.EXPORT_PENDING);
        assertTrue(exporter.awaitCount(1, 1_000L));
        exporter.queue(0);
        snapshots.awaitState(RecordingState.EXPORT_QUEUED);
        assertEquals(RecordingState.EXPORT_QUEUED, journal.state.getState());

        assertTrue(controller.start());
        snapshots.awaitState(RecordingState.RECORDING);
        assertEquals(2, store.createCount.get());
        controller.stop();
        snapshots.awaitState(RecordingState.EXPORT_PENDING);
        assertTrue(exporter.awaitCount(2, 1_000L));
        exporter.queue(1);
        snapshots.awaitState(RecordingState.EXPORT_QUEUED);

        RecordingExporter.Callback staleCallback = exporter.callback(0);
        staleCallback.onAcknowledged();
        staleCallback.onFailure(new IllegalStateException("late duplicate"));
        Thread.sleep(20L);
        assertEquals(RecordingState.EXPORT_QUEUED, controller.getSnapshot().getState());

        assertTrue(exporter.awaitCount(3, 1_000L));
        exporter.acknowledge(2);
        snapshots.awaitState(RecordingState.IDLE);
        controller.closeAsync();
    }

    @Test
    public void pausedSessionRecoversAcrossActivityRecreationWithDurationPreserved()
            throws Exception {
        InMemoryJournal journal = new InMemoryJournal();
        FakeStore store = new FakeStore();
        FakeExporter exporter = new FakeExporter();
        MutableClock clock = new MutableClock(10_000L);
        SnapshotRecorder firstSnapshots = new SnapshotRecorder();
        ScheduledThreadPoolExecutor firstWorker = worker();
        RecordingController first = controller(
                journal,
                store,
                exporter,
                firstWorker,
                clock,
                firstSnapshots);

        first.start();
        firstSnapshots.awaitState(RecordingState.RECORDING);
        clock.advance(5_000L);
        first.pause();
        RecordingSnapshot paused = firstSnapshots.awaitState(RecordingState.PAUSED);
        assertEquals(5_000L, paused.getDurationMillis());
        first.closeAsync();
        assertTrue(firstWorker.awaitTermination(1, TimeUnit.SECONDS));

        SnapshotRecorder replacementSnapshots = new SnapshotRecorder();
        RecordingController replacement = controller(
                journal,
                store,
                exporter,
                worker(),
                clock,
                replacementSnapshots);
        replacement.recover();
        assertEquals(
                5_000L,
                replacementSnapshots.awaitState(RecordingState.PAUSED).getDurationMillis());

        replacement.resume();
        replacementSnapshots.awaitState(RecordingState.RECORDING);
        clock.advance(2_000L);
        replacement.stop();
        RecordingSnapshot exportPending =
                replacementSnapshots.awaitState(RecordingState.EXPORT_PENDING);
        assertEquals(7_000L, exportPending.getDurationMillis());
        assertTrue(exporter.awaitCount(1, 1_000L));
        exporter.queue(0);
        replacementSnapshots.awaitState(RecordingState.EXPORT_QUEUED);
        assertTrue(exporter.awaitCount(2, 1_000L));
        exporter.acknowledge(1);
        replacementSnapshots.awaitState(RecordingState.IDLE);
        replacement.closeAsync();
    }

    @Test
    public void failingFinalizationIsReportedAndRetriedWithoutReturningSuccess()
            throws Exception {
        InMemoryJournal journal = new InMemoryJournal();
        FakeStore store = new FakeStore();
        store.finishFailures.set(1);
        FakeExporter exporter = new FakeExporter();
        SnapshotRecorder snapshots = new SnapshotRecorder();
        RecordingController controller = controller(
                journal,
                store,
                exporter,
                worker(),
                new MutableClock(20_000L),
                snapshots);

        controller.start();
        snapshots.awaitState(RecordingState.RECORDING);
        controller.stop();

        RecordingSnapshot failed = snapshots.awaitError(RecordingError.Code.STORAGE);
        assertEquals(RecordingState.STOPPING, failed.getState());
        assertTrue(exporter.awaitCount(1, 1_000L));
        assertTrue(store.finishAttempts.get() >= 2);
        exporter.acknowledge(0);
        snapshots.awaitState(RecordingState.IDLE);
        controller.closeAsync();
    }

    @Test
    public void exportFailureRemainsDurableAndRetriesUntilAcknowledged() throws Exception {
        InMemoryJournal journal = new InMemoryJournal();
        FakeStore store = new FakeStore();
        FakeExporter exporter = new FakeExporter();
        SnapshotRecorder snapshots = new SnapshotRecorder();
        RecordingController controller = controller(
                journal,
                store,
                exporter,
                worker(),
                new MutableClock(30_000L),
                snapshots);

        controller.start();
        snapshots.awaitState(RecordingState.RECORDING);
        controller.stop();
        snapshots.awaitState(RecordingState.EXPORT_PENDING);
        assertTrue(exporter.awaitCount(1, 1_000L));
        exporter.fail(0, new IllegalStateException("offline"));

        RecordingSnapshot failed = snapshots.awaitError(RecordingError.Code.EXPORT);
        assertEquals(RecordingState.EXPORT_PENDING, failed.getState());
        assertEquals(RecordingState.EXPORT_PENDING, journal.state.getState());
        assertTrue(exporter.awaitCount(2, 1_000L));
        exporter.acknowledge(1);
        snapshots.awaitState(RecordingState.IDLE);
        controller.closeAsync();
    }

    @Test
    public void durabilityFailureLeavesRecordingActiveAndReportsTheError() throws Exception {
        InMemoryJournal journal = new InMemoryJournal();
        FakeStore store = new FakeStore();
        SnapshotRecorder snapshots = new SnapshotRecorder();
        RecordingController controller = controller(
                journal,
                store,
                new FakeExporter(),
                worker(),
                new MutableClock(40_000L),
                snapshots);

        controller.start();
        snapshots.awaitState(RecordingState.RECORDING);
        journal.failWrites.set(1);
        controller.stop();

        RecordingSnapshot failed = snapshots.awaitError(RecordingError.Code.DURABILITY);
        assertEquals(RecordingState.RECORDING, failed.getState());
        assertEquals(RecordingState.RECORDING, controller.getSnapshot().getState());
        assertEquals(0, store.finishAttempts.get());
        controller.closeAsync();
    }

    @Test
    public void malformedDurableStateFailsClosedInsteadOfBecomingIdle() throws Exception {
        InMemoryJournal journal = new InMemoryJournal();
        journal.readFailure = new IllegalStateException("malformed");
        FakeStore store = new FakeStore();
        SnapshotRecorder snapshots = new SnapshotRecorder();
        RecordingController controller = controller(
                journal,
                store,
                new FakeExporter(),
                worker(),
                new MutableClock(50_000L),
                snapshots);

        controller.recover();

        RecordingSnapshot failed = snapshots.awaitState(RecordingState.ERROR);
        assertNotNull(failed.getError());
        assertEquals(RecordingError.Code.RECOVERY, failed.getError().getCode());
        assertEquals(0, store.createCount.get());
        controller.start();
        snapshots.awaitError(RecordingError.Code.COMMAND_REJECTED);
        assertEquals(RecordingState.ERROR, controller.getSnapshot().getState());
        controller.closeAsync();
    }

    private RecordingController controller(
            InMemoryJournal journal,
            FakeStore store,
            FakeExporter exporter,
            ScheduledThreadPoolExecutor worker,
            MutableClock clock,
            SnapshotRecorder snapshots) throws Exception {
        return new RecordingController(
                journal,
                store,
                exporter,
                worker,
                Runnable::run,
                clock,
                snapshots,
                attempt -> 5L);
    }

    private ScheduledThreadPoolExecutor worker() {
        ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1);
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        workers.add(worker);
        return worker;
    }

    private static final class MutableClock implements RecordingController.Clock {
        private final AtomicLong now;

        private MutableClock(long now) {
            this.now = new AtomicLong(now);
        }

        @Override
        public long currentTimeMillis() {
            return now.get();
        }

        private void advance(long millis) {
            now.addAndGet(millis);
        }
    }

    private static final class InMemoryJournal implements RecordingJournal {
        private final AtomicLong currentLease = new AtomicLong();
        private final AtomicInteger failWrites = new AtomicInteger();
        private volatile PersistentRecording state = PersistentRecording.idle();
        private volatile RuntimeException readFailure;

        @Override
        public long acquireLease() {
            return currentLease.incrementAndGet();
        }

        @Override
        public PersistentRecording read() {
            if (readFailure != null) {
                throw readFailure;
            }
            return state;
        }

        @Override
        public synchronized void write(long lease, PersistentRecording recording) {
            if (!isCurrent(lease)) {
                throw new IllegalStateException("stale lease");
            }
            if (failWrites.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new IllegalStateException("journal unavailable");
            }
            state = recording;
        }

        @Override
        public boolean isCurrent(long lease) {
            return currentLease.get() == lease;
        }

        @Override
        public synchronized boolean runIfCurrent(long lease, Runnable callback) {
            if (!isCurrent(lease)) {
                return false;
            }
            callback.run();
            return true;
        }
    }

    private static final class FakeStore implements RecordingStore {
        private final AtomicInteger nextId = new AtomicInteger();
        private final AtomicInteger createCount = new AtomicInteger();
        private final AtomicInteger finishAttempts = new AtomicInteger();
        private final AtomicInteger finishFailures = new AtomicInteger();
        private final Map<Integer, SportActivity> activities = new ConcurrentHashMap<>();
        private final CountDownLatch finishEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFinish = new CountDownLatch(1);
        private volatile boolean blockFinish;

        @Override
        public int createActivity(long startedAtMillis) {
            int id = nextId.incrementAndGet();
            SportActivity activity = new SportActivity();
            activity.Id = id;
            activity.StartTimeUTC = new Date(startedAtMillis);
            activities.put(id, activity);
            createCount.incrementAndGet();
            return id;
        }

        @Override
        public void writeSample(int activityId) {
            if (!activities.containsKey(activityId)) {
                throw new IllegalStateException("missing activity");
            }
        }

        @Override
        public void finishActivity(int activityId, long endedAtMillis) throws Exception {
            finishAttempts.incrementAndGet();
            if (blockFinish) {
                finishEntered.countDown();
                releaseFinish.await(30, TimeUnit.SECONDS);
            }
            if (finishFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new IllegalStateException("database busy");
            }
            SportActivity activity = activities.get(activityId);
            if (activity == null) {
                throw new IllegalStateException("missing activity");
            }
            activity.EndTimeUTC = new Date(endedAtMillis);
        }

        @Override
        public void deleteActivity(int activityId) {
            activities.remove(activityId);
        }

        @Override
        public SportActivity loadActivity(int activityId) {
            SportActivity activity = activities.get(activityId);
            if (activity == null) {
                throw new IllegalStateException("missing activity");
            }
            return activity;
        }
    }

    private static final class FakeExporter implements RecordingExporter {
        private final List<Callback> callbacks = new ArrayList<>();

        @Override
        public synchronized void export(SportActivity activity, Callback callback) {
            callbacks.add(callback);
            notifyAll();
        }

        private synchronized boolean awaitCount(int count, long timeoutMillis)
                throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (callbacks.size() < count) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0L) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(this, remainingNanos);
            }
            return true;
        }

        private synchronized Callback callback(int index) {
            return callbacks.get(index);
        }

        private void queue(int index) {
            callback(index).onQueued();
        }

        private void acknowledge(int index) {
            callback(index).onAcknowledged();
        }

        private void fail(int index, Throwable error) {
            callback(index).onFailure(error);
        }
    }

    private static final class SnapshotRecorder implements RecordingController.Listener {
        private final LinkedBlockingQueue<RecordingSnapshot> snapshots =
                new LinkedBlockingQueue<>();

        @Override
        public void onSnapshot(RecordingSnapshot snapshot) {
            snapshots.add(snapshot);
        }

        private RecordingSnapshot awaitState(RecordingState state) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
            while (System.nanoTime() < deadline) {
                RecordingSnapshot snapshot = snapshots.poll(50L, TimeUnit.MILLISECONDS);
                if (snapshot != null && snapshot.getState() == state) {
                    return snapshot;
                }
            }
            throw new AssertionError("Did not observe state " + state);
        }

        private RecordingSnapshot awaitError(RecordingError.Code code)
                throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
            while (System.nanoTime() < deadline) {
                RecordingSnapshot snapshot = snapshots.poll(50L, TimeUnit.MILLISECONDS);
                if (snapshot != null
                        && snapshot.getError() != null
                        && snapshot.getError().getCode() == code) {
                    return snapshot;
                }
            }
            throw new AssertionError("Did not observe error " + code);
        }
    }
}
