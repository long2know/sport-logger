package com.long2know.sportlogger.transfer;

import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository;

import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

final class PhoneTombstoneSyncRunner {
    interface WatchNodeSource {
        List<String> reachableNodeIds() throws Exception;
    }

    interface TransferSender {
        void send(
                String nodeId,
                String transferId,
                byte[] exactTransferBytes) throws Exception;
    }

    interface EventSink {
        void changed(
                PhoneActivityRepository.TombstoneOutboxItem item,
                String failureCode);
    }

    static final int BATCH_LIMIT = 50;

    private final PhoneActivityRepository repository;
    private final WatchNodeSource watchNodeSource;
    private final TransferSender transferSender;
    private final EventSink eventSink;
    private final LongSupplier clock;

    PhoneTombstoneSyncRunner(
            PhoneActivityRepository repository,
            WatchNodeSource watchNodeSource,
            TransferSender transferSender,
            EventSink eventSink,
            LongSupplier clock) {
        this.repository = Objects.requireNonNull(repository);
        this.watchNodeSource = Objects.requireNonNull(watchNodeSource);
        this.transferSender = Objects.requireNonNull(transferSender);
        this.eventSink = Objects.requireNonNull(eventSink);
        this.clock = Objects.requireNonNull(clock);
    }

    Result run(boolean reclaimInterruptedTransfers) {
        long nowUtcMs = clock.getAsLong();
        List<PhoneActivityRepository.TombstoneOutboxItem> items =
                reclaimInterruptedTransfers
                        ? repository.reclaimPendingTombstoneOutbox(
                        BATCH_LIMIT,
                        nowUtcMs)
                        : repository.pendingTombstoneOutbox(BATCH_LIMIT);
        if (reclaimInterruptedTransfers) {
            for (PhoneActivityRepository.TombstoneOutboxItem item : items) {
                eventSink.changed(item, null);
            }
        }
        if (items.isEmpty()) {
            return Result.idle();
        }

        List<String> nodeIds;
        try {
            nodeIds = watchNodeSource.reachableNodeIds();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return failWithoutSend(items, "CONNECTIVITY_LOOKUP_INTERRUPTED");
        } catch (Exception error) {
            return failWithoutSend(items, "CONNECTIVITY_LOOKUP_FAILED");
        }
        if (nodeIds.isEmpty()) {
            return failWithoutSend(items, "WATCH_UNREACHABLE");
        }

        int sent = 0;
        int failed = 0;
        for (PhoneActivityRepository.TombstoneOutboxItem item : items) {
            if (send(item, nodeIds)) {
                sent++;
            } else {
                failed++;
            }
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
        }
        return new Result(items.size(), sent, failed, true);
    }

    private Result failWithoutSend(
            List<PhoneActivityRepository.TombstoneOutboxItem> items,
            String failureCode) {
        for (PhoneActivityRepository.TombstoneOutboxItem item : items) {
            PhoneActivityRepository.TombstoneOutboxItem failed =
                    repository.transitionTombstoneOutbox(
                            item.getTransferId(),
                            item.getState(),
                            PhoneActivityRepository.TOMBSTONE_RETRYABLE,
                            null,
                            failureCode,
                            "Canonical watch receiver is not currently reachable");
            eventSink.changed(failed, failureCode);
        }
        return new Result(items.size(), 0, items.size(), true);
    }

    private boolean send(
            PhoneActivityRepository.TombstoneOutboxItem item,
            List<String> nodeIds) {
        PhoneActivityRepository.TombstoneOutboxItem sending =
                repository.transitionTombstoneOutbox(
                        item.getTransferId(),
                        item.getState(),
                        PhoneActivityRepository.TOMBSTONE_SENDING,
                        clock.getAsLong(),
                        null,
                        null);
        eventSink.changed(sending, null);
        try {
            sendToAnyNode(
                    nodeIds,
                    nodeId -> transferSender.send(
                            nodeId,
                            sending.getTransferId(),
                            sending.getPayload()));
            PhoneActivityRepository.TombstoneOutboxItem awaiting =
                    repository.transitionTombstoneOutbox(
                            sending.getTransferId(),
                            PhoneActivityRepository.TOMBSTONE_SENDING,
                            PhoneActivityRepository.TOMBSTONE_AWAITING_COMMIT_ACK,
                            null,
                            null,
                            null);
            eventSink.changed(awaiting, null);
            return true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            failSending(sending, "DATA_LAYER_SEND_INTERRUPTED");
            return false;
        } catch (Exception error) {
            failSending(sending, "DATA_LAYER_SEND_FAILED");
            return false;
        }
    }

    private void failSending(
            PhoneActivityRepository.TombstoneOutboxItem sending,
            String failureCode) {
        PhoneActivityRepository.TombstoneOutboxItem failed =
                repository.transitionTombstoneOutbox(
                        sending.getTransferId(),
                        PhoneActivityRepository.TOMBSTONE_SENDING,
                        PhoneActivityRepository.TOMBSTONE_RETRYABLE,
                        null,
                        failureCode,
                        "Canonical whole-file tombstone transfer did not complete");
        eventSink.changed(failed, failureCode);
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
        throw new IllegalStateException("No reachable watch node was available");
    }

    private interface NodeSend {
        void send(String nodeId) throws Exception;
    }

    static final class Result {
        private final int pendingCount;
        private final int sentCount;
        private final int failureCount;
        private final boolean retryRequired;

        private Result(
                int pendingCount,
                int sentCount,
                int failureCount,
                boolean retryRequired) {
            this.pendingCount = pendingCount;
            this.sentCount = sentCount;
            this.failureCount = failureCount;
            this.retryRequired = retryRequired;
        }

        private static Result idle() {
            return new Result(0, 0, 0, false);
        }

        static Result busy() {
            return new Result(0, 0, 0, true);
        }

        int getPendingCount() {
            return pendingCount;
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
