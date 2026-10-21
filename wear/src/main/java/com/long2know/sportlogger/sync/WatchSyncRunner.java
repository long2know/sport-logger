package com.long2know.sportlogger.sync;

import com.long2know.sportlogger.persistence.watch.WatchActivityRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

final class WatchSyncRunner {
    interface PhoneNodeSource {
        List<String> reachableNodeIds() throws Exception;
    }

    interface TransferSender {
        void sendActivity(
                String nodeId,
                String transferId,
                byte[] exactTransferBytes) throws Exception;

        void sendTombstone(
                String nodeId,
                String transferId,
                byte[] exactTransferBytes) throws Exception;
    }

    interface EventSink {
        void activityChanged(
                WatchActivityRepository.OutboxItem item,
                String failureCode);

        void tombstoneChanged(
                WatchActivityRepository.TombstoneOutboxItem item,
                String failureCode);
    }

    static final int BATCH_LIMIT = 50;

    private final WatchActivityRepository repository;
    private final PhoneNodeSource phoneNodeSource;
    private final TransferSender transferSender;
    private final EventSink eventSink;
    private final LongSupplier clock;

    WatchSyncRunner(
            WatchActivityRepository repository,
            PhoneNodeSource phoneNodeSource,
            TransferSender transferSender,
            EventSink eventSink,
            LongSupplier clock) {
        this.repository = Objects.requireNonNull(repository);
        this.phoneNodeSource = Objects.requireNonNull(phoneNodeSource);
        this.transferSender = Objects.requireNonNull(transferSender);
        this.eventSink = Objects.requireNonNull(eventSink);
        this.clock = Objects.requireNonNull(clock);
    }

    Result run(boolean reclaimInterruptedTransfers) {
        long nowUtcMs = clock.getAsLong();
        List<WatchActivityRepository.OutboxItem> activities =
                reclaimInterruptedTransfers
                        ? repository.reclaimPendingOutbox(BATCH_LIMIT, nowUtcMs).join()
                        : repository.pendingOutbox(BATCH_LIMIT).join();
        List<WatchActivityRepository.TombstoneOutboxItem> tombstones =
                new ArrayList<>(repository.pendingTombstoneOutbox(BATCH_LIMIT).join());
        if (reclaimInterruptedTransfers) {
            tombstones = reclaimSendingTombstones(tombstones, nowUtcMs);
        }
        if (activities.isEmpty() && tombstones.isEmpty()) {
            return Result.idle();
        }

        List<String> nodeIds;
        try {
            nodeIds = phoneNodeSource.reachableNodeIds();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return failWithoutSend(
                    activities,
                    tombstones,
                    "CONNECTIVITY_LOOKUP_INTERRUPTED");
        } catch (Exception error) {
            return failWithoutSend(
                    activities,
                    tombstones,
                    "CONNECTIVITY_LOOKUP_FAILED");
        }
        if (nodeIds.isEmpty()) {
            return failWithoutSend(
                    activities,
                    tombstones,
                    "PHONE_UNREACHABLE");
        }

        int sent = 0;
        int failed = 0;
        for (WatchActivityRepository.OutboxItem item : activities) {
            if (sendActivity(item, nodeIds)) {
                sent++;
            } else {
                failed++;
            }
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
        }
        if (!Thread.currentThread().isInterrupted()) {
            for (WatchActivityRepository.TombstoneOutboxItem item : tombstones) {
                if (sendTombstone(item, nodeIds)) {
                    sent++;
                } else {
                    failed++;
                }
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
            }
        }
        return new Result(
                activities.size(),
                tombstones.size(),
                sent,
                failed,
                true);
    }

    private List<WatchActivityRepository.TombstoneOutboxItem> reclaimSendingTombstones(
            List<WatchActivityRepository.TombstoneOutboxItem> tombstones,
            long recoveredAtUtcMs) {
        List<WatchActivityRepository.TombstoneOutboxItem> recovered =
                new ArrayList<>(tombstones.size());
        for (WatchActivityRepository.TombstoneOutboxItem item : tombstones) {
            if (WatchActivityRepository.OUTBOX_SENDING.equals(item.getState())) {
                WatchActivityRepository.TombstoneOutboxItem updated =
                        repository.transitionTombstoneOutbox(
                                        item.getTransferId(),
                                        WatchActivityRepository.OUTBOX_SENDING,
                                        WatchActivityRepository.OUTBOX_RETRYABLE,
                                        recoveredAtUtcMs,
                                        "PROCESS_RESTART",
                                        "Recovered interrupted whole-file tombstone transfer")
                                .join();
                eventSink.tombstoneChanged(updated, "PROCESS_RESTART");
                recovered.add(updated);
            } else {
                recovered.add(item);
            }
        }
        return recovered;
    }

    private Result failWithoutSend(
            List<WatchActivityRepository.OutboxItem> activities,
            List<WatchActivityRepository.TombstoneOutboxItem> tombstones,
            String failureCode) {
        for (WatchActivityRepository.OutboxItem item : activities) {
            WatchActivityRepository.OutboxItem failed =
                    repository.transitionOutbox(
                                    item.getTransferId(),
                                    item.getState(),
                                    WatchActivityRepository.OUTBOX_RETRYABLE,
                                    null,
                                    failureCode,
                                    "Canonical phone receiver is not currently reachable")
                            .join();
            eventSink.activityChanged(failed, failureCode);
        }
        for (WatchActivityRepository.TombstoneOutboxItem item : tombstones) {
            WatchActivityRepository.TombstoneOutboxItem failed =
                    repository.transitionTombstoneOutbox(
                                    item.getTransferId(),
                                    item.getState(),
                                    WatchActivityRepository.OUTBOX_RETRYABLE,
                                    null,
                                    failureCode,
                                    "Canonical phone receiver is not currently reachable")
                            .join();
            eventSink.tombstoneChanged(failed, failureCode);
        }
        return new Result(
                activities.size(),
                tombstones.size(),
                0,
                activities.size() + tombstones.size(),
                true);
    }

    private boolean sendActivity(
            WatchActivityRepository.OutboxItem item,
            List<String> nodeIds) {
        long attemptedAtUtcMs = clock.getAsLong();
        WatchActivityRepository.OutboxItem sending =
                repository.transitionOutbox(
                                item.getTransferId(),
                                item.getState(),
                                WatchActivityRepository.OUTBOX_SENDING,
                                attemptedAtUtcMs,
                                null,
                                null)
                        .join();
        eventSink.activityChanged(sending, null);
        try {
            sendToAnyNode(
                    nodeIds,
                    nodeId -> transferSender.sendActivity(
                            nodeId,
                            sending.getTransferId(),
                            sending.getPayload()));
            WatchActivityRepository.OutboxItem awaiting =
                    repository.transitionOutbox(
                                    sending.getTransferId(),
                                    WatchActivityRepository.OUTBOX_SENDING,
                                    WatchActivityRepository.OUTBOX_AWAITING_COMMIT_ACK,
                                    null,
                                    null,
                                    null)
                            .join();
            eventSink.activityChanged(awaiting, null);
            return true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            failActivity(sending, "DATA_LAYER_SEND_INTERRUPTED");
            return false;
        } catch (Exception error) {
            failActivity(sending, "DATA_LAYER_SEND_FAILED");
            return false;
        }
    }

    private boolean sendTombstone(
            WatchActivityRepository.TombstoneOutboxItem item,
            List<String> nodeIds) {
        long attemptedAtUtcMs = clock.getAsLong();
        WatchActivityRepository.TombstoneOutboxItem sending =
                repository.transitionTombstoneOutbox(
                                item.getTransferId(),
                                item.getState(),
                                WatchActivityRepository.OUTBOX_SENDING,
                                attemptedAtUtcMs,
                                null,
                                null)
                        .join();
        eventSink.tombstoneChanged(sending, null);
        try {
            sendToAnyNode(
                    nodeIds,
                    nodeId -> transferSender.sendTombstone(
                            nodeId,
                            sending.getTransferId(),
                            sending.getPayload()));
            WatchActivityRepository.TombstoneOutboxItem awaiting =
                    repository.transitionTombstoneOutbox(
                                    sending.getTransferId(),
                                    WatchActivityRepository.OUTBOX_SENDING,
                                    WatchActivityRepository.OUTBOX_AWAITING_COMMIT_ACK,
                                    null,
                                    null,
                                    null)
                            .join();
            eventSink.tombstoneChanged(awaiting, null);
            return true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            failTombstone(sending, "DATA_LAYER_SEND_INTERRUPTED");
            return false;
        } catch (Exception error) {
            failTombstone(sending, "DATA_LAYER_SEND_FAILED");
            return false;
        }
    }

    private void failActivity(
            WatchActivityRepository.OutboxItem sending,
            String failureCode) {
        WatchActivityRepository.OutboxItem failed =
                repository.transitionOutbox(
                                sending.getTransferId(),
                                WatchActivityRepository.OUTBOX_SENDING,
                                WatchActivityRepository.OUTBOX_RETRYABLE,
                                null,
                                failureCode,
                                "Canonical whole-file activity transfer did not complete")
                        .join();
        eventSink.activityChanged(failed, failureCode);
    }

    private void failTombstone(
            WatchActivityRepository.TombstoneOutboxItem sending,
            String failureCode) {
        WatchActivityRepository.TombstoneOutboxItem failed =
                repository.transitionTombstoneOutbox(
                                sending.getTransferId(),
                                WatchActivityRepository.OUTBOX_SENDING,
                                WatchActivityRepository.OUTBOX_RETRYABLE,
                                null,
                                failureCode,
                                "Canonical whole-file tombstone transfer did not complete")
                        .join();
        eventSink.tombstoneChanged(failed, failureCode);
    }

    private static void sendToAnyNode(
            List<String> nodeIds,
            NodeSend operation) throws Exception {
        Exception firstFailure = null;
        for (String nodeId : nodeIds) {
            try {
                operation.send(nodeId);
                return;
            } catch (InterruptedException error) {
                throw error;
            } catch (Exception error) {
                if (firstFailure == null) {
                    firstFailure = error;
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
        throw new IllegalStateException("No reachable phone node was available");
    }

    private interface NodeSend {
        void send(String nodeId) throws Exception;
    }

    static final class Result {
        private final int activityCount;
        private final int tombstoneCount;
        private final int sentCount;
        private final int failureCount;
        private final boolean retryRequired;

        private Result(
                int activityCount,
                int tombstoneCount,
                int sentCount,
                int failureCount,
                boolean retryRequired) {
            this.activityCount = activityCount;
            this.tombstoneCount = tombstoneCount;
            this.sentCount = sentCount;
            this.failureCount = failureCount;
            this.retryRequired = retryRequired;
        }

        private static Result idle() {
            return new Result(0, 0, 0, 0, false);
        }

        static Result busy() {
            return new Result(0, 0, 0, 0, true);
        }

        int getActivityCount() {
            return activityCount;
        }

        int getTombstoneCount() {
            return tombstoneCount;
        }

        int getSentCount() {
            return sentCount;
        }

        int getFailureCount() {
            return failureCount;
        }

        boolean isRetryRequired() {
            return retryRequired;
        }
    }
}
