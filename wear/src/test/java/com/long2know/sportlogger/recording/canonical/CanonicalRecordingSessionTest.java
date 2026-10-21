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
import com.long2know.sportlogger.recording.RecordingSessionTerminator;
import com.long2know.sportlogger.recording.RecordingSnapshot;
import com.long2know.sportlogger.recording.RecordingState;
import com.long2know.utilities.contract.CanonicalPayload;
import com.long2know.utilities.contract.proto.ActivityTransferFile;
import com.long2know.utilities.contract.proto.ActivityType;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
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

    private interface AsyncOperation {
        void run(RecordingSessionTerminator.Callback callback);
    }
}
