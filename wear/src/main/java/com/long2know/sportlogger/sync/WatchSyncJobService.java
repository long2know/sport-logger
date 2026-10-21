package com.long2know.sportlogger.sync;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

public final class WatchSyncJobService extends JobService {
    private static final String TAG = "WatchCanonicalSyncJob";

    private final AtomicLong generation = new AtomicLong();
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(new SyncThreadFactory());
    private volatile Future<?> runningTask;

    @Override
    public boolean onStartJob(JobParameters parameters) {
        long runGeneration = generation.incrementAndGet();
        runningTask = executor.submit(() -> {
            boolean reschedule = true;
            try {
                WatchSyncRunner.Result result =
                        WatchSyncDispatcher.runNow(this, true);
                reschedule = result.isRetryRequired();
            } catch (Exception error) {
                Log.e(TAG, "Canonical sync recovery failed", error);
            }
            if (generation.get() == runGeneration) {
                jobFinished(parameters, reschedule);
            }
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters parameters) {
        generation.incrementAndGet();
        Future<?> task = runningTask;
        if (task != null) {
            task.cancel(true);
        }
        return true;
    }

    @Override
    public void onDestroy() {
        generation.incrementAndGet();
        Future<?> task = runningTask;
        if (task != null) {
            task.cancel(true);
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    private static final class SyncThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "sportlogger-watch-sync");
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        }
    }
}
