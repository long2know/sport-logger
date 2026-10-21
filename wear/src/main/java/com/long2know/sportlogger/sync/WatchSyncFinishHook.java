package com.long2know.sportlogger.sync;

import android.content.Context;

import com.long2know.sportlogger.persistence.watch.WatchActivityRepository;

import java.util.Objects;

public final class WatchSyncFinishHook {
    private WatchSyncFinishHook() {
    }

    public static void onCompletionCommitted(
            Context context,
            WatchActivityRepository.CompletionResult completion) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(completion);
        WatchActivityRepository.OutboxItem outbox =
                Objects.requireNonNull(completion.getOutbox());
        WatchActivityRepository.SessionSnapshot session =
                Objects.requireNonNull(completion.getSession());
        if (!session.getSessionId().equals(outbox.getSessionId())
                || session.getWatchRevision() != outbox.getSessionRevision()) {
            throw new IllegalArgumentException(
                    "Canonical completion and outbox identity do not match");
        }
        WatchSyncEvents.stateChanged(
                context.getApplicationContext(),
                outbox,
                null);
        WatchSyncScheduler.scheduleImmediate(context);
    }

    public static void requestRetry(Context context) {
        WatchSyncScheduler.scheduleImmediate(Objects.requireNonNull(context));
    }
}
