package com.long2know.sportlogger.sync;

import android.content.Context;

import com.long2know.sportlogger.persistence.watch.WatchActivityRepository;

import java.util.concurrent.locks.ReentrantLock;

final class WatchSyncDispatcher {
    private static final ReentrantLock RUN_LOCK = new ReentrantLock();

    private WatchSyncDispatcher() {
    }

    static WatchSyncRunner.Result runNow(
            Context context,
            boolean reclaimInterruptedTransfers) {
        if (!RUN_LOCK.tryLock()) {
            return WatchSyncRunner.Result.busy();
        }
        Context applicationContext = context.getApplicationContext();
        try (WatchActivityRepository repository =
                     WatchActivityRepository.open(applicationContext)) {
            WearPhoneNodeResolver nodeResolver =
                    new WearPhoneNodeResolver(applicationContext);
            WearChannelFileSender fileSender =
                    new WearChannelFileSender(applicationContext);
            WatchSyncRunner runner = new WatchSyncRunner(
                    repository,
                    nodeResolver::reachableNodeIds,
                    new WatchSyncRunner.TransferSender() {
                        @Override
                        public void sendActivity(
                                String nodeId,
                                String transferId,
                                byte[] exactTransferBytes) throws Exception {
                            fileSender.sendActivity(
                                    nodeId,
                                    transferId,
                                    exactTransferBytes);
                        }

                        @Override
                        public void sendTombstone(
                                String nodeId,
                                String transferId,
                                byte[] exactTransferBytes) throws Exception {
                            fileSender.sendTombstone(
                                    nodeId,
                                    transferId,
                                    exactTransferBytes);
                        }
                    },
                    new WatchSyncRunner.EventSink() {
                        @Override
                        public void activityChanged(
                                WatchActivityRepository.OutboxItem item,
                                String failureCode) {
                            WatchSyncEvents.stateChanged(
                                    applicationContext,
                                    item,
                                    failureCode);
                        }

                        @Override
                        public void tombstoneChanged(
                                WatchActivityRepository.TombstoneOutboxItem item,
                                String failureCode) {
                            WatchSyncEvents.tombstoneStateChanged(
                                    applicationContext,
                                    item,
                                    failureCode);
                        }
                    },
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
        try (WatchActivityRepository repository =
                     WatchActivityRepository.open(applicationContext)) {
            new WatchCommitAckHandler(
                    repository,
                    new WatchCommitAckHandler.EventSink() {
                        @Override
                        public void activityCommitted(
                                WatchActivityRepository.OutboxItem item) {
                            WatchSyncEvents.stateChanged(
                                    applicationContext,
                                    item,
                                    null);
                        }

                        @Override
                        public void tombstoneCommitted(
                                WatchActivityRepository.TombstoneOutboxItem item) {
                            WatchSyncEvents.tombstoneStateChanged(
                                    applicationContext,
                                    item,
                                    null);
                        }
                    })
                    .receive(exactAcknowledgementBytes, committedAtUtcMs);
            return !repository.pendingOutbox(1).join().isEmpty()
                    || !repository.pendingTombstoneOutbox(1).join().isEmpty();
        } finally {
            RUN_LOCK.unlock();
        }
    }

    static CanonicalWatchTombstoneTransferHandler.Result acceptIncomingTombstone(
            Context context,
            String sourceNodeId,
            String channelPath,
            byte[] exactTombstoneBytes,
            long committedAtUtcMs,
            CanonicalWatchTombstoneTransferHandler.AcknowledgementSender
                    acknowledgementSender) {
        RUN_LOCK.lock();
        Context applicationContext = context.getApplicationContext();
        try (WatchActivityRepository repository =
                     WatchActivityRepository.open(applicationContext)) {
            return new CanonicalWatchTombstoneTransferHandler(
                    repository,
                    acknowledgementSender)
                    .receive(
                            sourceNodeId,
                            channelPath,
                            exactTombstoneBytes,
                            committedAtUtcMs);
        } finally {
            RUN_LOCK.unlock();
        }
    }
}
