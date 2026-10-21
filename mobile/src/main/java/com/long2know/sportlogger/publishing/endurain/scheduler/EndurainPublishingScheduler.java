package com.long2know.sportlogger.publishing.endurain.scheduler;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;

import java.util.Objects;

public final class EndurainPublishingScheduler
        implements EndurainPublishingSchedule {
    static final String EXTRA_RECOVERY_ONLY = "recovery_only";
    private static final int PUBLISH_JOB_ID = 0x534c4501;
    private static final int RECOVERY_JOB_ID = 0x534c4502;

    private final Context applicationContext;

    public EndurainPublishingScheduler(Context context) {
        applicationContext =
                Objects.requireNonNull(context, "context").getApplicationContext();
    }

    @Override
    public void schedulePublishAt(long notBeforeUtcMs) {
        schedule(PUBLISH_JOB_ID, notBeforeUtcMs, false, true);
    }

    @Override
    public void scheduleRecoveryAt(long notBeforeUtcMs) {
        schedule(RECOVERY_JOB_ID, notBeforeUtcMs, true, false);
    }

    private void schedule(
            int jobId,
            long notBeforeUtcMs,
            boolean recoveryOnly,
            boolean requiresNetwork) {
        JobScheduler scheduler =
                applicationContext.getSystemService(JobScheduler.class);
        if (scheduler == null) {
            throw new IllegalStateException(
                    "JobScheduler is unavailable for Endurain publishing");
        }
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean(EXTRA_RECOVERY_ONLY, recoveryOnly);
        JobInfo.Builder builder = new JobInfo.Builder(
                jobId,
                new ComponentName(
                        applicationContext,
                        EndurainPublishingJobService.class))
                .setPersisted(true)
                .setMinimumLatency(Math.max(
                        0L,
                        notBeforeUtcMs - System.currentTimeMillis()))
                .setBackoffCriteria(
                        JobInfo.DEFAULT_INITIAL_BACKOFF_MILLIS,
                        JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setExtras(extras);
        if (requiresNetwork) {
            builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
        }
        if (scheduler.schedule(builder.build()) != JobScheduler.RESULT_SUCCESS) {
            throw new IllegalStateException(
                    "Could not schedule Endurain publishing recovery");
        }
    }
}
