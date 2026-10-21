package com.long2know.sportlogger.transfer;

import android.content.Context;

import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository;

import java.util.Objects;

public final class PhoneTombstoneSyncHook {
    private PhoneTombstoneSyncHook() {
    }

    public static void onDeletionCommitted(
            Context context,
            PhoneActivityRepository.TombstoneOutboxItem outbox) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(outbox);
        Context applicationContext = context.getApplicationContext();
        PhoneSyncEvents.outboundTombstoneChanged(
                applicationContext,
                outbox,
                null);
        PhoneTombstoneSyncScheduler.scheduleImmediate(applicationContext);
    }

    public static void requestRetry(Context context) {
        PhoneTombstoneSyncScheduler.scheduleImmediate(
                Objects.requireNonNull(context));
    }
}
