package com.long2know.sportlogger.persistence.watch;

import android.content.Context;

import androidx.room.Room;
import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.long2know.utilities.contract.proto.ActivityRecord;
import com.long2know.utilities.contract.proto.ActivityType;
import com.long2know.utilities.contract.proto.SourceKind;
import com.long2know.utilities.contract.proto.SourceMetadata;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class WatchDatabaseInstrumentedTest {
    private static final String DATABASE = "watch-canonical-instrumented.db";
    private static final String SESSION_ID = "22e12ef5-c99d-5e04-bbb6-007737ca6216";
    private static final String TRANSFER_ID = "8cb7fb24-dfe6-4ba6-85d0-411c87d87cc8";

    private Context context;
    private WatchDatabase database;

    @Rule
    public final MigrationTestHelper migrationHelper =
            new MigrationTestHelper(
                    InstrumentationRegistry.getInstrumentation(),
                    WatchDatabase.class);

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.deleteDatabase(DATABASE);
        database = Room.databaseBuilder(context, WatchDatabase.class, DATABASE)
                .allowMainThreadQueries()
                .build();
    }

    @After
    public void tearDown() {
        if (database != null && database.isOpen()) {
            database.close();
        }
        context.deleteDatabase(DATABASE);
    }

    @Test
    public void versionTwoPersistsCompletionLapsAndOutboxInOneRepositoryCall() {
        WatchActivityRepository repository =
                new WatchActivityRepository(database, Runnable::run);
        repository.startSession(
                        WatchActivityRepository.StartRequest
                                .builder(SESSION_ID, 7L, 1_000L)
                                .activityType(ActivityType.ACTIVITY_TYPE_RUNNING)
                                .build())
                .join();
        repository.finishSession(
                        SESSION_ID,
                        7L,
                        ActivityRecord.newBuilder()
                                .setSessionId(SESSION_ID)
                                .setWatchRevision(1L)
                                .setActivityType(ActivityType.ACTIVITY_TYPE_RUNNING)
                                .setStartTimeUtcMs(1_000L)
                                .setEndTimeUtcMs(2_000L)
                                .setElapsedDurationMs(1_000L)
                                .setSource(SourceMetadata.newBuilder()
                                        .setKind(SourceKind.SOURCE_KIND_RAW_PLATFORM)
                                        .setAppContractVersion(1))
                                .build(),
                        TRANSFER_ID,
                        2_000L)
                .join();

        assertEquals(
                WatchActivityRepository.LifecycleState.COMPLETED,
                repository.session(SESSION_ID).join().getState());
        assertEquals(1, repository.laps(SESSION_ID).join().size());
        assertEquals(1, repository.readyOutbox(10).join().size());
    }

    @Test
    public void migratesVersionOneToTwoAndPreservesSessionWhileAddingLaps()
            throws Exception {
        database.close();
        database = null;
        context.deleteDatabase(DATABASE);
        SupportSQLiteDatabase versionOne =
                migrationHelper.createDatabase(DATABASE, 1);
        versionOne.execSQL(
                "INSERT INTO watch_sessions("
                        + "session_id, watch_revision, state, activity_type, "
                        + "started_at_utc_ms, accumulated_active_ms, lease_token, "
                        + "sample_count, route_point_count, heart_rate_sample_count, "
                        + "created_at_utc_ms, updated_at_utc_ms"
                        + ") VALUES(?, 0, 'RECORDING', 1, 1000, 0, 7, 0, 0, 0, 1000, 1000)",
                new Object[]{SESSION_ID});
        versionOne.close();

        SupportSQLiteDatabase migrated =
                migrationHelper.runMigrationsAndValidate(
                        DATABASE,
                        2,
                        true,
                        WatchDatabase.MIGRATION_1_2);

        try (android.database.Cursor session = migrated.query(
                "SELECT activity_type FROM watch_sessions WHERE session_id = ?",
                new Object[]{SESSION_ID})) {
            assertTrue(session.moveToFirst());
            assertEquals(1, session.getInt(0));
        }
        try (android.database.Cursor table = migrated.query(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'watch_laps'")) {
            assertTrue(table.moveToFirst());
        }
    }
}
