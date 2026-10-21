package com.long2know.sportlogger.sync;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

final class WatchSyncScheduler {
    private static final int JOB_ID = 0x534c5736;
    private static final long FALLBACK_DELAY_MILLIS = 60_000L;

    private WatchSyncScheduler() {
    }

    static void scheduleImmediate(Context context) {
        schedule(context, 0L);
    }

    static void scheduleFallback(Context context) {
        schedule(context, FALLBACK_DELAY_MILLIS);
    }

    static void cancel(Context context) {
        JobScheduler scheduler =
                context.getApplicationContext().getSystemService(JobScheduler.class);
        if (scheduler != null) {
            scheduler.cancel(JOB_ID);
        }
    }

    private static void schedule(Context context, long minimumLatencyMillis) {
        Context applicationContext = context.getApplicationContext();
        JobScheduler scheduler =
                applicationContext.getSystemService(JobScheduler.class);
        if (scheduler == null) {
            throw new IllegalStateException(
                    "JobScheduler is unavailable for canonical sync recovery");
        }
        JobInfo job = new JobInfo.Builder(
                JOB_ID,
                new ComponentName(
                        applicationContext,
                        WatchSyncJobService.class))
                .setPersisted(true)
                .setMinimumLatency(minimumLatencyMillis)
                .setBackoffCriteria(
                        JobInfo.DEFAULT_INITIAL_BACKOFF_MILLIS,
                        JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build();
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
            throw new IllegalStateException(
                    "Could not schedule canonical sync recovery");
        }
    }
}
