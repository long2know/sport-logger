package com.long2know.sportlogger.transfer;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public final class PendingAcknowledgementRetry {
    private static final int JOB_ID = 0x53504f52;
    private static final long INITIAL_DELAY_MILLIS = 1_000L;

    private final PhoneActivityStore store;
    private final ActivityTransferReceiver.Acknowledger acknowledger;

    public PendingAcknowledgementRetry(
            PhoneActivityStore store,
            ActivityTransferReceiver.Acknowledger acknowledger) {
        this.store = Objects.requireNonNull(store);
        this.acknowledger = Objects.requireNonNull(acknowledger);
    }

    public Result run() throws IOException {
        List<PhoneActivityStore.PendingAcknowledgement> pending =
                store.pendingAcknowledgements();
        int queued = 0;
        Exception firstFailure = null;
        for (PhoneActivityStore.PendingAcknowledgement acknowledgement : pending) {
            try {
                acknowledger.acknowledge(
                        acknowledgement.getAcknowledgementPath(),
                        acknowledgement.getTransferId(),
                        acknowledgement.getSourceUri());
                store.acknowledgementQueued(acknowledgement);
                queued++;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                if (firstFailure == null) {
                    firstFailure = error;
                }
                break;
            } catch (Exception error) {
                if (firstFailure == null) {
                    firstFailure = error;
                }
            }
        }
        return new Result(pending.size(), queued, firstFailure);
    }

    public static void schedule(Context context) {
        Context applicationContext = context.getApplicationContext();
        JobScheduler scheduler = applicationContext.getSystemService(JobScheduler.class);
        if (scheduler == null) {
            throw new IllegalStateException(
                    "JobScheduler is unavailable for acknowledgement retry");
        }
        JobInfo job = new JobInfo.Builder(
                JOB_ID,
                new ComponentName(
                        applicationContext,
                        PendingAcknowledgementJobService.class))
                .setPersisted(true)
                .setMinimumLatency(INITIAL_DELAY_MILLIS)
                .setBackoffCriteria(
                        JobInfo.DEFAULT_INITIAL_BACKOFF_MILLIS,
                        JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build();
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
            throw new IllegalStateException(
                    "Could not schedule durable acknowledgement retry");
        }
    }

    public static final class Result {
        private final int pendingCount;
        private final int queuedCount;
        private final Exception firstFailure;

        private Result(int pendingCount, int queuedCount, Exception firstFailure) {
            this.pendingCount = pendingCount;
            this.queuedCount = queuedCount;
            this.firstFailure = firstFailure;
        }

        public int getPendingCount() {
            return pendingCount;
        }

        public int getQueuedCount() {
            return queuedCount;
        }

        public int getFailureCount() {
            return pendingCount - queuedCount;
        }

        public Exception getFirstFailure() {
            return firstFailure;
        }

        public boolean needsRetry() {
            return queuedCount != pendingCount;
        }
    }
}
