package com.long2know.sportlogger.transfer;

import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository;
import com.long2know.utilities.contract.CanonicalContractException;
import com.long2know.utilities.contract.CanonicalTransfers;
import com.long2know.utilities.contract.proto.CommitAck;
import com.long2know.utilities.contract.proto.TombstoneRecord;
import com.long2know.utilities.transfer.CanonicalSyncProtocol;

import java.security.MessageDigest;
import java.util.Objects;

public final class CanonicalTombstoneTransferHandler {
    private final PhoneActivityRepository repository;
    private final CanonicalActivityTransferHandler.AcknowledgementSender
            acknowledgementSender;

    public CanonicalTombstoneTransferHandler(
            PhoneActivityRepository repository,
            CanonicalActivityTransferHandler.AcknowledgementSender
                    acknowledgementSender) {
        this.repository = Objects.requireNonNull(repository);
        this.acknowledgementSender = Objects.requireNonNull(acknowledgementSender);
    }

    public Result receive(
            String sourceNodeId,
            String channelPath,
            byte[] tombstoneBytes,
            long committedAtUtcMs) {
        if (sourceNodeId == null || sourceNodeId.isEmpty()) {
            throw new IllegalArgumentException("Source node identifier is required");
        }
        String pathTransferId =
                CanonicalSyncProtocol.transferIdFromTombstoneChannelPath(channelPath);
        CanonicalTransfers.VerifiedTombstone verified =
                CanonicalTransfers.parseTombstone(
                        Objects.requireNonNull(tombstoneBytes).clone());
        TombstoneRecord tombstone = verified.getTombstone();
        if (!pathTransferId.equals(tombstone.getTransferId())) {
            throw new CanonicalContractException(
                    "TRANSFER_PATH_MISMATCH",
                    "Tombstone channel path does not match its canonical transfer");
        }

        PhoneActivityRepository.TombstoneReceiveResult accepted =
                repository.acceptTombstoneTransfer(tombstoneBytes, committedAtUtcMs);
        verifyAcknowledgement(verified, accepted.getAcknowledgement());

        Exception deliveryFailure = null;
        try {
            acknowledgementSender.send(
                    sourceNodeId,
                    accepted.getExactAcknowledgementBytes());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            deliveryFailure = error;
        } catch (Exception error) {
            deliveryFailure = error;
        }
        return new Result(
                accepted.getAcknowledgement(),
                accepted.getExactAcknowledgementBytes(),
                accepted.isCreated(),
                deliveryFailure);
    }

    private static void verifyAcknowledgement(
            CanonicalTransfers.VerifiedTombstone verified,
            CommitAck acknowledgement) {
        TombstoneRecord tombstone = verified.getTombstone();
        if (!tombstone.getTransferId().equals(acknowledgement.getTransferId())
                || !tombstone.getSessionId().equals(acknowledgement.getSessionId())
                || !acknowledgement.hasDeleteRevision()
                || acknowledgement.getDeleteRevision()
                != tombstone.getDeleteRevision()
                || !acknowledgement.hasTombstoneOrigin()
                || acknowledgement.getTombstoneOrigin() != tombstone.getOrigin()
                || !MessageDigest.isEqual(
                verified.getSha256(),
                acknowledgement.getPayloadSha256().toByteArray())) {
            throw new CanonicalContractException(
                    "ACK_IDENTITY_MISMATCH",
                    "Stored acknowledgement does not match its accepted tombstone");
        }
    }

    public static final class Result {
        private final CommitAck acknowledgement;
        private final byte[] exactAcknowledgementBytes;
        private final boolean repositoryChanged;
        private final Exception acknowledgementDeliveryFailure;

        private Result(
                CommitAck acknowledgement,
                byte[] exactAcknowledgementBytes,
                boolean repositoryChanged,
                Exception acknowledgementDeliveryFailure) {
            this.acknowledgement = acknowledgement;
            this.exactAcknowledgementBytes = exactAcknowledgementBytes;
            this.repositoryChanged = repositoryChanged;
            this.acknowledgementDeliveryFailure = acknowledgementDeliveryFailure;
        }

        public CommitAck getAcknowledgement() {
            return acknowledgement;
        }

        public byte[] getExactAcknowledgementBytes() {
            return exactAcknowledgementBytes.clone();
        }

        public boolean isRepositoryChanged() {
            return repositoryChanged;
        }

        public boolean isAcknowledgementDelivered() {
            return acknowledgementDeliveryFailure == null;
        }

        public Exception getAcknowledgementDeliveryFailure() {
            return acknowledgementDeliveryFailure;
        }
    }
}
