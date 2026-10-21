package com.long2know.utilities.contract;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.long2know.utilities.contract.proto.AckResult;
import com.long2know.utilities.contract.proto.ActivityTransferFile;
import com.long2know.utilities.contract.proto.CommitAck;
import com.long2know.utilities.contract.proto.TombstoneOrigin;
import com.long2know.utilities.contract.proto.TombstoneRecord;

import java.security.MessageDigest;
import java.util.UUID;

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

    public static VerifiedTombstone parseTombstone(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new CanonicalContractException(
                    "TOMBSTONE_REQUIRED",
                    "Tombstone bytes are required");
        }
        try {
            TombstoneRecord tombstone = TombstoneRecord.parseFrom(bytes);
            if (tombstone.getSchemaVersion() != SCHEMA_VERSION) {
                throw new CanonicalContractException(
                        "TOMBSTONE_SCHEMA_UNSUPPORTED",
                        "Unsupported tombstone schema " + tombstone.getSchemaVersion());
            }
            CanonicalIds.requireUuid(tombstone.getTransferId(), "Transfer identifier");
            CanonicalIds.requireUuid(tombstone.getSessionId(), "Session identifier");
            if (tombstone.getDeleteRevision() == 0L
                    || tombstone.getOrigin()
                    == TombstoneOrigin.TOMBSTONE_ORIGIN_UNSPECIFIED) {
                throw new CanonicalContractException(
                        "TOMBSTONE_INVALID",
                        "Tombstone delete revision and origin are required");
            }
            return new VerifiedTombstone(
                    tombstone,
                    bytes.clone(),
                    sha256(bytes));
        } catch (InvalidProtocolBufferException error) {
            throw new CanonicalContractException(
                    "TOMBSTONE_CORRUPT",
                    "Tombstone is not valid protobuf",
                    error);
        }
    }

    public static CommitAck tombstoneCommitAck(
            VerifiedTombstone verified,
            AckResult result,
            String errorCode,
            String errorMessage) {
        TombstoneRecord tombstone = verified.getTombstone();
        CommitAck.Builder builder = CommitAck.newBuilder()
                .setSchemaVersion(SCHEMA_VERSION)
                .setTransferId(tombstone.getTransferId())
                .setSessionId(tombstone.getSessionId())
                .setAcceptedSessionRevision(tombstone.getCoveredSessionRevision())
                .setPayloadSha256(ByteString.copyFrom(verified.getSha256()))
                .setResult(result)
                .setDeleteRevision(tombstone.getDeleteRevision())
                .setTombstoneOrigin(tombstone.getOrigin());
        if (errorCode != null && !errorCode.isEmpty()) {
            builder.setErrorCode(errorCode);
        }
        if (errorMessage != null && !errorMessage.isEmpty()) {
            builder.setErrorMessage(errorMessage);
        }
        return builder.build();
    }

    public static int compareTombstoneOrder(
            TombstoneRecord left,
            TombstoneRecord right) {
        int revision = Long.compareUnsigned(
                left.getDeleteRevision(),
                right.getDeleteRevision());
        if (revision != 0) {
            return revision;
        }
        int origin = Integer.compare(originRank(left.getOrigin()), originRank(right.getOrigin()));
        if (origin != 0) {
            return origin;
        }
        byte[] leftUuid = uuidBytes(UUID.fromString(left.getTransferId()));
        byte[] rightUuid = uuidBytes(UUID.fromString(right.getTransferId()));
        for (int index = 0; index < leftUuid.length; index++) {
            int comparison = Integer.compare(
                    leftUuid[index] & 0xff,
                    rightUuid[index] & 0xff);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static int originRank(TombstoneOrigin origin) {
        if (origin == TombstoneOrigin.TOMBSTONE_ORIGIN_WATCH) {
            return 0;
        }
        if (origin == TombstoneOrigin.TOMBSTONE_ORIGIN_PHONE) {
            return 1;
        }
        throw new CanonicalContractException(
                "TOMBSTONE_ORIGIN_REQUIRED",
                "Tombstone origin is required");
    }

    private static byte[] uuidBytes(UUID uuid) {
        return java.nio.ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
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

    public static final class VerifiedTombstone {
        private final TombstoneRecord tombstone;
        private final byte[] exactBytes;
        private final byte[] sha256;

        private VerifiedTombstone(
                TombstoneRecord tombstone,
                byte[] exactBytes,
                byte[] sha256) {
            this.tombstone = tombstone;
            this.exactBytes = exactBytes;
            this.sha256 = sha256;
        }

        public TombstoneRecord getTombstone() {
            return tombstone;
        }

        public byte[] getExactBytes() {
            return exactBytes.clone();
        }

        public byte[] getSha256() {
            return sha256.clone();
        }

        public String getSha256Hex() {
            return CanonicalPayload.hex(sha256);
        }
    }
}
