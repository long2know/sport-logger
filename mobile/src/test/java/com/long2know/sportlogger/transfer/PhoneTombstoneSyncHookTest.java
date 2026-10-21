package com.long2know.sportlogger.transfer;

import android.app.Application;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.Intent;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository;
import com.long2know.sportlogger.persistence.phone.PhoneDatabase;

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
@Config(sdk = 26)
public class PhoneTombstoneSyncHookTest {
    private static final String SESSION_ID = "30df2849-93e5-4776-bbd7-844430b57983";
    private static final String TRANSFER_ID = "fc82f866-5d7c-4338-a6fc-2d0f08fe495c";

    private Context context;
    private JobScheduler scheduler;
    private PhoneDatabase database;
    private PhoneActivityRepository repository;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        scheduler = context.getSystemService(JobScheduler.class);
        scheduler.cancelAll();
        database = Room.inMemoryDatabaseBuilder(context, PhoneDatabase.class)
                .allowMainThreadQueries()
                .build();
        repository = new PhoneActivityRepository(database);
    }

    @After
    public void tearDown() {
        scheduler.cancelAll();
        database.close();
    }

    @Test
    public void deletionHookBroadcastsQueuedStateAndSchedulesRecovery() {
        repository.deleteLocal(
                SESSION_ID,
                1L,
                1L,
                TRANSFER_ID,
                2_000L);
        PhoneActivityRepository.TombstoneOutboxItem outbox =
                repository.tombstoneOutbox(TRANSFER_ID);
        ShadowApplication shadowApplication =
                Shadows.shadowOf((Application) context);
        shadowApplication.getBroadcastIntents().clear();

        PhoneTombstoneSyncHook.onDeletionCommitted(context, outbox);

        Intent intent = shadowApplication.getBroadcastIntents().stream()
                .filter(item -> PhoneSyncEvents.ACTION_TOMBSTONE_SYNC_STATE_CHANGED.equals(
                        item.getAction()))
                .findFirst()
                .orElse(null);
        assertNotNull(intent);
        assertEquals(
                PhoneActivityRepository.TOMBSTONE_READY,
                intent.getStringExtra(PhoneSyncEvents.EXTRA_CANONICAL_STATE));
        assertEquals(
                PhoneSyncEvents.DisplayState.QUEUED.name(),
                intent.getStringExtra(PhoneSyncEvents.EXTRA_DISPLAY_STATE));
        assertEquals(
                TRANSFER_ID,
                intent.getStringExtra(PhoneSyncEvents.EXTRA_TRANSFER_ID));
        List<JobInfo> jobs = scheduler.getAllPendingJobs();
        assertEquals(1, jobs.size());
        assertEquals(
                PhoneTombstoneSyncJobService.class.getName(),
                jobs.get(0).getService().getClassName());
    }
}
