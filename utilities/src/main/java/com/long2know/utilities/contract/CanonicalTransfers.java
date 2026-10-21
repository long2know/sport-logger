package com.long2know.utilities.contract;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.long2know.utilities.contract.proto.AckResult;
import com.long2know.utilities.contract.proto.ActivityTransferFile;
import com.long2know.utilities.contract.proto.CommitAck;
import com.long2know.utilities.contract.proto.TombstoneOrigin;
import com.long2know.utilities.contract.proto.TombstoneRecord;

import java.security.MessageDigest;

public final class CanonicalTransfers {
    public static final int SCHEMA_VERSION = 1;

    private CanonicalTransfers() {
    }

    public static ActivityTransferFile activityTransfer(
            String transferId,
            CanonicalPayload payload) {
        CanonicalIds.requireUuid(transferId, "Transfer identifier");
        return ActivityTransferFile.newBuilder()
                .setSchemaVersion(SCHEMA_VERSION)
                .setTransferId(transferId)
                .setSessionId(payload.getActivity().getSessionId())
                .setSessionRevision(payload.getActivity().getWatchRevision())
                .setActivityFile(ByteString.copyFrom(payload.getExactBytes()))
                .setPayloadSha256(ByteString.copyFrom(payload.sha256()))
                .setPayloadLength(payload.getExactBytes().length)
                .build();
    }

    public static VerifiedActivityTransfer parseActivityTransfer(byte[] bytes) {
        try {
            ActivityTransferFile transfer = ActivityTransferFile.parseFrom(bytes);
            if (transfer.getSchemaVersion() != SCHEMA_VERSION) {
                throw new CanonicalContractException(
                        "TRANSFER_SCHEMA_UNSUPPORTED",
                        "Unsupported activity transfer schema " + transfer.getSchemaVersion());
            }
            CanonicalIds.requireUuid(transfer.getTransferId(), "Transfer identifier");
            CanonicalIds.requireUuid(transfer.getSessionId(), "Session identifier");
            byte[] payloadBytes = transfer.getActivityFile().toByteArray();
            if (transfer.getPayloadLength() != payloadBytes.length) {
                throw new CanonicalContractException(
                        "TRANSFER_LENGTH_MISMATCH",
                        "Activity transfer length does not match its payload");
            }
            byte[] hash = CanonicalPayload.parse(payloadBytes).sha256();
            if (!MessageDigest.isEqual(hash, transfer.getPayloadSha256().toByteArray())) {
                throw new CanonicalContractException(
                        "TRANSFER_HASH_MISMATCH",
                        "Activity transfer hash does not match its payload");
            }
            CanonicalPayload payload = CanonicalPayload.parse(payloadBytes);
            if (!payload.getActivity().getSessionId().equals(transfer.getSessionId())
                    || payload.getActivity().getWatchRevision()
                    != transfer.getSessionRevision()) {
                throw new CanonicalContractException(
                        "TRANSFER_IDENTITY_MISMATCH",
                        "Activity transfer metadata does not match its payload");
            }
            return new VerifiedActivityTransfer(transfer, payload, bytes.clone());
        } catch (InvalidProtocolBufferException error) {
            throw new CanonicalContractException(
                    "TRANSFER_CORRUPT",
                    "Activity transfer is not valid protobuf",
                    error);
        }
    }

    public static CommitAck commitAck(
            ActivityTransferFile transfer,
            long acceptedRevision,
            AckResult result,
            String errorCode,
            String errorMessage) {
        CommitAck.Builder builder = CommitAck.newBuilder()
                .setSchemaVersion(SCHEMA_VERSION)
                .setTransferId(transfer.getTransferId())
                .setSessionId(transfer.getSessionId())
                .setAcceptedSessionRevision(acceptedRevision)
                .setPayloadSha256(transfer.getPayloadSha256())
                .setResult(result);
        if (errorCode != null && !errorCode.isEmpty()) {
            builder.setErrorCode(errorCode);
        }
        if (errorMessage != null && !errorMessage.isEmpty()) {
            builder.setErrorMessage(errorMessage);
        }
        return builder.build();
    }

    public static TombstoneRecord tombstone(
            String transferId,
            String sessionId,
            long deleteRevision,
            long coveredSessionRevision,
            TombstoneOrigin origin,
            long deletedAtUtcMs) {
        CanonicalIds.requireUuid(transferId, "Transfer identifier");
        CanonicalIds.requireUuid(sessionId, "Session identifier");
        if (deleteRevision == 0L) {
            throw new CanonicalContractException(
                    "DELETE_REVISION_INVALID",
                    "Delete revision must be positive");
        }
        if (origin == TombstoneOrigin.TOMBSTONE_ORIGIN_UNSPECIFIED) {
            throw new CanonicalContractException(
                    "TOMBSTONE_ORIGIN_REQUIRED",
                    "Tombstone origin is required");
        }
        return TombstoneRecord.newBuilder()
                .setSchemaVersion(SCHEMA_VERSION)
                .setTransferId(transferId)
                .setSessionId(sessionId)
                .setDeleteRevision(deleteRevision)
                .setCoveredSessionRevision(coveredSessionRevision)
                .setOrigin(origin)
                .setDeletedAtUtcMs(deletedAtUtcMs)
                .build();
    }

    public static final class VerifiedActivityTransfer {
        private final ActivityTransferFile transfer;
        private final CanonicalPayload payload;
        private final byte[] exactBytes;

        private VerifiedActivityTransfer(
                ActivityTransferFile transfer,
                CanonicalPayload payload,
                byte[] exactBytes) {
            this.transfer = transfer;
            this.payload = payload;
            this.exactBytes = exactBytes;
        }

        public ActivityTransferFile getTransfer() {
            return transfer;
        }

        public CanonicalPayload getPayload() {
            return payload;
        }

        public byte[] getExactBytes() {
            return exactBytes.clone();
        }
    }
}
