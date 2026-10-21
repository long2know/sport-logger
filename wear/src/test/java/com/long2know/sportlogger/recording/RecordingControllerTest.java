package com.long2know.sportlogger.recording;

import com.long2know.utilities.models.SportActivity;
import com.long2know.utilities.contract.proto.ActivityType;

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
    public void canonicalAuthorityNeverWritesOrExportsLegacyMirror()
            throws Exception {
        InMemoryJournal journal = new InMemoryJournal();
        FakeStore legacyStore = new FakeStore();
        FakeStore canonicalStore = new FakeStore();
        FakeExporter exporter = new FakeExporter();
        SnapshotRecorder snapshots = new SnapshotRecorder();
        RecordingController controller = new RecordingController(
                journal,
                legacyStore,
                canonicalStore,
                exporter,
                RecordingSessionTerminator.NO_OP,
                RecordingAuthority.CANONICAL,
                worker(),
                Runnable::run,
                new MutableClock(2_500L),
                snapshots,
                attempt -> 5L);

        controller.start();
        RecordingSnapshot recording =
                snapshots.awaitState(RecordingState.RECORDING);
        assertEquals(RecordingAuthority.CANONICAL, recording.getAuthority());
        controller.stop();
        snapshots.awaitState(RecordingState.EXPORT_QUEUED);

        assertEquals(0, legacyStore.createCount.get());
        assertEquals(0, legacyStore.writeAttempts.get());
        assertEquals(0, legacyStore.finishAttempts.get());
        assertEquals(0, legacyStore.loadAttempts.get());
        assertEquals(1, canonicalStore.createCount.get());
        assertTrue(canonicalStore.writeAttempts.get() >= 1);
        assertEquals(1, canonicalStore.finishAttempts.get());
        assertEquals(0, canonicalStore.loadAttempts.get());
        assertFalse(exporter.awaitCount(1, 50L));
        assertEquals(RecordingAuthority.CANONICAL, journal.state.getAuthority());
        controller.closeAsync();
    }

    @Test
    public void preCutoverLegacyTerminalStateStillUsesLegacyExporter()
            throws Exception {
        InMemoryJournal journal = new InMemoryJournal();
        journal.state = new PersistentRecording(
                RecordingState.STOPPING,
                1,
                1_000L,
                0L,
                500L,
                1_500L,
                null);
        FakeStore legacyStore = new FakeStore();
        legacyStore.addActivity(1, 1_000L);
        FakeStore canonicalStore = new FakeStore();
        FakeExporter exporter = new FakeExporter();
        SnapshotRecorder snapshots = new SnapshotRecorder();
        RecordingController controller = new RecordingController(
                journal,
                legacyStore,
                canonicalStore,
                exporter,
                RecordingSessionTerminator.NO_OP,
                RecordingAuthority.CANONICAL,
                worker(),
                Runnable::run,
                new MutableClock(2_000L),
                snapshots,
                attempt -> 5L);

        controller.recover();

        assertTrue(exporter.awaitCount(1, 1_000L));
        assertEquals(1, legacyStore.finishAttempts.get());
        assertEquals(1, legacyStore.loadAttempts.get());
        assertEquals(0, canonicalStore.finishAttempts.get());
        assertEquals(
                RecordingAuthority.LEGACY_COMPATIBILITY,
                journal.state.getAuthority());
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
    public void recoveryClearsLegacyPausedLateSampleErrorBeforeResume()
            throws Exception {
        InMemoryJournal journal = new InMemoryJournal();
        journal.state = new PersistentRecording(
                RecordingState.INTERRUPTED,
                1,
                ActivityType.ACTIVITY_TYPE_RUNNING,
                RecordingAuthority.CANONICAL,
                1_000L,
                0L,
                0L,
                0L,
                500L,
                700L,
                0L,
                -1,
                new RecordingError(
                        RecordingError.Code.STORAGE,
                        "Canonical sample persistence failed: "
                                + "Samples can only be appended while recording or ending"));
        FakeStore store = new FakeStore();
        store.addActivity(1, 1_000L);
        SnapshotRecorder snapshots = new SnapshotRecorder();
        RecordingController controller = canonicalController(
                journal,
                store,
                new FakeLapRecorder(),
                worker(),
                new MutableClock(2_000L),
                snapshots);

        controller.recover();
        RecordingSnapshot recovered =
                snapshots.awaitState(RecordingState.INTERRUPTED);
        assertEquals(null, recovered.getError());

        controller.resume();
        RecordingSnapshot resumed =
                snapshots.awaitState(RecordingState.RECORDING);
        assertEquals(null, resumed.getError());
        controller.closeAsync();
    }

    @Test
    public void selectedActivityTypeIsDurableAndAuthoritativeAfterRecovery()
            throws Exception {
        InMemoryJournal journal = new InMemoryJournal();
        FakeStore store = new FakeStore();
        MutableClock clock = new MutableClock(15_000L);
        SnapshotRecorder firstSnapshots = new SnapshotRecorder();
        ScheduledThreadPoolExecutor firstWorker = worker();
        RecordingController first = controller(
                journal,
                store,
                new FakeExporter(),
                firstWorker,
                clock,
                firstSnapshots);

        first.start(ActivityType.ACTIVITY_TYPE_CYCLING);
        RecordingSnapshot started =
                firstSnapshots.awaitState(RecordingState.RECORDING);
        assertEquals(ActivityType.ACTIVITY_TYPE_CYCLING, started.getActivityType());
        assertEquals(
                ActivityType.ACTIVITY_TYPE_CYCLING,
                journal.state.getActivityType());
        first.closeAsync();
        assertTrue(firstWorker.awaitTermination(1, TimeUnit.SECONDS));

        SnapshotRecorder replacementSnapshots = new SnapshotRecorder();
        RecordingController replacement = controller(
                journal,
                store,
                new FakeExporter(),
                worker(),
                clock,
                replacementSnapshots);
        replacement.recover();

        assertEquals(
                ActivityType.ACTIVITY_TYPE_CYCLING,
                replacementSnapshots.awaitState(
                        RecordingState.RECORDING).getActivityType());
        replacement.closeAsync();
    }

    @Test
    public void manualLapUsesAuthoritativeTimingAndSurvivesRecovery()
            throws Exception {
        InMemoryJournal journal = new InMemoryJournal();
        FakeStore store = new FakeStore();
        FakeLapRecorder laps = new FakeLapRecorder();
        MutableClock clock = new MutableClock(18_000L);
        SnapshotRecorder firstSnapshots = new SnapshotRecorder();
        ScheduledThreadPoolExecutor firstWorker = worker();
        RecordingController first = canonicalController(
                journal,
                store,
                laps,
                firstWorker,
                clock,
                firstSnapshots);

        first.start(ActivityType.ACTIVITY_TYPE_HIKING);
        RecordingSnapshot recording =
                firstSnapshots.awaitState(RecordingState.RECORDING);
        clock.advance(2_000L);

        ManualLapResult result =
                first.recordManualLap().get(1, TimeUnit.SECONDS);

        assertTrue(result.isAccepted());
        assertEquals(recording.getActivityId(), laps.lastBoundary.getActivityId());
        assertEquals(20_000L, laps.lastBoundary.getWallTimeUtcMillis());
        assertEquals(2_000L, laps.lastBoundary.getElapsedDurationMillis());
        assertEquals(2_000L, laps.lastBoundary.getActiveDurationMillis());
        assertEquals(2, result.getSnapshot().getCurrentLapIndex());
        assertEquals(
                Long.valueOf(2_000L),
                result.getSnapshot().getLastLapElapsedDurationMillis());
        assertEquals(
                Long.valueOf(2_000L),
                result.getSnapshot().getLastLapActiveDurationMillis());

        first.closeAsync();
        assertTrue(firstWorker.awaitTermination(1, TimeUnit.SECONDS));

        SnapshotRecorder replacementSnapshots = new SnapshotRecorder();
        RecordingController replacement = canonicalController(
                journal,
                store,
                laps,
                worker(),
                clock,
                replacementSnapshots);
        replacement.recover();

        RecordingSnapshot recovered =
                replacementSnapshots.awaitState(RecordingState.RECORDING);
        assertEquals(2, recovered.getCurrentLapIndex());
        assertEquals(
                Long.valueOf(2_000L),
                recovered.getLastLapElapsedDurationMillis());
        assertEquals(
                Long.valueOf(2_000L),
                recovered.getLastLapActiveDurationMillis());
        replacement.closeAsync();
    }

    @Test
    public void manualLapRejectsPausedLegacyAndConcurrentRequests()
            throws Exception {
        InMemoryJournal canonicalJournal = new InMemoryJournal();
        FakeStore canonicalStore = new FakeStore();
        FakeLapRecorder heldLaps = new FakeLapRecorder();
        heldLaps.hold = true;
        SnapshotRecorder canonicalSnapshots = new SnapshotRecorder();
        RecordingController canonical = canonicalController(
                canonicalJournal,
                canonicalStore,
                heldLaps,
                worker(),
                new MutableClock(22_000L),
                canonicalSnapshots);
        canonical.start();
        canonicalSnapshots.awaitState(RecordingState.RECORDING);

        java.util.concurrent.CompletableFuture<ManualLapResult> first =
                canonical.recordManualLap();
        assertTrue(heldLaps.entered.await(1, TimeUnit.SECONDS));
        ManualLapResult busy =
                canonical.recordManualLap().get(1, TimeUnit.SECONDS);
        assertEquals(ManualLapResult.Status.REJECTED_BUSY, busy.getStatus());
        heldLaps.complete(new RecordingLapRecorder.LapState(
                2,
                0L,
                0L));
        assertTrue(first.get(1, TimeUnit.SECONDS).isAccepted());

        canonical.pause();
        canonicalSnapshots.awaitState(RecordingState.PAUSED);
        ManualLapResult paused =
                canonical.recordManualLap().get(1, TimeUnit.SECONDS);
        assertEquals(
                ManualLapResult.Status.REJECTED_NOT_RECORDING,
                paused.getStatus());
        canonical.closeAsync();

        InMemoryJournal legacyJournal = new InMemoryJournal();
        SnapshotRecorder legacySnapshots = new SnapshotRecorder();
        RecordingController legacy = controller(
                legacyJournal,
                new FakeStore(),
                new FakeExporter(),
                worker(),
                new MutableClock(24_000L),
                legacySnapshots);
        legacy.start();
        legacySnapshots.awaitState(RecordingState.RECORDING);

        ManualLapResult legacyResult =
                legacy.recordManualLap().get(1, TimeUnit.SECONDS);

        assertEquals(
                ManualLapResult.Status.REJECTED_LEGACY_AUTHORITY,
                legacyResult.getStatus());
        legacy.closeAsync();
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
        journal.failState = RecordingState.STOPPING;
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

    @Test
    public void elapsedDurationIgnoresWallClockChangesAndEndsFromMonotonicTime()
            throws Exception {
        InMemoryJournal journal = new InMemoryJournal();
        FakeStore store = new FakeStore();
        FakeExporter exporter = new FakeExporter();
        MutableClock clock = new MutableClock(100_000L);
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
        clock.advanceElapsed(5_000L);
        clock.setWall(10_000L);
        controller.pause();
        assertEquals(
                5_000L,
                snapshots.awaitState(RecordingState.PAUSED).getDurationMillis());

        clock.advanceElapsed(3_000L);
        controller.resume();
        snapshots.awaitState(RecordingState.RECORDING);
        clock.advanceElapsed(2_000L);
        clock.setWall(9_000_000L);
        controller.stop();
        RecordingSnapshot pending =
                snapshots.awaitState(RecordingState.EXPORT_PENDING);

        assertEquals(7_000L, pending.getDurationMillis());
        assertEquals(10_000L, pending.getElapsedDurationMillis());
        assertEquals(
                110_000L,
                store.activities.get(1).EndTimeUTC.getTime());
        assertTrue(exporter.awaitCount(1, 1_000L));
        exporter.queue(0);
        controller.closeAsync();
    }

    @Test
    public void sameBootProcessRecoveryUsesElapsedRealtimeDespiteWallClockJump()
            throws Exception {
        InMemoryJournal journal = new InMemoryJournal();
        journal.state = new PersistentRecording(
                RecordingState.RECORDING,
                1,
                1_000L,
                50_000L,
                10_000L,
                2_000L,
                0L,
                7,
                null);
        FakeStore store = new FakeStore();
        store.addActivity(1, 1_000L);
        MutableClock clock = new MutableClock(900_000L);
        clock.setElapsed(15_000L);
        clock.setBoot(7);
        SnapshotRecorder snapshots = new SnapshotRecorder();
        RecordingController controller = controller(
                journal,
                store,
                new FakeExporter(),
                worker(),
                clock,
                snapshots);

        controller.recover();

        assertEquals(
                7_000L,
                snapshots.awaitState(RecordingState.RECORDING).getDurationMillis());
        controller.closeAsync();
    }

    @Test
    public void deviceRestartBecomesDurableInterruptedStateWithoutResettingDuration()
            throws Exception {
        InMemoryJournal journal = new InMemoryJournal();
        journal.state = new PersistentRecording(
                RecordingState.RECORDING,
                1,
                1_000L,
                50_000L,
                10_000L,
                4_000L,
                0L,
                7,
                null);
        MutableClock clock = new MutableClock(2_000L);
        clock.setElapsed(500L);
        clock.setBoot(8);
        SnapshotRecorder snapshots = new SnapshotRecorder();
        RecordingController controller = controller(
                journal,
                new FakeStore(),
                new FakeExporter(),
                worker(),
                clock,
                snapshots);

        controller.recover();

        RecordingSnapshot interrupted =
                snapshots.awaitState(RecordingState.INTERRUPTED);
        assertEquals(4_000L, interrupted.getDurationMillis());
        assertEquals(
                RecordingError.Code.BOOT_INTERRUPTION,
                interrupted.getError().getCode());
        assertEquals(RecordingState.INTERRUPTED, journal.state.getState());
        controller.closeAsync();
    }

    @Test
    public void stopDoesNotFinishOrExportUntilAcquisitionFinalDataCompletes()
            throws Exception {
        InMemoryJournal journal = new InMemoryJournal();
        FakeStore store = new FakeStore();
        FakeExporter exporter = new FakeExporter();
        FakeTerminator terminator = new FakeTerminator();
        SnapshotRecorder snapshots = new SnapshotRecorder();
        RecordingController controller = controller(
                journal,
                store,
                exporter,
                terminator,
                worker(),
                new MutableClock(60_000L),
                snapshots);

        controller.start();
        snapshots.awaitState(RecordingState.RECORDING);
        controller.stop();
        snapshots.awaitState(RecordingState.STOPPING);

        assertTrue(terminator.entered.await(1, TimeUnit.SECONDS));
        assertEquals(0, store.finishAttempts.get());
        assertFalse(exporter.awaitCount(1, 50L));

        terminator.complete();
        snapshots.awaitState(RecordingState.EXPORT_PENDING);
        assertEquals(1, store.finishAttempts.get());
        assertTrue(store.writeAttempts.get() >= 1);
        assertTrue(exporter.awaitCount(1, 1_000L));
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

    private RecordingController canonicalController(
            InMemoryJournal journal,
            FakeStore store,
            FakeLapRecorder lapRecorder,
            ScheduledThreadPoolExecutor worker,
            MutableClock clock,
            SnapshotRecorder snapshots) {
        return new RecordingController(
                journal,
                store,
                store,
                new FakeExporter(),
                RecordingSessionTerminator.NO_OP,
                lapRecorder,
                RecordingAuthority.CANONICAL,
                worker,
                Runnable::run,
                clock,
                snapshots,
                attempt -> 5L);
    }

    private RecordingController controller(
            InMemoryJournal journal,
            FakeStore store,
            FakeExporter exporter,
            RecordingSessionTerminator terminator,
            ScheduledThreadPoolExecutor worker,
            MutableClock clock,
            SnapshotRecorder snapshots) throws Exception {
        return new RecordingController(
                journal,
                store,
                exporter,
                terminator,
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
        private final AtomicLong wall;
        private final AtomicLong elapsed;
        private final AtomicInteger boot = new AtomicInteger(1);

        private MutableClock(long now) {
            this.wall = new AtomicLong(now);
            this.elapsed = new AtomicLong(now);
        }

        @Override
        public long currentTimeMillis() {
            return wall.get();
        }

        @Override
        public long elapsedRealtimeMillis() {
            return elapsed.get();
        }

        @Override
        public int bootCount() {
            return boot.get();
        }

        private void advance(long millis) {
            wall.addAndGet(millis);
            elapsed.addAndGet(millis);
        }

        private void advanceElapsed(long millis) {
            elapsed.addAndGet(millis);
        }

        private void setWall(long millis) {
            wall.set(millis);
        }

        private void setElapsed(long millis) {
            elapsed.set(millis);
        }

        private void setBoot(int value) {
            boot.set(value);
        }
    }

    private static final class InMemoryJournal implements RecordingJournal {
        private final AtomicLong currentLease = new AtomicLong();
        private final AtomicInteger failWrites = new AtomicInteger();
        private volatile PersistentRecording state = PersistentRecording.idle();
        private volatile RuntimeException readFailure;
        private volatile RecordingState failState;

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
            if (recording.getState() == failState) {
                failState = null;
                throw new IllegalStateException("journal unavailable");
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
        private final AtomicInteger writeAttempts = new AtomicInteger();
        private final AtomicInteger loadAttempts = new AtomicInteger();
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
            writeAttempts.incrementAndGet();
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
            loadAttempts.incrementAndGet();
            SportActivity activity = activities.get(activityId);
            if (activity == null) {
                throw new IllegalStateException("missing activity");
            }
            return activity;
        }

        private void addActivity(int activityId, long startedAtMillis) {
            SportActivity activity = new SportActivity();
            activity.Id = activityId;
            activity.StartTimeUTC = new Date(startedAtMillis);
            activities.put(activityId, activity);
            nextId.set(Math.max(nextId.get(), activityId));
        }
    }

    private static final class FakeTerminator
            implements RecordingSessionTerminator {
        private final CountDownLatch entered = new CountDownLatch(1);
        private volatile Callback callback;

        @Override
        public void endSession(
                int activityId,
                boolean preserveFinalData,
                RecordingSnapshot finalSnapshot,
                Callback callback) {
            this.callback = callback;
            entered.countDown();
        }

        private void complete() {
            callback.onComplete();
        }
    }

    private static final class FakeLapRecorder implements RecordingLapRecorder {
        private final CountDownLatch entered = new CountDownLatch(1);
        private volatile boolean hold;
        private volatile Boundary lastBoundary;
        private volatile Callback pending;

        @Override
        public void recordManualLap(Boundary boundary, Callback callback) {
            lastBoundary = boundary;
            entered.countDown();
            if (hold) {
                pending = callback;
                return;
            }
            callback.onPersisted(new LapState(
                    2,
                    boundary.getElapsedDurationMillis(),
                    boundary.getActiveDurationMillis()));
        }

        @Override
        public void recover(int activityId, Callback callback) {
            callback.onPersisted(LapState.initial());
        }

        private void complete(LapState state) {
            Callback callback = pending;
            pending = null;
            callback.onPersisted(state);
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
