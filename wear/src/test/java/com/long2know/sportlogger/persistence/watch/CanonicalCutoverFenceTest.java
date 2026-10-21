package com.long2know.sportlogger.persistence.watch;

import android.content.Context;

import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;

import com.long2know.sportlogger.recording.CanonicalAuthorityRecordingStore;
import com.long2know.sportlogger.recording.RecordingAuthority;
import com.long2know.sportlogger.recording.RecordingSessionTerminator;
import com.long2know.sportlogger.recording.RecordingSnapshot;
import com.long2know.sportlogger.recording.RecordingState;
import com.long2know.sportlogger.recording.canonical.CanonicalRecordingSession;
import com.long2know.utilities.contract.CanonicalIds;
import com.long2know.utilities.contract.CanonicalTransfers;
import com.long2know.utilities.contract.proto.TombstoneOrigin;
import com.long2know.utilities.contract.proto.TombstoneRecord;
import com.long2know.utilities.data_access.SqlLogger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class CanonicalCutoverFenceTest {
    private Context context;
    private CanonicalRecordingSession recordingSession;
    private WatchActivityRepository repository;
    private WatchDatabase database;
    private AtomicReference<Throwable> fatal;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(WatchDatabase.DATABASE_NAME);
        context.deleteDatabase(SqlLogger.DATABASE_NAME);
        context.getSharedPreferences(
                        "sport_logger_recording_ids",
                        Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        context.getSharedPreferences(
                        "sport_logger_canonical_recording",
                        Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        fatal = new AtomicReference<>();
        recordingSession = new CanonicalRecordingSession(
                context,
                (message, error) -> fatal.set(error));
        repository = WatchActivityRepository.open(context);
        database = WatchDatabase.openIndependent(context);
    }

    @After
    public void tearDown() {
        recordingSession.close();
        repository.close();
        database.close();
    }

    @Test
    public void canonicalCompletionAndTombstoneCannotReenterThroughLegacyMigration()
            throws Exception {
        CanonicalAuthorityRecordingStore recordingStore =
                new CanonicalAuthorityRecordingStore(context);
        int activityId = recordingStore.createActivity(1_000L);
        RecordingSnapshot recording = snapshot(
                RecordingState.RECORDING,
                activityId,
                0L,
                0L,
                50_000L,
                50_000L);
        recordingSession.onRecordingSnapshot(recording);
        RecordingSnapshot stopping = snapshot(
                RecordingState.STOPPING,
                activityId,
                900L,
                1_200L,
                0L,
                0L);
        awaitSuccess(callback ->
                recordingSession.reserveCompletion(stopping, callback));
        awaitSuccess(callback ->
                recordingSession.finishCompletion(stopping, callback));
        String sessionId = repository.readyOutbox(10)
                .join()
                .get(0)
                .getSessionId();

        assertFalse(context.getDatabasePath(SqlLogger.DATABASE_NAME).exists());
        runLegacyMigrationIfPresent();
        runLegacyMigrationIfPresent();
        assertEquals(1L, count("watch_sessions"));
        assertEquals(1L, count("watch_activity_outbox"));

        TombstoneRecord tombstone = CanonicalTransfers.tombstone(
                CanonicalIds.newTransferId(),
                sessionId,
                1L,
                1L,
                TombstoneOrigin.TOMBSTONE_ORIGIN_PHONE,
                3_000L);
        repository.acceptTombstoneTransfer(
                        tombstone.toByteArray(),
                        3_000L)
                .join();
        runLegacyMigrationIfPresent();
        runLegacyMigrationIfPresent();

        WatchActivityRepository.OutboxItem outbox =
                repository.outboxForSessionRevision(sessionId, 1L).join();
        assertNotNull(outbox);
        assertEquals("TOMBSTONED", outbox.getState());
        assertEquals(1L, count("watch_sessions"));
        assertEquals(1L, count("watch_activity_outbox"));
        assertFalse(context.getDatabasePath(SqlLogger.DATABASE_NAME).exists());
        assertFalse(fatal.get() != null);
    }

    private long count(String table) {
        SupportSQLiteDatabase sqlite = database.getOpenHelper().getReadableDatabase();
        try (android.database.Cursor cursor =
                     sqlite.query("SELECT COUNT(*) FROM " + table)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getLong(0);
        }
    }

    private void runLegacyMigrationIfPresent() throws Exception {
        File legacy = LegacyWatchMigrator.defaultLegacyDatabase(context);
        if (legacy.isFile()) {
            new LegacyWatchMigrator(repository).migrateDefault(
                    context,
                    System.currentTimeMillis());
        }
    }

    private static RecordingSnapshot snapshot(
            RecordingState state,
            int activityId,
            long activeDuration,
            long elapsedDuration,
            long activeSince,
            long elapsedSince) {
        return new RecordingSnapshot(
                state,
                activityId,
                RecordingAuthority.CANONICAL,
                1_000L,
                activeDuration,
                elapsedDuration,
                activeSince,
                elapsedSince,
                3,
                null);
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
