package com.long2know.sportlogger.transfer;

import com.google.protobuf.InvalidProtocolBufferException;
import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository;
import com.long2know.utilities.contract.CanonicalContractException;
import com.long2know.utilities.contract.proto.CommitAck;

import java.util.Objects;

final class PhoneTombstoneCommitAckHandler {
    interface EventSink {
        void committed(PhoneActivityRepository.TombstoneOutboxItem item);
    }

    private final PhoneActivityRepository repository;
    private final EventSink eventSink;

    PhoneTombstoneCommitAckHandler(
            PhoneActivityRepository repository,
            EventSink eventSink) {
        this.repository = Objects.requireNonNull(repository);
        this.eventSink = Objects.requireNonNull(eventSink);
    }

    Result receive(byte[] exactAcknowledgementBytes, long committedAtUtcMs) {
        CommitAck acknowledgement = parse(exactAcknowledgementBytes);
        PhoneActivityRepository.TombstoneOutboxItem outbox =
                repository.tombstoneOutbox(acknowledgement.getTransferId());
        if (outbox == null) {
            throw new CanonicalContractException(
                    "TOMBSTONE_OUTBOX_NOT_FOUND",
                    "Acknowledged phone tombstone transfer does not exist");
        }
        PhoneActivityRepository.TombstoneAckAcceptance accepted =
                repository.acceptTombstoneCommitAck(
                        exactAcknowledgementBytes,
                        committedAtUtcMs);
        eventSink.committed(accepted.getOutbox());
        return new Result(
                accepted.isCommittedNow(),
                accepted.getOutbox().getTransferId());
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

        private Result(boolean committedNow, String transferId) {
            this.committedNow = committedNow;
            this.transferId = transferId;
        }

        boolean isCommittedNow() {
            return committedNow;
        }

        String getTransferId() {
            return transferId;
        }
    }
}
