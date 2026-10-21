package com.long2know.sportlogger.transfer;

import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository;
import com.long2know.utilities.contract.CanonicalContractException;
import com.long2know.utilities.contract.CanonicalTransfers;
import com.long2know.utilities.contract.proto.ActivityTransferFile;
import com.long2know.utilities.contract.proto.CommitAck;
import com.long2know.utilities.transfer.CanonicalSyncProtocol;

import java.security.MessageDigest;
import java.util.Objects;

public final class CanonicalActivityTransferHandler {
    public interface AcknowledgementSender {
        void send(String destinationNodeId, byte[] exactAcknowledgementBytes)
                throws Exception;
    }

    private final PhoneActivityRepository repository;
    private final AcknowledgementSender acknowledgementSender;

    public CanonicalActivityTransferHandler(
            PhoneActivityRepository repository,
            AcknowledgementSender acknowledgementSender) {
        this.repository = Objects.requireNonNull(repository);
        this.acknowledgementSender = Objects.requireNonNull(acknowledgementSender);
    }

    public Result receive(
            String sourceNodeId,
            String channelPath,
            byte[] transferBytes,
            long committedAtUtcMs) {
        if (sourceNodeId == null || sourceNodeId.isEmpty()) {
            throw new IllegalArgumentException("Source node identifier is required");
        }
        String pathTransferId =
                CanonicalSyncProtocol.transferIdFromActivityChannelPath(channelPath);
        CanonicalTransfers.VerifiedActivityTransfer verified =
                CanonicalTransfers.parseActivityTransfer(
                        Objects.requireNonNull(transferBytes).clone());
        ActivityTransferFile transfer = verified.getTransfer();
        if (!pathTransferId.equals(transfer.getTransferId())) {
            throw new CanonicalContractException(
                    "TRANSFER_PATH_MISMATCH",
                    "Activity channel path does not match its canonical transfer");
        }

        PhoneActivityRepository.AcceptResult accepted =
                repository.acceptActivityTransfer(transferBytes, committedAtUtcMs);
        verifyAcknowledgement(transfer, accepted.getAcknowledgement());

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
            ActivityTransferFile transfer,
            CommitAck acknowledgement) {
        if (!transfer.getTransferId().equals(acknowledgement.getTransferId())
                || !transfer.getSessionId().equals(acknowledgement.getSessionId())
                || !MessageDigest.isEqual(
                transfer.getPayloadSha256().toByteArray(),
                acknowledgement.getPayloadSha256().toByteArray())) {
            throw new CanonicalContractException(
                    "ACK_IDENTITY_MISMATCH",
                    "Stored acknowledgement does not match its accepted transfer");
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
