package com.long2know.sportlogger.transfer;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

public final class PendingAcknowledgementJobService extends JobService {
    private static final String TAG = "PendingAckJob";

    private final AtomicLong generation = new AtomicLong();
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(new RetryThreadFactory());
    private volatile Future<?> runningTask;

    @Override
    public boolean onStartJob(JobParameters parameters) {
        long runGeneration = generation.incrementAndGet();
        runningTask = executor.submit(() -> {
            boolean reschedule = true;
            try {
                PendingAcknowledgementRetry.Result result =
                        new PendingAcknowledgementRetry(
                                new PhoneActivityStore(this),
                                new WearDataLayerAcknowledger(this))
                                .run();
                reschedule = result.needsRetry();
                if (reschedule) {
                    Log.w(
                            TAG,
                            "Acknowledgement retry left "
                                    + result.getFailureCount()
                                    + " marker(s) pending",
                            result.getFirstFailure());
                }
            } catch (Exception error) {
                Log.e(TAG, "Could not retry pending acknowledgements", error);
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

    private static final class RetryThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "sport-logger-phone-ack");
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        }
    }
}
