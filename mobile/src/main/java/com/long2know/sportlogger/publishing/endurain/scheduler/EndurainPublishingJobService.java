package com.long2know.sportlogger.publishing.endurain.scheduler;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

import com.long2know.sportlogger.publishing.endurain.EndurainPublishingCoordinator;
import com.long2know.sportlogger.publishing.endurain.factory.EndurainPublishingFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

public final class EndurainPublishingJobService extends JobService {
    private static final String TAG = "EndurainPublishing";

    private final AtomicLong generation = new AtomicLong();
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(new JobThreadFactory());

    @Override
    public boolean onStartJob(JobParameters parameters) {
        long runGeneration = generation.incrementAndGet();
        boolean recoveryOnly = parameters.getExtras().getBoolean(
                EndurainPublishingScheduler.EXTRA_RECOVERY_ONLY,
                false);
        executor.submit(() -> {
            EndurainPublishingCoordinator.RunResult result = null;
            try {
                result = EndurainPublishingFactory.runScheduled(
                        this,
                        !recoveryOnly);
            } catch (Exception error) {
                Log.e(TAG, "Endurain publishing job failed");
            }
            if (generation.get() != runGeneration) {
                return;
            }
            jobFinished(parameters, result == null);
            if (result != null && result.getNextPublishAtUtcMs() != null) {
                new EndurainPublishingScheduler(this).schedulePublishAt(
                        result.getNextPublishAtUtcMs());
            }
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters parameters) {
        generation.incrementAndGet();
        return true;
    }

    @Override
    public void onDestroy() {
        generation.incrementAndGet();
        executor.shutdown();
        super.onDestroy();
    }

    private static final class JobThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(
                    runnable,
                    "sportlogger-endurain-job");
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        }
    }
}
