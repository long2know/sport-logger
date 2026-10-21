package com.long2know.sportlogger.publishing.endurain.scheduler;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 26)
public final class EndurainPublishingSchedulerTest {
    private JobScheduler jobs;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        jobs = context.getSystemService(JobScheduler.class);
        jobs.cancelAll();
    }

    @After
    public void tearDown() {
        jobs.cancelAll();
    }

    @Test
    public void publishAndRecoveryJobsPersistWithDifferentNetworkNeeds() {
        Context context = ApplicationProvider.getApplicationContext();
        EndurainPublishingScheduler scheduler =
                new EndurainPublishingScheduler(context);
        long now = System.currentTimeMillis();

        scheduler.schedulePublishAt(now + 60_000L);
        scheduler.scheduleRecoveryAt(now + 120_000L);

        List<JobInfo> pending = jobs.getAllPendingJobs();
        assertEquals(2, pending.size());
        JobInfo publish = pending.stream()
                .filter(job -> !job.getExtras().getBoolean(
                        EndurainPublishingScheduler.EXTRA_RECOVERY_ONLY))
                .findFirst()
                .orElseThrow();
        JobInfo recovery = pending.stream()
                .filter(job -> job.getExtras().getBoolean(
                        EndurainPublishingScheduler.EXTRA_RECOVERY_ONLY))
                .findFirst()
                .orElseThrow();
        assertTrue(publish.isPersisted());
        assertTrue(recovery.isPersisted());
        assertEquals(JobInfo.NETWORK_TYPE_ANY, publish.getNetworkType());
        assertEquals(JobInfo.NETWORK_TYPE_NONE, recovery.getNetworkType());
        assertFalse(publish.getExtras().getBoolean(
                EndurainPublishingScheduler.EXTRA_RECOVERY_ONLY));
        assertTrue(recovery.getExtras().getBoolean(
                EndurainPublishingScheduler.EXTRA_RECOVERY_ONLY));
    }
}
