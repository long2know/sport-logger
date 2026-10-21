package com.long2know.sportlogger.transfer;

import android.content.Context;

import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository;

import java.util.concurrent.locks.ReentrantLock;

final class PhoneTombstoneSyncDispatcher {
    private static final ReentrantLock RUN_LOCK = new ReentrantLock();

    private PhoneTombstoneSyncDispatcher() {
    }

    static PhoneTombstoneSyncRunner.Result runNow(
            Context context,
            boolean reclaimInterruptedTransfers) {
        if (!RUN_LOCK.tryLock()) {
            return PhoneTombstoneSyncRunner.Result.busy();
        }
        Context applicationContext = context.getApplicationContext();
        try (PhoneActivityRepository repository =
                     PhoneActivityRepository.open(applicationContext)) {
            PhoneWatchNodeResolver nodeResolver =
                    new PhoneWatchNodeResolver(applicationContext);
            PhoneChannelFileSender fileSender =
                    new PhoneChannelFileSender(applicationContext);
            PhoneTombstoneSyncRunner runner = new PhoneTombstoneSyncRunner(
                    repository,
                    nodeResolver::reachableNodeIds,
                    fileSender::sendTombstone,
                    (item, failureCode) ->
                            PhoneSyncEvents.outboundTombstoneChanged(
                                    applicationContext,
                                    item,
                                    failureCode),
                    System::currentTimeMillis);
            return runner.run(reclaimInterruptedTransfers);
        } finally {
            RUN_LOCK.unlock();
        }
    }

    static boolean acceptAcknowledgement(
            Context context,
            byte[] exactAcknowledgementBytes,
            long committedAtUtcMs) {
        RUN_LOCK.lock();
        Context applicationContext = context.getApplicationContext();
        try (PhoneActivityRepository repository =
                     PhoneActivityRepository.open(applicationContext)) {
            new PhoneTombstoneCommitAckHandler(
                    repository,
                    item -> PhoneSyncEvents.outboundTombstoneChanged(
                            applicationContext,
                            item,
                            null))
                    .receive(exactAcknowledgementBytes, committedAtUtcMs);
            return !repository.pendingTombstoneOutbox(1).isEmpty();
        } finally {
            RUN_LOCK.unlock();
        }
    }
}
