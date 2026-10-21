package com.long2know.sportlogger.sync;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.app.Application;
import android.content.Context;
import android.content.Intent;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.long2know.sportlogger.persistence.watch.WatchActivityRepository;
import com.long2know.sportlogger.persistence.watch.WatchDatabase;
import com.long2know.utilities.contract.proto.ActivityRecord;
import com.long2know.utilities.contract.proto.ActivityType;
import com.long2know.utilities.contract.proto.SourceKind;
import com.long2know.utilities.contract.proto.SourceMetadata;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class WatchSyncFinishHookTest {
    private static final String SESSION_ID = "45110f45-e214-4810-9524-65ad21c213cd";
    private static final String TRANSFER_ID = "077ec2d0-92bb-4775-a8bb-853c23ad7985";

    private Context context;
    private WatchDatabase database;
    private WatchActivityRepository repository;
    private JobScheduler scheduler;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        database = Room.inMemoryDatabaseBuilder(context, WatchDatabase.class)
                .allowMainThreadQueries()
                .build();
        repository = new WatchActivityRepository(database, Runnable::run);
        scheduler = context.getSystemService(JobScheduler.class);
        scheduler.cancelAll();
    }

    @After
    public void tearDown() {
        scheduler.cancelAll();
        database.close();
    }

    @Test
    public void completionHookBroadcastsDurableQueuedStateAndSchedulesRecovery()
            throws Exception {
        repository.startSession(
                        WatchActivityRepository.StartRequest
                                .builder(SESSION_ID, 39L, 1_000L)
                                .activityType(ActivityType.ACTIVITY_TYPE_RUNNING)
                                .build())
                .join();
        WatchActivityRepository.CompletionResult completion =
                repository.finishSession(
                                SESSION_ID,
                                39L,
                                ActivityRecord.newBuilder()
                                        .setSessionId(SESSION_ID)
                                        .setWatchRevision(1L)
                                        .setActivityType(
                                                ActivityType.ACTIVITY_TYPE_RUNNING)
                                        .setStartTimeUtcMs(1_000L)
                                        .setEndTimeUtcMs(2_000L)
                                        .setElapsedDurationMs(1_000L)
                                        .setSource(SourceMetadata.newBuilder()
                                                .setKind(
                                                        SourceKind
                                                                .SOURCE_KIND_HEALTH_SERVICES)
                                                .setAppContractVersion(1))
                                        .build(),
                                TRANSFER_ID,
                                2_100L)
                        .join();
        ShadowApplication shadowApplication =
                Shadows.shadowOf((Application) context);
        shadowApplication.getBroadcastIntents().clear();
        WatchSyncFinishHook.onCompletionCommitted(context, completion);

        Intent intent = shadowApplication.getBroadcastIntents().stream()
                .filter(item -> WatchSyncEvents.ACTION_SYNC_STATE_CHANGED.equals(
                        item.getAction()))
                .findFirst()
                .orElse(null);
        assertNotNull(intent);
        assertEquals(
                WatchActivityRepository.OUTBOX_READY,
                intent.getStringExtra(WatchSyncEvents.EXTRA_CANONICAL_STATE));
        assertEquals(
                WatchSyncEvents.DisplayState.QUEUED.name(),
                intent.getStringExtra(WatchSyncEvents.EXTRA_DISPLAY_STATE));
        assertEquals(
                TRANSFER_ID,
                intent.getStringExtra(WatchSyncEvents.EXTRA_TRANSFER_ID));
        List<JobInfo> jobs = scheduler.getAllPendingJobs();
        assertEquals(1, jobs.size());
        assertEquals(
                WatchSyncJobService.class.getName(),
                jobs.get(0).getService().getClassName());
    }
}
