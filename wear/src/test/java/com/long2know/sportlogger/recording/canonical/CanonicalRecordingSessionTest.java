package com.long2know.sportlogger.recording.canonical;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.long2know.sportlogger.acquisition.ExerciseMetric;
import com.long2know.sportlogger.acquisition.MeasurementCapability;
import com.long2know.sportlogger.acquisition.MeasurementProvenance;
import com.long2know.sportlogger.acquisition.MeasurementSample;
import com.long2know.sportlogger.persistence.watch.WatchActivityRepository;
import com.long2know.sportlogger.persistence.watch.WatchDatabase;
import com.long2know.sportlogger.recording.CompletedRecordingSnapshot;
import com.long2know.sportlogger.recording.RecordingLapRecorder;
import com.long2know.sportlogger.recording.RecordingSessionTerminator;
import com.long2know.sportlogger.recording.RecordingSnapshot;
import com.long2know.sportlogger.recording.RecordingState;
import com.long2know.utilities.contract.CanonicalPayload;
import com.long2know.utilities.contract.CanonicalTransfers;
import com.long2know.utilities.contract.proto.AckResult;
import com.long2know.utilities.contract.proto.ActivityTransferFile;
import com.long2know.utilities.contract.proto.ActivityType;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class CanonicalRecordingSessionTest {
    private Context context;
    private WatchDatabase database;
    private WatchActivityRepository repository;
    private CanonicalSessionJournal journal;
    private CanonicalRecordingSession session;
    private AtomicReference<Throwable> fatal;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(
                        "sport_logger_canonical_recording",
                        Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        database = Room.inMemoryDatabaseBuilder(context, WatchDatabase.class)
                .allowMainThreadQueries()
                .build();
        repository = new WatchActivityRepository(database, Runnable::run);
        journal = new CanonicalSessionJournal(context);
        fatal = new AtomicReference<>();
        session = new CanonicalRecordingSession(
                context,
                repository,
                journal,
                (message, error) -> fatal.set(error));
        session.updateCapabilities(Collections.singletonMap(
                ExerciseMetric.HEART_RATE_BPM,
                supported(ExerciseMetric.HEART_RATE_BPM)));
    }

    @After
    public void tearDown() {
        session.close();
    }

    @Test
    public void startSamplesReservationFinalizationAndOutboxAreDurable()
            throws Exception {
        RecordingSnapshot recording = snapshot(
                ActivityType.ACTIVITY_TYPE_HIKING,
                RecordingState.RECORDING,
                0L,
                0L,
                50_000L,
                50_000L);
        session.onRecordingSnapshot(recording);
        CanonicalSessionJournal.State state = journal.read();
        assertTrue(state.leaseToken != 0L);

        session.onAcceptedSample(heartRate(50_500_000_000L, 145D));
        RecordingSnapshot paused = snapshot(
                ActivityType.ACTIVITY_TYPE_HIKING,
                RecordingState.PAUSED,
                500L,
                700L,
                0L,
                50_700L);
        session.onRecordingSnapshot(paused);
        RecordingSnapshot resumed = snapshot(
                ActivityType.ACTIVITY_TYPE_HIKING,
                RecordingState.RECORDING,
                500L,
                900L,
                50_900L,
                50_900L);
        session.onRecordingSnapshot(resumed);
        RecordingSnapshot stopping = snapshot(
                ActivityType.ACTIVITY_TYPE_HIKING,
                RecordingState.STOPPING,
                900L,
                1_200L,
                0L,
                0L);

        awaitSuccess(callback -> session.reserveCompletion(stopping, callback));
        session.onAcceptedSample(heartRate(51_200_000_000L, 151D));
        awaitSuccess(callback -> session.finishCompletion(stopping, callback));

        WatchActivityRepository.SessionSnapshot completed =
                repository.session(state.sessionId).join();
        assertEquals(
                WatchActivityRepository.LifecycleState.COMPLETED,
                completed.getState());
        assertEquals(2L, completed.getSampleCount());
        assertEquals(1L, completed.getWatchRevision());
        assertEquals(
                WatchActivityRepository.OUTBOX_READY,
                repository.readyOutbox(10).join().get(0).getState());
        ActivityTransferFile transfer = ActivityTransferFile.parseFrom(
                repository.readyOutbox(10).join().get(0).getPayload());
        assertEquals(
                ActivityType.ACTIVITY_TYPE_HIKING,
                CanonicalPayload.parse(
                        transfer.getActivityFile().toByteArray())
                        .getActivity()
                        .getActivityType());
        assertTrue(journal.read().terminal);
        assertEquals(
                ActivityType.ACTIVITY_TYPE_HIKING,
                journal.read().activityType);
        assertNull(fatal.get());

        session.onRecordingSnapshot(new RecordingSnapshot(
                RecordingState.EXPORT_PENDING,
                1,
                900L,
                null));
        assertNull(journal.read());
    }

    @Test
    public void reservedCompletionRecoversWithStableIdentity() throws Exception {
        RecordingSnapshot recording = snapshot(
                RecordingState.RECORDING,
                0L,
                0L,
                70_000L,
                70_000L);
        session.onRecordingSnapshot(recording);
        session.onAcceptedSample(heartRate(70_200_000_000L, 130D));
        RecordingSnapshot stopping = snapshot(
                RecordingState.STOPPING,
                200L,
                200L,
                0L,
                0L);
        awaitSuccess(callback -> session.reserveCompletion(stopping, callback));
        CanonicalSessionJournal.State reserved = journal.read();

        CanonicalRecordingSession replacement = new CanonicalRecordingSession(
                context,
                repository,
                journal,
                (message, error) -> fatal.set(error));
        replacement.updateCapabilities(Collections.singletonMap(
                ExerciseMetric.HEART_RATE_BPM,
                supported(ExerciseMetric.HEART_RATE_BPM)));
        replacement.onRecordingSnapshot(stopping);
        awaitSuccess(callback -> replacement.reserveCompletion(stopping, callback));
        CanonicalSessionJournal.State recovered = journal.read();

        assertEquals(
                reserved.completionTransferId,
                recovered.completionTransferId);
        assertEquals(
                reserved.completionRevision,
                recovered.completionRevision);
        replacement.close();
    }

    @Test
    public void manualLapRecoversAndFinalizationClosesTheLastPartialLap()
            throws Exception {
        RecordingSnapshot recording = snapshot(
                RecordingState.RECORDING,
                0L,
                0L,
                80_000L,
                80_000L);
        session.onRecordingSnapshot(recording);
        CanonicalSessionJournal.State state = journal.read();

        RecordingLapRecorder.LapState recorded = awaitLap(callback ->
                session.recordManualLap(
                        new RecordingLapRecorder.Boundary(
                                recording.getActivityId(),
                                1_500L,
                                500L,
                                400L),
                        callback));
        RecordingLapRecorder.LapState recovered = awaitLap(callback ->
                session.recover(recording.getActivityId(), callback));

        assertEquals(2, recorded.getCurrentLapIndex());
        assertEquals(Long.valueOf(500L),
                recovered.getLastLapElapsedDurationMillis());
        assertEquals(Long.valueOf(400L),
                recovered.getLastLapActiveDurationMillis());

        RecordingSnapshot stopping = snapshot(
                RecordingState.STOPPING,
                700L,
                1_000L,
                0L,
                0L);
        awaitSuccess(callback -> session.reserveCompletion(stopping, callback));
        awaitSuccess(callback -> session.finishCompletion(stopping, callback));

        com.long2know.utilities.contract.proto.ActivityRecord activity =
                repository.latestCompleted().join().getActivity();
        assertEquals(state.sessionId, activity.getSessionId());
        assertEquals(2, activity.getLapsCount());
        assertEquals(500L, activity.getLaps(0).getElapsedDurationMs());
        assertEquals(400L, activity.getLaps(0).getActiveDurationMs());
        assertEquals(500L, activity.getLaps(1).getElapsedDurationMs());
        assertEquals(300L, activity.getLaps(1).getActiveDurationMs());
    }

    @Test
    public void discardRemovesDurableManualLaps() throws Exception {
        RecordingSnapshot recording = snapshot(
                RecordingState.RECORDING,
                0L,
                0L,
                90_000L,
                90_000L);
        session.onRecordingSnapshot(recording);
        CanonicalSessionJournal.State state = journal.read();
        awaitLap(callback -> session.recordManualLap(
                new RecordingLapRecorder.Boundary(
                        recording.getActivityId(),
                        1_500L,
                        500L,
                        450L),
                callback));
        assertEquals(2, repository.laps(state.sessionId).join().size());

        awaitSuccess(callback -> session.discard(
                snapshot(
                        RecordingState.DISCARDING,
                        450L,
                        500L,
                        0L,
                        0L),
                callback));

        assertTrue(repository.laps(state.sessionId).join().isEmpty());
        assertEquals(
                WatchActivityRepository.LifecycleState.DISCARDED,
                repository.session(state.sessionId).join().getState());
    }

    @Test
    public void completedPresentationObservesAndQueriesCommittedOutbox()
            throws Exception {
        CountDownLatch completedChanged = new CountDownLatch(1);
        AtomicReference<CompletedRecordingSnapshot> observed =
                new AtomicReference<>();
        session.observeLatestCompleted(
                Runnable::run,
                new CanonicalRecordingSession.CompletedListener() {
                    @Override
                    public void onChanged(CompletedRecordingSnapshot snapshot) {
                        if (snapshot != null) {
                            observed.set(snapshot);
                            completedChanged.countDown();
                        }
                    }

                    @Override
                    public void onFailure(Throwable error) {
                        fatal.set(error);
                        completedChanged.countDown();
                    }
                });
        RecordingSnapshot recording = snapshot(
                ActivityType.ACTIVITY_TYPE_CYCLING,
                RecordingState.RECORDING,
                0L,
                0L,
                100_000L,
                100_000L);
        session.onRecordingSnapshot(recording);
        RecordingSnapshot stopping = snapshot(
                ActivityType.ACTIVITY_TYPE_CYCLING,
                RecordingState.STOPPING,
                800L,
                1_000L,
                0L,
                0L);
        awaitSuccess(callback -> session.reserveCompletion(stopping, callback));
        awaitSuccess(callback -> session.finishCompletion(stopping, callback));

        assertTrue(completedChanged.await(2, TimeUnit.SECONDS));
        assertNull(fatal.get());
        CompletedRecordingSnapshot ready = observed.get();
        assertEquals(
                ActivityType.ACTIVITY_TYPE_CYCLING,
                ready.getActivity().getActivityType());
        assertEquals(
                WatchActivityRepository.OUTBOX_READY,
                ready.getOutboxState());

        WatchActivityRepository.OutboxItem outbox =
                repository.outbox(ready.getTransferId()).join();
        ActivityTransferFile transfer =
                CanonicalTransfers.parseActivityTransfer(
                        outbox.getPayload()).getTransfer();
        repository.acceptCommitAck(
                        CanonicalTransfers.commitAck(
                                        transfer,
                                        transfer.getSessionRevision(),
                                        AckResult.ACK_RESULT_ACCEPTED,
                                        null,
                                        null)
                                .toByteArray(),
                        101_500L)
                .join();

        List<CompletedRecordingSnapshot> recent =
                session.recentCompleted(10).get(2, TimeUnit.SECONDS);
        assertEquals(1, recent.size());
        assertEquals(
                WatchActivityRepository.OUTBOX_COMMITTED,
                recent.get(0).getOutboxState());
        assertEquals(Long.valueOf(101_500L),
                recent.get(0).getCommittedAtUtcMillis());
    }

    @Test
    public void productionObserverTracksImmediateExternalSyncThroughCommit()
            throws Exception {
        context.deleteDatabase(WatchDatabase.DATABASE_NAME);
        AtomicReference<Throwable> productionFatal = new AtomicReference<>();
        BlockingQueue<String> observedStates = new LinkedBlockingQueue<>();
        CanonicalRecordingSession productionSession = null;
        WatchActivityRepository syncRepository = null;
        try {
            WatchActivityRepository serviceRepository =
                    WatchActivityRepository.open(context);
            productionSession = new CanonicalRecordingSession(
                    context,
                    serviceRepository,
                    new CanonicalSessionJournal(context),
                    (message, error) -> productionFatal.set(error));
            productionSession.observeLatestCompleted(
                    Runnable::run,
                    new CanonicalRecordingSession.CompletedListener() {
                        @Override
                        public void onChanged(CompletedRecordingSnapshot snapshot) {
                            if (snapshot != null) {
                                observedStates.offer(snapshot.getOutboxState());
                            }
                        }

                        @Override
                        public void onFailure(Throwable error) {
                            productionFatal.set(error);
                        }
                    });

            RecordingSnapshot recording = snapshot(
                    ActivityType.ACTIVITY_TYPE_CYCLING,
                    RecordingState.RECORDING,
                    0L,
                    0L,
                    100_000L,
                    100_000L);
            productionSession.onRecordingSnapshot(recording);
            RecordingSnapshot stopping = snapshot(
                    ActivityType.ACTIVITY_TYPE_CYCLING,
                    RecordingState.STOPPING,
                    800L,
                    1_000L,
                    0L,
                    0L);
            CanonicalRecordingSession completingSession = productionSession;
            awaitSuccess(callback ->
                    completingSession.reserveCompletion(stopping, callback));
            awaitSuccess(callback ->
                    completingSession.finishCompletion(stopping, callback));
            awaitObservedState(
                    observedStates,
                    WatchActivityRepository.OUTBOX_READY);

            syncRepository = WatchActivityRepository.open(context);
            assertNotSame(serviceRepository, syncRepository);
            List<WatchActivityRepository.OutboxItem> reclaimed =
                    syncRepository.reclaimPendingOutbox(10, 102_000L)
                            .get(2, TimeUnit.SECONDS);
            assertEquals(1, reclaimed.size());
            WatchActivityRepository.OutboxItem ready = reclaimed.get(0);

            syncRepository.transitionOutbox(
                            ready.getTransferId(),
                            WatchActivityRepository.OUTBOX_READY,
                            WatchActivityRepository.OUTBOX_SENDING,
                            102_100L,
                            null,
                            null)
                    .get(2, TimeUnit.SECONDS);
            awaitObservedState(
                    observedStates,
                    WatchActivityRepository.OUTBOX_SENDING);
            syncRepository.transitionOutbox(
                            ready.getTransferId(),
                            WatchActivityRepository.OUTBOX_SENDING,
                            WatchActivityRepository.OUTBOX_AWAITING_COMMIT_ACK,
                            null,
                            null,
                            null)
                    .get(2, TimeUnit.SECONDS);
            awaitObservedState(
                    observedStates,
                    WatchActivityRepository.OUTBOX_AWAITING_COMMIT_ACK);

            ActivityTransferFile transfer =
                    CanonicalTransfers.parseActivityTransfer(
                            ready.getPayload()).getTransfer();
            syncRepository.acceptCommitAck(
                            CanonicalTransfers.commitAck(
                                            transfer,
                                            transfer.getSessionRevision(),
                                            AckResult.ACK_RESULT_ACCEPTED,
                                            null,
                                            null)
                                    .toByteArray(),
                            102_200L)
                    .get(2, TimeUnit.SECONDS);
            awaitObservedState(
                    observedStates,
                    WatchActivityRepository.OUTBOX_COMMITTED);

            syncRepository.close();
            syncRepository = null;
            CompletedRecordingSnapshot committed =
                    productionSession.recentCompleted(1)
                            .get(2, TimeUnit.SECONDS)
                            .get(0);
            assertEquals(
                    WatchActivityRepository.OUTBOX_COMMITTED,
                    committed.getOutboxState());
            assertArrayEquals(
                    ready.getPayload(),
                    serviceRepository.outbox(ready.getTransferId())
                            .get(2, TimeUnit.SECONDS)
                            .getPayload());
            assertNull(productionFatal.get());

            productionSession.close();
            productionSession = null;
            try (WatchActivityRepository reopened =
                         WatchActivityRepository.open(context)) {
                WatchActivityRepository.OutboxItem durable =
                        reopened.outbox(ready.getTransferId())
                                .get(2, TimeUnit.SECONDS);
                assertEquals(
                        WatchActivityRepository.OUTBOX_COMMITTED,
                        durable.getState());
                assertArrayEquals(ready.getPayload(), durable.getPayload());
            }
        } finally {
            if (syncRepository != null) {
                syncRepository.close();
            }
            if (productionSession != null) {
                productionSession.close();
            }
            context.deleteDatabase(WatchDatabase.DATABASE_NAME);
            context.getSharedPreferences(
                            "sport_logger_canonical_recording",
                            Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit();
        }
    }

    private static RecordingSnapshot snapshot(
            RecordingState state,
            long activeDuration,
            long elapsedDuration,
            long activeSince,
            long elapsedSince) {
        return snapshot(
                ActivityType.ACTIVITY_TYPE_RUNNING,
                state,
                activeDuration,
                elapsedDuration,
                activeSince,
                elapsedSince);
    }

    private static RecordingSnapshot snapshot(
            ActivityType activityType,
            RecordingState state,
            long activeDuration,
            long elapsedDuration,
            long activeSince,
            long elapsedSince) {
        return new RecordingSnapshot(
                state,
                1,
                activityType,
                1_000L,
                activeDuration,
                elapsedDuration,
                activeSince,
                elapsedSince,
                3,
                null);
    }

    private static MeasurementSample<Double> heartRate(
            long sequenceNanos,
            double value) {
        return new MeasurementSample<>(
                ExerciseMetric.HEART_RATE_BPM,
                value,
                sequenceNanos,
                MeasurementProvenance.HEALTH_SERVICES,
                MeasurementSample.Quality.HIGH);
    }

    private static MeasurementCapability supported(ExerciseMetric metric) {
        return new MeasurementCapability(
                metric,
                MeasurementCapability.Support.SUPPORTED,
                MeasurementCapability.PermissionState.GRANTED,
                MeasurementCapability.RawHardwareState.UNKNOWN,
                MeasurementCapability.VendorState.UNAVAILABLE,
                MeasurementCapability.LiveAvailability.AVAILABLE,
                Collections.singleton(MeasurementProvenance.HEALTH_SERVICES));
    }

    private static void awaitSuccess(AsyncOperation operation) throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        operation.run(new RecordingSessionTerminator.Callback() {
            @Override
            public void onComplete() {
                complete.countDown();
            }

            @Override
            public void onFailure(Throwable failure) {
                error.set(failure);
                complete.countDown();
            }
        });
        assertTrue(complete.await(2, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private static RecordingLapRecorder.LapState awaitLap(
            LapOperation operation) throws Exception {
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<RecordingLapRecorder.LapState> state =
                new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        operation.run(new RecordingLapRecorder.Callback() {
            @Override
            public void onPersisted(RecordingLapRecorder.LapState persisted) {
                state.set(persisted);
                complete.countDown();
            }

            @Override
            public void onFailure(Throwable failure) {
                error.set(failure);
                complete.countDown();
            }
        });
        assertTrue(complete.await(2, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return state.get();
    }

    private static void awaitObservedState(
            BlockingQueue<String> observedStates,
            String expectedState) throws Exception {
        long deadlineNanos = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                throw new AssertionError(
                        "Timed out waiting for observed state " + expectedState);
            }
            String observed = observedStates.poll(
                    remainingNanos,
                    TimeUnit.NANOSECONDS);
            if (observed == null) {
                throw new AssertionError(
                        "Timed out waiting for observed state " + expectedState);
            }
            if (expectedState.equals(observed)) {
                return;
            }
        }
    }

    private interface AsyncOperation {
        void run(RecordingSessionTerminator.Callback callback);
    }

    private interface LapOperation {
        void run(RecordingLapRecorder.Callback callback);
    }
}
