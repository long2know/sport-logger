package com.long2know.sportlogger.sync;

import com.google.protobuf.InvalidProtocolBufferException;
import com.long2know.sportlogger.persistence.watch.WatchActivityRepository;
import com.long2know.utilities.contract.CanonicalContractException;
import com.long2know.utilities.contract.proto.CommitAck;

import java.util.Objects;

final class WatchCommitAckHandler {
    interface EventSink {
        void activityCommitted(WatchActivityRepository.OutboxItem item);

        void tombstoneCommitted(
                WatchActivityRepository.TombstoneOutboxItem item);
    }

    private final WatchActivityRepository repository;
    private final EventSink eventSink;

    WatchCommitAckHandler(
            WatchActivityRepository repository,
            EventSink eventSink) {
        this.repository = Objects.requireNonNull(repository);
        this.eventSink = Objects.requireNonNull(eventSink);
    }

    Result receive(byte[] exactAcknowledgementBytes, long committedAtUtcMs) {
        CommitAck acknowledgement = parse(exactAcknowledgementBytes);
        WatchActivityRepository.OutboxItem activity =
                repository.outbox(acknowledgement.getTransferId()).join();
        if (activity != null) {
            WatchActivityRepository.AckAcceptance accepted =
                    repository.acceptCommitAck(
                                    exactAcknowledgementBytes,
                                    committedAtUtcMs)
                            .join();
            eventSink.activityCommitted(accepted.getOutbox());
            return new Result(
                    accepted.isCommittedNow(),
                    accepted.getOutbox().getTransferId(),
                    false);
        }
        WatchActivityRepository.TombstoneOutboxItem tombstone =
                repository.tombstoneOutbox(acknowledgement.getTransferId()).join();
        if (tombstone != null) {
            WatchActivityRepository.TombstoneAckAcceptance accepted =
                    repository.acceptTombstoneCommitAck(
                                    exactAcknowledgementBytes,
                                    committedAtUtcMs)
                            .join();
            eventSink.tombstoneCommitted(accepted.getOutbox());
            return new Result(
                    accepted.isCommittedNow(),
                    accepted.getOutbox().getTransferId(),
                    true);
        }
        throw new CanonicalContractException(
                "OUTBOX_NOT_FOUND",
                "Acknowledged canonical transfer does not exist");
    }

    private static CommitAck parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new CanonicalContractException(
                    "ACK_REQUIRED",
                    "Acknowledgement bytes are required");
        }
        try {
            return CommitAck.parseFrom(bytes);
        } catch (InvalidProtocolBufferException error) {
            throw new CanonicalContractException(
                    "ACK_CORRUPT",
                    "Acknowledgement is not valid protobuf",
                    error);
        }
    }

    static final class Result {
        private final boolean committedNow;
        private final String transferId;
        private final boolean tombstone;

        private Result(
                boolean committedNow,
                String transferId,
                boolean tombstone) {
            this.committedNow = committedNow;
            this.transferId = transferId;
            this.tombstone = tombstone;
        }

        boolean isCommittedNow() {
            return committedNow;
        }

        String getTransferId() {
            return transferId;
        }

        boolean isTombstone() {
            return tombstone;
        }
    }
}
