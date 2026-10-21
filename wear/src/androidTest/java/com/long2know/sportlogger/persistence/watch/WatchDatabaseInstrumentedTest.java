package com.long2know.sportlogger.persistence.watch;

import android.content.Context;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.long2know.utilities.contract.proto.ActivityRecord;
import com.long2know.utilities.contract.proto.ActivityType;
import com.long2know.utilities.contract.proto.SourceKind;
import com.long2know.utilities.contract.proto.SourceMetadata;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class WatchDatabaseInstrumentedTest {
    private static final String DATABASE = "watch-canonical-instrumented.db";
    private static final String SESSION_ID = "22e12ef5-c99d-5e04-bbb6-007737ca6216";
    private static final String TRANSFER_ID = "8cb7fb24-dfe6-4ba6-85d0-411c87d87cc8";

    private Context context;
    private WatchDatabase database;

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
        database.close();
        context.deleteDatabase(DATABASE);
    }

    @Test
    public void versionOnePersistsCompletionAndOutboxInOneRepositoryCall() {
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
        assertEquals(1, repository.readyOutbox(10).join().size());
    }
}
