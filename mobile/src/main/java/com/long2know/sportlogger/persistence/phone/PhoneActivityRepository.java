package com.long2know.sportlogger.persistence.phone;

import android.content.Context;
import android.database.Cursor;
import android.os.CancellationSignal;

import androidx.room.InvalidationTracker;
import androidx.sqlite.db.SimpleSQLiteQuery;

import com.google.protobuf.InvalidProtocolBufferException;
import com.long2know.utilities.contract.CanonicalContractException;
import com.long2know.utilities.contract.CanonicalIds;
import com.long2know.utilities.contract.CanonicalPayload;
import com.long2know.utilities.contract.CanonicalTransfers;
import com.long2know.utilities.contract.proto.AckResult;
import com.long2know.utilities.contract.proto.ActivityRecord;
import com.long2know.utilities.contract.proto.ActivityTransferFile;
import com.long2know.utilities.contract.proto.CommitAck;
import com.long2know.utilities.contract.proto.MeasurementSample;
import com.long2know.utilities.contract.proto.SourceKind;
import com.long2know.utilities.contract.proto.TombstoneOrigin;
import com.long2know.utilities.contract.proto.TombstoneRecord;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

public final class PhoneActivityRepository implements AutoCloseable {
    public static final String IMPORT_COMPLETE = "COMPLETE";
    public static final String IMPORT_PARTIAL = "PARTIAL";
    public static final String IMPORT_CORRUPT = "CORRUPT";
    public static final String IMPORT_UNSUPPORTED = "UNSUPPORTED";
    public static final String IMPORT_IO_FAILURE = "IO_FAILURE";
    public static final int MAX_TITLE_LENGTH = 200;
    public static final int MAX_NOTES_LENGTH = 10_000;
    public static final int MAX_TAG_COUNT = 50;
    public static final int MAX_TAG_LENGTH = 64;
    public static final String TOMBSTONE_READY = "READY";
    public static final String TOMBSTONE_SENDING = "SENDING";
    public static final String TOMBSTONE_AWAITING_COMMIT_ACK = "AWAITING_COMMIT_ACK";
    public static final String TOMBSTONE_COMMITTED = "COMMITTED";
    public static final String TOMBSTONE_RETRYABLE = "RETRYABLE";

    private final PhoneDatabase database;
    private final PhoneDao dao;
    private final boolean ownsDatabase;

    public PhoneActivityRepository(PhoneDatabase database) {
        this(database, false);
    }

    private PhoneActivityRepository(PhoneDatabase database, boolean ownsDatabase) {
        this.database = Objects.requireNonNull(database);
        this.dao = database.phoneDao();
        this.ownsDatabase = ownsDatabase;
    }

    public static PhoneActivityRepository open(Context context) {
        return new PhoneActivityRepository(PhoneDatabase.open(context), true);
    }

    public AcceptResult acceptActivityTransfer(byte[] transferBytes, long committedAtUtcMs) {
        CanonicalTransfers.VerifiedActivityTransfer verified =
                CanonicalTransfers.parseActivityTransfer(transferBytes);
        return database.runInTransaction(() -> acceptVerified(verified, committedAtUtcMs));
    }

    public List<ActivitySummary> summaries(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 500) {
            throw new IllegalArgumentException(
                    "Summary offset must be non-negative and limit between 1 and 500");
        }
        List<ActivitySummary> summaries = new ArrayList<>();
        for (PhoneSummaryRow row : dao.summaries(offset, limit)) {
            summaries.add(new ActivitySummary(
                    row.sessionId,
                    row.revision,
                    row.activityType,
                    row.startTimeUtcMs,
                    row.endTimeUtcMs,
                    row.elapsedDurationMs,
                    row.activeDurationMs,
                    row.distanceM,
                    row.sampleCount,
                    row.routePointCount,
                    row.heartRateSampleCount,
                    row.transferState,
                    row.lastTransferId,
                    row.importHealth,
                    row.title));
        }
        return Collections.unmodifiableList(summaries);
    }

    public long summaryCount() {
        return dao.summaryCount();
    }

    public ActivityDetail detail(String sessionId, long revision) {
        CanonicalIds.requireUuid(sessionId, "Session identifier");
        PhoneActivityRevisionEntity stored = dao.revision(sessionId, revision);
        if (stored == null) {
            return null;
        }
        CanonicalPayload payload = CanonicalPayload.parse(stored.payload);
        return new ActivityDetail(
                sessionId,
                revision,
                payload.getActivity(),
                payload.getExactBytes(),
                stored.payloadSha256,
                metadata(sessionId));
    }

    public PointPage pointPage(
            String sessionId,
            long revision,
            long afterSequence,
            int limit,
            CancellationSignal cancellationSignal) {
        CanonicalIds.requireUuid(sessionId, "Session identifier");
        if (limit < 1 || limit > 5_000) {
            throw new IllegalArgumentException("Point page limit must be between 1 and 5000");
        }
        String query = "SELECT sequence, payload FROM phone_points "
                + "WHERE session_id = ? AND revision = ? AND sequence > ? "
                + "ORDER BY sequence LIMIT ?";
        Object[] arguments = {sessionId, revision, afterSequence, limit + 1};
        List<MeasurementSample> samples = new ArrayList<>(limit);
        long nextSequence = afterSequence;
        boolean hasMore = false;
        try (Cursor cursor = database.getOpenHelper()
                .getReadableDatabase()
                .query(new SimpleSQLiteQuery(query, arguments), cancellationSignal)) {
            int sequenceColumn = cursor.getColumnIndexOrThrow("sequence");
            int payloadColumn = cursor.getColumnIndexOrThrow("payload");
            while (cursor.moveToNext()) {
                if (samples.size() == limit) {
                    hasMore = true;
                    break;
                }
                try {
                    MeasurementSample sample =
                            MeasurementSample.parseFrom(cursor.getBlob(payloadColumn));
                    samples.add(sample);
                    nextSequence = cursor.getLong(sequenceColumn);
                } catch (InvalidProtocolBufferException error) {
                    throw new CanonicalContractException(
                            "POINT_PAYLOAD_CORRUPT",
                            "Stored point payload is not valid protobuf",
                            error);
                }
            }
        }
        return new PointPage(samples, nextSequence, hasMore);
    }

    public Metadata metadata(String sessionId) {
        CanonicalIds.requireUuid(sessionId, "Session identifier");
        PhoneMetadataEntity entity = dao.metadata(sessionId);
        if (entity == null) {
            return Metadata.empty(sessionId);
        }
        return new Metadata(
                entity.sessionId,
                entity.title,
                entity.notes,
                decodeTags(entity.tagsText),
                entity.updatedAtUtcMs);
    }

    public void setMetadata(
            String sessionId,
            String title,
            String notes,
            List<String> tags,
            long updatedAtUtcMs) {
        CanonicalIds.requireUuid(sessionId, "Session identifier");
        validateLength(title, MAX_TITLE_LENGTH, "Title");
        validateLength(notes, MAX_NOTES_LENGTH, "Notes");
        PhoneMetadataEntity entity = new PhoneMetadataEntity();
        entity.sessionId = sessionId;
        entity.title = title;
        entity.notes = notes;
        entity.tagsText = encodeTags(tags);
        entity.updatedAtUtcMs = updatedAtUtcMs;
        dao.putMetadata(entity);
    }

    public AutoCloseable observeChanges(Executor executor, Runnable listener) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(listener);
        InvalidationTracker.Observer observer = new InvalidationTracker.Observer(
                "phone_session_heads",
                "phone_activity_revisions",
                "phone_inbox_receipts",
                "phone_metadata",
                "phone_tombstones",
                "phone_tombstone_outbox",
                "phone_tombstone_receipts") {
            @Override
            public void onInvalidated(Set<String> tables) {
                executor.execute(listener);
            }
        };
        database.getInvalidationTracker().addObserver(observer);
        return () -> database.getInvalidationTracker().removeObserver(observer);
    }

    public TombstoneRecord deleteLocal(
            String sessionId,
            long deleteRevision,
            long coveredRevision,
            String transferId,
            long deletedAtUtcMs) {
        TombstoneRecord tombstone = CanonicalTransfers.tombstone(
                transferId,
                sessionId,
                deleteRevision,
                coveredRevision,
                TombstoneOrigin.TOMBSTONE_ORIGIN_PHONE,
                deletedAtUtcMs);
        database.runInTransaction(() -> {
            byte[] bytes = tombstone.toByteArray();
            String hash = sha256Hex(bytes);
            PhoneTombstoneEntity stored = new PhoneTombstoneEntity();
            stored.sessionId = sessionId;
            stored.deleteRevision = deleteRevision;
            stored.coveredSessionRevision = coveredRevision;
            stored.origin = tombstone.getOriginValue();
            stored.transferId = transferId;
            stored.deletedAtUtcMs = deletedAtUtcMs;
            stored.payload = bytes;
            stored.payloadSha256 = hash;
            dao.putTombstone(stored);

            PhoneTombstoneOutboxEntity outbox = new PhoneTombstoneOutboxEntity();
            outbox.transferId = transferId;
            outbox.sessionId = sessionId;
            outbox.deleteRevision = deleteRevision;
            outbox.coveredSessionRevision = coveredRevision;
            outbox.origin = tombstone.getOriginValue();
            outbox.payload = bytes;
            outbox.payloadSha256 = hash;
            outbox.payloadLength = bytes.length;
            outbox.state = TOMBSTONE_READY;
            outbox.createdAtUtcMs = deletedAtUtcMs;
            dao.insertTombstoneOutbox(outbox);

            PhoneSessionHeadEntity head = dao.head(sessionId);
            if (head == null) {
                head = new PhoneSessionHeadEntity();
                head.sessionId = sessionId;
                head.acceptedRevision = coveredRevision;
                head.transferState = "TOMBSTONED";
                head.importHealth = IMPORT_COMPLETE;
            }
            head.deleted = true;
            head.transferState = "TOMBSTONED";
            head.updatedAtUtcMs = deletedAtUtcMs;
            dao.putHead(head);
        });
        return tombstone;
    }

    public TombstoneOutboxItem tombstoneOutbox(String transferId) {
        CanonicalIds.requireUuid(transferId, "Transfer identifier");
        PhoneTombstoneOutboxEntity entity = dao.tombstoneOutbox(transferId);
        return entity == null ? null : tombstoneOutbox(entity);
    }

    public List<TombstoneOutboxItem> pendingTombstoneOutbox(int limit) {
        validateOutboxLimit(limit);
        List<TombstoneOutboxItem> items = new ArrayList<>();
        for (PhoneTombstoneOutboxEntity entity : dao.pendingTombstoneOutbox(limit)) {
            items.add(tombstoneOutbox(entity));
        }
        return Collections.unmodifiableList(items);
    }

    public List<TombstoneOutboxItem> reclaimPendingTombstoneOutbox(
            int limit,
            long recoveredAtUtcMs) {
        validateOutboxLimit(limit);
        return database.runInTransaction(() -> {
            List<TombstoneOutboxItem> items = new ArrayList<>();
            for (PhoneTombstoneOutboxEntity entity : dao.pendingTombstoneOutbox(limit)) {
                if (TOMBSTONE_SENDING.equals(entity.state)) {
                    entity.state = TOMBSTONE_RETRYABLE;
                    entity.lastFailureCode = "PROCESS_RESTART";
                    entity.lastFailureMessage =
                            "Recovered tombstone that was sending when the process stopped";
                    entity.lastAttemptAtUtcMs = recoveredAtUtcMs;
                    if (dao.updateTombstoneOutbox(entity) != 1) {
                        throw new CanonicalContractException(
                                "TOMBSTONE_OUTBOX_UPDATE_FAILED",
                                "Recovered phone tombstone outbox was not updated");
                    }
                }
                items.add(tombstoneOutbox(entity));
            }
            return Collections.unmodifiableList(items);
        });
    }

    public TombstoneOutboxItem transitionTombstoneOutbox(
            String transferId,
            String expectedState,
            String nextState,
            Long attemptedAtUtcMs,
            String failureCode,
            String failureMessage) {
        return database.runInTransaction(() -> {
            CanonicalIds.requireUuid(transferId, "Transfer identifier");
            PhoneTombstoneOutboxEntity entity = dao.tombstoneOutbox(transferId);
            if (entity == null) {
                throw new CanonicalContractException(
                        "TOMBSTONE_OUTBOX_NOT_FOUND",
                        "Phone tombstone outbox transfer does not exist");
            }
            if (!entity.state.equals(expectedState)) {
                throw new CanonicalContractException(
                        "TOMBSTONE_OUTBOX_CAS_REJECTED",
                        "Phone tombstone transition was rejected by its state");
            }
            entity.state = nextState;
            if (attemptedAtUtcMs != null) {
                entity.lastAttemptAtUtcMs = attemptedAtUtcMs;
                entity.attemptCount++;
            }
            entity.lastFailureCode = failureCode;
            entity.lastFailureMessage = failureMessage;
            if (dao.updateTombstoneOutbox(entity) != 1) {
                throw new CanonicalContractException(
                        "TOMBSTONE_OUTBOX_UPDATE_FAILED",
                        "Phone tombstone outbox transition was not persisted");
            }
            return tombstoneOutbox(entity);
        });
    }

    public TombstoneAckAcceptance acceptTombstoneCommitAck(
            byte[] acknowledgementBytes,
            long committedAtUtcMs) {
        return database.runInTransaction(() -> {
            CommitAck acknowledgement = parseAck(acknowledgementBytes);
            PhoneTombstoneOutboxEntity entity =
                    dao.tombstoneOutbox(acknowledgement.getTransferId());
            if (entity == null) {
                throw new CanonicalContractException(
                        "TOMBSTONE_OUTBOX_NOT_FOUND",
                        "Acknowledged phone tombstone transfer does not exist");
            }
            if (!entity.sessionId.equals(acknowledgement.getSessionId())
                    || !acknowledgement.hasDeleteRevision()
                    || acknowledgement.getDeleteRevision() != entity.deleteRevision
                    || !acknowledgement.hasTombstoneOrigin()
                    || acknowledgement.getTombstoneOriginValue() != entity.origin
                    || !MessageDigest.isEqual(
                    hexBytes(entity.payloadSha256),
                    acknowledgement.getPayloadSha256().toByteArray())) {
                throw new CanonicalContractException(
                        "ACK_IDENTITY_MISMATCH",
                        "Phone tombstone acknowledgement does not match immutable outbox");
            }
            if (TOMBSTONE_COMMITTED.equals(entity.state)) {
                if (!MessageDigest.isEqual(entity.commitAckPayload, acknowledgementBytes)) {
                    throw new CanonicalContractException(
                            "ACK_CONFLICT",
                            "Committed phone tombstone stores different acknowledgement bytes");
                }
                return new TombstoneAckAcceptance(
                        tombstoneOutbox(entity),
                        acknowledgement,
                        false);
            }
            if (acknowledgement.getResult() != AckResult.ACK_RESULT_ACCEPTED
                    && acknowledgement.getResult() != AckResult.ACK_RESULT_DUPLICATE) {
                throw new CanonicalContractException(
                        "ACK_NOT_TERMINAL",
                        "Phone tombstone acknowledgement does not confirm watch commit");
            }
            entity.state = TOMBSTONE_COMMITTED;
            entity.commitAckPayload = acknowledgementBytes.clone();
            entity.committedAtUtcMs = committedAtUtcMs;
            entity.lastFailureCode = null;
            entity.lastFailureMessage = null;
            if (dao.updateTombstoneOutbox(entity) != 1) {
                throw new CanonicalContractException(
                        "TOMBSTONE_OUTBOX_UPDATE_FAILED",
                        "Committed phone tombstone outbox was not updated");
            }
            return new TombstoneAckAcceptance(
                    tombstoneOutbox(entity),
                    acknowledgement,
                    true);
        });
    }

    public TombstoneReceiveResult acceptTombstoneTransfer(
            byte[] tombstoneBytes,
            long committedAtUtcMs) {
        return database.runInTransaction(() -> {
            CanonicalTransfers.VerifiedTombstone verified =
                    CanonicalTransfers.parseTombstone(tombstoneBytes);
            TombstoneRecord incoming = verified.getTombstone();
            PhoneTombstoneReceiptEntity existing =
                    dao.tombstoneReceipt(incoming.getTransferId());
            if (existing != null) {
                if (!existing.payloadSha256.equals(verified.getSha256Hex())) {
                    throw new CanonicalContractException(
                            "TRANSFER_ID_REUSED",
                            "Tombstone transfer identifier already stores different bytes");
                }
                try {
                    return new TombstoneReceiveResult(
                            CommitAck.parseFrom(existing.ackPayload),
                            existing.ackPayload.clone(),
                            false);
                } catch (InvalidProtocolBufferException error) {
                    throw new CanonicalContractException(
                            "ACK_PAYLOAD_CORRUPT",
                            "Stored phone tombstone acknowledgement is corrupt",
                            error);
                }
            }
            PhoneTombstoneEntity current = dao.tombstone(incoming.getSessionId());
            boolean wins = current == null || CanonicalTransfers.compareTombstoneOrder(
                    incoming,
                    CanonicalTransfers.parseTombstone(current.payload).getTombstone()) > 0;
            AckResult result = wins
                    ? AckResult.ACK_RESULT_ACCEPTED
                    : AckResult.ACK_RESULT_STALE;
            if (wins) {
                PhoneTombstoneEntity stored = new PhoneTombstoneEntity();
                stored.sessionId = incoming.getSessionId();
                stored.deleteRevision = incoming.getDeleteRevision();
                stored.coveredSessionRevision = incoming.getCoveredSessionRevision();
                stored.origin = incoming.getOriginValue();
                stored.transferId = incoming.getTransferId();
                stored.deletedAtUtcMs = incoming.getDeletedAtUtcMs();
                stored.payload = verified.getExactBytes();
                stored.payloadSha256 = verified.getSha256Hex();
                dao.putTombstone(stored);
                PhoneSessionHeadEntity head = dao.head(incoming.getSessionId());
                if (head == null) {
                    head = new PhoneSessionHeadEntity();
                    head.sessionId = incoming.getSessionId();
                    head.acceptedRevision = incoming.getCoveredSessionRevision();
                    head.importHealth = IMPORT_COMPLETE;
                }
                if (head.acceptedRevision <= incoming.getCoveredSessionRevision()) {
                    head.deleted = true;
                    head.transferState = "TOMBSTONED";
                    head.updatedAtUtcMs = committedAtUtcMs;
                    dao.putHead(head);
                }
            }
            CommitAck acknowledgement = CanonicalTransfers.tombstoneCommitAck(
                    verified,
                    result,
                    null,
                    null);
            byte[] acknowledgementBytes = acknowledgement.toByteArray();
            PhoneTombstoneReceiptEntity receipt = new PhoneTombstoneReceiptEntity();
            receipt.transferId = incoming.getTransferId();
            receipt.sessionId = incoming.getSessionId();
            receipt.deleteRevision = incoming.getDeleteRevision();
            receipt.coveredSessionRevision = incoming.getCoveredSessionRevision();
            receipt.origin = incoming.getOriginValue();
            receipt.payloadSha256 = verified.getSha256Hex();
            receipt.committedAtUtcMs = committedAtUtcMs;
            receipt.ackPayload = acknowledgementBytes;
            dao.insertTombstoneReceipt(receipt);
            return new TombstoneReceiveResult(
                    acknowledgement,
                    acknowledgementBytes,
                    true);
        });
    }

    public void prepareConnectorDelivery(
            ConnectorKey key,
            String credentialReference,
            long artifactLength,
            String artifactSha256,
            long createdAtUtcMs) {
        validateConnectorKey(key);
        if (credentialReference == null || credentialReference.isEmpty()) {
            throw new IllegalArgumentException("Credential reference is required");
        }
        if (artifactLength < 0L || artifactSha256 == null || artifactSha256.length() != 64) {
            throw new IllegalArgumentException("Artifact length and SHA-256 are required");
        }
        database.runInTransaction(() -> {
            ConnectorLedgerEntity existing = connectorLedger(key);
            if (existing != null) {
                if (existing.artifactLength != artifactLength
                        || !existing.artifactSha256.equals(artifactSha256)
                        || !existing.credentialReference.equals(credentialReference)) {
                    throw new CanonicalContractException(
                            "CONNECTOR_ARTIFACT_CONFLICT",
                            "Connector ledger already has different immutable delivery inputs");
                }
                return;
            }
            if (dao.revision(key.sessionId, key.revision) == null) {
                throw new CanonicalContractException(
                        "ACTIVITY_REVISION_NOT_FOUND",
                        "Connector delivery requires an immutable stored activity revision");
            }
            ConnectorLedgerEntity ledger = new ConnectorLedgerEntity();
            applyKey(ledger, key);
            ledger.credentialReference = credentialReference;
            ledger.artifactLength = artifactLength;
            ledger.artifactSha256 = artifactSha256;
            ledger.state = ConnectorState.PENDING.name();
            ledger.createdAtUtcMs = createdAtUtcMs;
            ledger.updatedAtUtcMs = createdAtUtcMs;
            dao.insertConnectorLedger(ledger);
        });
    }

    public boolean claimConnectorDelivery(
            ConnectorKey key,
            ConnectorState expectedState,
            long leaseToken,
            String leaseOwner,
            long leaseExpiresAtUtcMs,
            long nowUtcMs) {
        validateConnectorKey(key);
        if (leaseToken == 0L || leaseOwner == null || leaseOwner.isEmpty()) {
            throw new IllegalArgumentException("Connector lease token and owner are required");
        }
        return dao.claimConnectorLedger(
                key.sessionId,
                key.revision,
                key.connectorId,
                key.normalizedInstanceUrl,
                key.connectionIdentity,
                expectedState.name(),
                leaseToken,
                leaseOwner,
                leaseExpiresAtUtcMs,
                nowUtcMs) == 1;
    }

    public void transitionConnectorDelivery(
            ConnectorKey key,
            long leaseToken,
            ConnectorState expectedState,
            ConnectorState nextState,
            Long retryNotBeforeUtcMs,
            String failureCode,
            String failureMessage,
            long nowUtcMs) {
        if (retryNotBeforeUtcMs != null) {
            long maximum = nowUtcMs + 7L * 24L * 60L * 60L * 1_000L;
            if (nextState != ConnectorState.PENDING
                    || retryNotBeforeUtcMs <= nowUtcMs
                    || retryNotBeforeUtcMs > maximum) {
                throw new IllegalArgumentException(
                        "Retry time is only valid for bounded pending delivery");
            }
        }
        database.runInTransaction(() -> {
            ConnectorLedgerEntity ledger = requireConnectorLedger(key);
            if (ledger.leaseToken != leaseToken || leaseToken == 0L
                    || !ledger.state.equals(expectedState.name())) {
                throw new CanonicalContractException(
                        "CONNECTOR_CAS_REJECTED",
                        "Connector transition was rejected by its lease or state");
            }
            ledger.state = nextState.name();
            ledger.retryNotBeforeUtcMs = retryNotBeforeUtcMs;
            ledger.lastFailureCode = failureCode;
            ledger.lastFailureMessage = failureMessage;
            ledger.leaseToken = 0L;
            ledger.leaseOwner = null;
            ledger.leaseExpiresAtUtcMs = null;
            ledger.updatedAtUtcMs = nowUtcMs;
            if (dao.updateConnectorLedger(ledger) != 1) {
                throw new CanonicalContractException(
                        "CONNECTOR_UPDATE_FAILED",
                        "Connector ledger transition did not update exactly one row");
            }
        });
    }

    public ConnectorDelivery recordConnectorSuccess(
            ConnectorKey key,
            long leaseToken,
            List<Long> returnedRemoteIds,
            long nowUtcMs) {
        if (returnedRemoteIds == null || returnedRemoteIds.isEmpty()) {
            throw new CanonicalContractException(
                    "REMOTE_ID_REQUIRED",
                    "Successful delivery must return at least one remote identifier");
        }
        return database.runInTransaction(() -> {
            ConnectorLedgerEntity ledger = requireConnectorLedger(key);
            if (ledger.leaseToken != leaseToken || leaseToken == 0L) {
                throw new CanonicalContractException(
                        "CONNECTOR_CAS_REJECTED",
                        "Connector success was rejected by its lease");
            }
            List<Long> before = dao.connectorRemoteIds(
                    key.sessionId,
                    key.revision,
                    key.connectorId,
                    key.normalizedInstanceUrl,
                    key.connectionIdentity);
            Set<Long> ids = new LinkedHashSet<>(before);
            for (Long remoteId : returnedRemoteIds) {
                if (remoteId == null || remoteId <= 0L) {
                    throw new CanonicalContractException(
                            "REMOTE_ID_INVALID",
                            "Connector remote identifiers must be positive integers");
                }
                ConnectorRemoteIdEntity entity = new ConnectorRemoteIdEntity();
                applyKey(entity, key);
                entity.remoteId = remoteId;
                entity.recordedAtUtcMs = nowUtcMs;
                dao.insertConnectorRemoteId(entity);
                ids.add(remoteId);
            }
            ledger.state = !before.isEmpty() && ids.size() > before.size()
                    ? ConnectorState.DUPLICATE_REVIEW.name()
                    : ConnectorState.DELIVERED.name();
            ledger.leaseToken = 0L;
            ledger.leaseOwner = null;
            ledger.leaseExpiresAtUtcMs = null;
            ledger.retryNotBeforeUtcMs = null;
            ledger.updatedAtUtcMs = nowUtcMs;
            if (dao.updateConnectorLedger(ledger) != 1) {
                throw new CanonicalContractException(
                        "CONNECTOR_UPDATE_FAILED",
                        "Connector success did not update exactly one row");
            }
            return connectorDelivery(ledger, new ArrayList<>(ids));
        });
    }

    public ConnectorDelivery connectorDelivery(ConnectorKey key) {
        ConnectorLedgerEntity ledger = connectorLedger(key);
        if (ledger == null) {
            return null;
        }
        return connectorDelivery(
                ledger,
                dao.connectorRemoteIds(
                        key.sessionId,
                        key.revision,
                        key.connectorId,
                        key.normalizedInstanceUrl,
                        key.connectionIdentity));
    }

    public ConnectorDelivery reconcileRemoteIds(
            ConnectorKey key,
            List<Long> confirmedRemoteIds,
            long nowUtcMs) {
        return database.runInTransaction(() -> {
            ConnectorLedgerEntity ledger = requireConnectorLedger(key);
            if (!ledger.state.equals(ConnectorState.UNKNOWN_DELIVERY.name())
                    && !ledger.state.equals(ConnectorState.DELIVERED.name())
                    && !ledger.state.equals(ConnectorState.DUPLICATE_REVIEW.name())) {
                throw new CanonicalContractException(
                        "CONNECTOR_STATE_INVALID",
                        "Remote reconciliation requires unknown, delivered, or duplicate state");
            }
            List<Long> before = dao.connectorRemoteIds(
                    key.sessionId,
                    key.revision,
                    key.connectorId,
                    key.normalizedInstanceUrl,
                    key.connectionIdentity);
            Set<Long> ids = new LinkedHashSet<>(before);
            for (Long remoteId : confirmedRemoteIds) {
                if (remoteId == null || remoteId <= 0L) {
                    throw new CanonicalContractException(
                            "REMOTE_ID_INVALID",
                            "Connector remote identifiers must be positive integers");
                }
                ConnectorRemoteIdEntity entity = new ConnectorRemoteIdEntity();
                applyKey(entity, key);
                entity.remoteId = remoteId;
                entity.recordedAtUtcMs = nowUtcMs;
                dao.insertConnectorRemoteId(entity);
                ids.add(remoteId);
            }
            if (ids.isEmpty()) {
                throw new CanonicalContractException(
                        "REMOTE_ID_REQUIRED",
                        "Reconciliation must confirm at least one remote identifier");
            }
            ledger.state = !before.isEmpty() && ids.size() > before.size()
                    ? ConnectorState.DUPLICATE_REVIEW.name()
                    : ConnectorState.DELIVERED.name();
            ledger.retryNotBeforeUtcMs = null;
            ledger.updatedAtUtcMs = nowUtcMs;
            if (dao.updateConnectorLedger(ledger) != 1) {
                throw new CanonicalContractException(
                        "CONNECTOR_UPDATE_FAILED",
                        "Connector reconciliation was not recorded");
            }
            return connectorDelivery(ledger, new ArrayList<>(ids));
        });
    }

    public void markConnectorDeleteUnsupported(ConnectorKey key, long nowUtcMs) {
        database.runInTransaction(() -> {
            ConnectorLedgerEntity ledger = requireConnectorLedger(key);
            ledger.deleteUnsupported = true;
            ledger.updatedAtUtcMs = nowUtcMs;
            if (dao.updateConnectorLedger(ledger) != 1) {
                throw new CanonicalContractException(
                        "CONNECTOR_UPDATE_FAILED",
                        "Connector delete capability was not recorded");
            }
        });
    }

    public void authorizeDuplicateRiskRetry(
            ConnectorKey key,
            long nowUtcMs) {
        database.runInTransaction(() -> {
            ConnectorLedgerEntity ledger = requireConnectorLedger(key);
            if (!ledger.state.equals(ConnectorState.UNKNOWN_DELIVERY.name())
                    && !ledger.state.equals(ConnectorState.DUPLICATE_REVIEW.name())) {
                throw new CanonicalContractException(
                        "CONNECTOR_STATE_INVALID",
                        "Duplicate-risk retry requires unknown delivery or duplicate review");
            }
            ledger.state = ConnectorState.PENDING.name();
            ledger.retryNotBeforeUtcMs = null;
            ledger.updatedAtUtcMs = nowUtcMs;
            if (dao.updateConnectorLedger(ledger) != 1) {
                throw new CanonicalContractException(
                        "CONNECTOR_UPDATE_FAILED",
                        "Duplicate-risk retry authorization was not recorded");
            }
        });
    }

    PhoneLegacyBackfillReceiptEntity legacyBackfillReceipt(String sourceFileKey) {
        return dao.legacyBackfillReceipt(sourceFileKey);
    }

    void insertLegacyBackfillReceipt(PhoneLegacyBackfillReceiptEntity receipt) {
        dao.insertLegacyBackfillReceipt(receipt);
    }

    void markImportHealth(
            String sessionId,
            String health,
            String errorCode,
            long updatedAtUtcMs) {
        PhoneSessionHeadEntity head = dao.head(sessionId);
        if (head == null) {
            throw new CanonicalContractException(
                    "SESSION_NOT_FOUND",
                    "Cannot update import health for an unknown session");
        }
        head.importHealth = health;
        head.importErrorCode = errorCode;
        head.updatedAtUtcMs = updatedAtUtcMs;
        dao.putHead(head);
    }

    private AcceptResult acceptVerified(
            CanonicalTransfers.VerifiedActivityTransfer verified,
            long committedAtUtcMs) {
        ActivityTransferFile transfer = verified.getTransfer();
        String payloadHash = CanonicalPayload.hex(transfer.getPayloadSha256().toByteArray());
        PhoneInboxReceiptEntity existingReceipt = dao.inboxReceipt(transfer.getTransferId());
        if (existingReceipt != null) {
            if (!existingReceipt.payloadSha256.equals(payloadHash)
                    || existingReceipt.payloadLength != transfer.getPayloadLength()
                    || !existingReceipt.sessionId.equals(transfer.getSessionId())
                    || existingReceipt.incomingRevision != transfer.getSessionRevision()) {
                throw new CanonicalContractException(
                        "TRANSFER_ID_REUSED",
                        "Transfer identifier already stores different activity data");
            }
            try {
                return new AcceptResult(
                        CommitAck.parseFrom(existingReceipt.ackPayload),
                        existingReceipt.ackPayload.clone(),
                        false);
            } catch (InvalidProtocolBufferException error) {
                throw new CanonicalContractException(
                        "ACK_PAYLOAD_CORRUPT",
                        "Stored acknowledgement is not valid protobuf",
                        error);
            }
        }

        PhoneSessionHeadEntity head = dao.head(transfer.getSessionId());
        PhoneTombstoneEntity tombstone = dao.tombstone(transfer.getSessionId());
        AckResult result;
        long acceptedRevision = head == null ? 0L : head.acceptedRevision;
        if (tombstone != null
                && transfer.getSessionRevision() <= tombstone.coveredSessionRevision) {
            result = AckResult.ACK_RESULT_TOMBSTONED;
        } else {
            PhoneActivityRevisionEntity sameRevision =
                    dao.revision(transfer.getSessionId(), transfer.getSessionRevision());
            if (sameRevision != null && !sameRevision.payloadSha256.equals(payloadHash)) {
                throw new CanonicalContractException(
                        "REVISION_PAYLOAD_CONFLICT",
                        "Session revision already stores different activity data");
            }
            if (head == null || transfer.getSessionRevision() > head.acceptedRevision) {
                if (sameRevision == null) {
                    insertRevision(verified.getPayload(), committedAtUtcMs);
                }
                if (head == null) {
                    head = new PhoneSessionHeadEntity();
                    head.sessionId = transfer.getSessionId();
                }
                head.acceptedRevision = transfer.getSessionRevision();
                head.deleted = false;
                head.transferState = "COMMITTED";
                head.lastTransferId = transfer.getTransferId();
                head.importHealth = importHealth(verified.getPayload().getActivity());
                head.importErrorCode = null;
                head.updatedAtUtcMs = committedAtUtcMs;
                dao.putHead(head);
                acceptedRevision = transfer.getSessionRevision();
                result = AckResult.ACK_RESULT_ACCEPTED;
            } else if (transfer.getSessionRevision() == head.acceptedRevision) {
                acceptedRevision = head.acceptedRevision;
                result = AckResult.ACK_RESULT_DUPLICATE;
            } else {
                acceptedRevision = head.acceptedRevision;
                result = AckResult.ACK_RESULT_STALE;
            }
        }

        CommitAck.Builder ackBuilder = CanonicalTransfers.commitAck(
                transfer,
                acceptedRevision,
                result,
                null,
                null).toBuilder();
        if (result == AckResult.ACK_RESULT_TOMBSTONED && tombstone != null) {
            ackBuilder.setDeleteRevision(tombstone.deleteRevision);
            ackBuilder.setTombstoneOriginValue(tombstone.origin);
        }
        CommitAck ack = ackBuilder.build();
        byte[] ackBytes = ack.toByteArray();
        PhoneInboxReceiptEntity receipt = new PhoneInboxReceiptEntity();
        receipt.transferId = transfer.getTransferId();
        receipt.sessionId = transfer.getSessionId();
        receipt.incomingRevision = transfer.getSessionRevision();
        receipt.acceptedRevision = acceptedRevision;
        receipt.payloadSha256 = payloadHash;
        receipt.payloadLength = transfer.getPayloadLength();
        receipt.result = result.name();
        receipt.committedAtUtcMs = committedAtUtcMs;
        receipt.ackPayload = ackBytes;
        dao.insertInboxReceipt(receipt);
        return new AcceptResult(ack, ackBytes, true);
    }

    private void insertRevision(CanonicalPayload payload, long acceptedAtUtcMs) {
        ActivityRecord activity = payload.getActivity();
        PhoneActivityRevisionEntity revision = new PhoneActivityRevisionEntity();
        revision.sessionId = activity.getSessionId();
        revision.revision = activity.getWatchRevision();
        revision.activityType = activity.getActivityTypeValue();
        revision.startTimeUtcMs = activity.getStartTimeUtcMs();
        revision.endTimeUtcMs = activity.getEndTimeUtcMs();
        revision.elapsedDurationMs = activity.getElapsedDurationMs();
        revision.activeDurationMs =
                activity.hasActiveDurationMs() ? activity.getActiveDurationMs() : null;
        if (activity.hasSummary()) {
            if (activity.getSummary().hasDistanceM()
                    && activity.getSummary().getDistanceM().hasValue()) {
                revision.distanceM = activity.getSummary().getDistanceM().getValue();
            }
            if (activity.getSummary().hasCaloriesKcal()
                    && activity.getSummary().getCaloriesKcal().hasValue()) {
                revision.caloriesKcal = activity.getSummary().getCaloriesKcal().getValue();
            }
            if (activity.getSummary().hasSteps()
                    && activity.getSummary().getSteps().hasValue()) {
                revision.steps = activity.getSummary().getSteps().getValue();
            }
        }
        long routeCount = 0L;
        long heartRateCount = 0L;
        for (MeasurementSample sample : activity.getSamplesList()) {
            if (sample.hasLocation() && sample.getLocation().hasValue()) {
                routeCount++;
            }
            if (sample.hasHeartRateBpm() && sample.getHeartRateBpm().hasValue()) {
                heartRateCount++;
            }
        }
        revision.sampleCount = activity.getSamplesCount();
        revision.routePointCount = routeCount;
        revision.heartRateSampleCount = heartRateCount;
        revision.timeZoneId = activity.hasTimeZoneId() ? activity.getTimeZoneId() : null;
        revision.utcOffsetMinutes = activity.hasUtcOffsetMinutesAtStart()
                ? activity.getUtcOffsetMinutesAtStart()
                : null;
        revision.sourceKind = activity.getSource().getKindValue();
        revision.sourceProvider =
                activity.getSource().hasProvider() ? activity.getSource().getProvider() : null;
        revision.sourceManufacturer = activity.getSource().hasManufacturer()
                ? activity.getSource().getManufacturer()
                : null;
        revision.sourceModel =
                activity.getSource().hasModel() ? activity.getSource().getModel() : null;
        revision.sourceDeviceId =
                activity.getSource().hasDeviceId() ? activity.getSource().getDeviceId() : null;
        revision.payload = payload.getExactBytes();
        revision.payloadSha256 = payload.sha256Hex();
        revision.acceptedAtUtcMs = acceptedAtUtcMs;
        dao.insertRevision(revision);

        for (MeasurementSample sample : activity.getSamplesList()) {
            PhonePointEntity point = new PhonePointEntity();
            point.sessionId = activity.getSessionId();
            point.revision = activity.getWatchRevision();
            point.sequence = sample.getSequence();
            point.timestampUtcMs = sample.getTimestampUtcMs();
            point.hasLocation = sample.hasLocation() && sample.getLocation().hasValue();
            point.hasHeartRate =
                    sample.hasHeartRateBpm() && sample.getHeartRateBpm().hasValue();
            point.payload = sample.toByteArray();
            point.payloadSha256 = sha256Hex(point.payload);
            dao.insertPoint(point);
        }
    }

    private ConnectorLedgerEntity connectorLedger(ConnectorKey key) {
        validateConnectorKey(key);
        return dao.connectorLedger(
                key.sessionId,
                key.revision,
                key.connectorId,
                key.normalizedInstanceUrl,
                key.connectionIdentity);
    }

    private ConnectorLedgerEntity requireConnectorLedger(ConnectorKey key) {
        ConnectorLedgerEntity ledger = connectorLedger(key);
        if (ledger == null) {
            throw new CanonicalContractException(
                    "CONNECTOR_LEDGER_NOT_FOUND",
                    "Connector delivery has not been prepared");
        }
        return ledger;
    }

    private static void validateConnectorKey(ConnectorKey key) {
        Objects.requireNonNull(key);
        CanonicalIds.requireUuid(key.sessionId, "Session identifier");
        if (key.revision == 0L
                || isEmpty(key.connectorId)
                || isEmpty(key.normalizedInstanceUrl)
                || isEmpty(key.connectionIdentity)) {
            throw new IllegalArgumentException("Complete connector identity is required");
        }
    }

    private static void applyKey(ConnectorLedgerEntity entity, ConnectorKey key) {
        entity.sessionId = key.sessionId;
        entity.sessionRevision = key.revision;
        entity.connectorId = key.connectorId;
        entity.normalizedInstanceUrl = key.normalizedInstanceUrl;
        entity.connectionIdentity = key.connectionIdentity;
    }

    private static void applyKey(ConnectorRemoteIdEntity entity, ConnectorKey key) {
        entity.sessionId = key.sessionId;
        entity.sessionRevision = key.revision;
        entity.connectorId = key.connectorId;
        entity.normalizedInstanceUrl = key.normalizedInstanceUrl;
        entity.connectionIdentity = key.connectionIdentity;
    }

    private static ConnectorDelivery connectorDelivery(
            ConnectorLedgerEntity ledger,
            List<Long> remoteIds) {
        return new ConnectorDelivery(
                new ConnectorKey(
                        ledger.sessionId,
                        ledger.sessionRevision,
                        ledger.connectorId,
                        ledger.normalizedInstanceUrl,
                        ledger.connectionIdentity),
                ConnectorState.valueOf(ledger.state),
                ledger.artifactLength,
                ledger.artifactSha256,
                ledger.credentialReference,
                Collections.unmodifiableList(new ArrayList<>(remoteIds)),
                ledger.retryNotBeforeUtcMs,
                ledger.deleteUnsupported);
    }

    private static String importHealth(ActivityRecord activity) {
        SourceKind source = activity.getSource().getKind();
        if (source == SourceKind.SOURCE_KIND_LEGACY_SQLITE
                || source == SourceKind.SOURCE_KIND_LEGACY_SERIALIZED) {
            return IMPORT_COMPLETE;
        }
        return IMPORT_COMPLETE;
    }

    private static String encodeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        if (tags.size() > MAX_TAG_COUNT) {
            throw new IllegalArgumentException(
                    "At most " + MAX_TAG_COUNT + " tags are supported");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String tag : tags) {
            if (tag == null || tag.isEmpty() || tag.contains("\n") || tag.contains("\r")) {
                throw new IllegalArgumentException("Tags must be non-empty single-line values");
            }
            if (tag.length() > MAX_TAG_LENGTH) {
                throw new IllegalArgumentException(
                        "Tags cannot exceed " + MAX_TAG_LENGTH + " characters");
            }
            unique.add(tag);
        }
        return String.join("\n", unique);
    }

    private static List<String> decodeTags(String tagsText) {
        if (tagsText == null || tagsText.isEmpty()) {
            return Collections.emptyList();
        }
        String[] values = tagsText.split("\n", -1);
        List<String> tags = new ArrayList<>(values.length);
        Collections.addAll(tags, values);
        return Collections.unmodifiableList(tags);
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static void validateLength(String value, int maximum, String label) {
        if (value != null && value.length() > maximum) {
            throw new IllegalArgumentException(
                    label + " cannot exceed " + maximum + " characters");
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return CanonicalPayload.hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static CommitAck parseAck(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new CanonicalContractException(
                    "ACK_REQUIRED",
                    "Acknowledgement bytes are required");
        }
        try {
            CommitAck acknowledgement = CommitAck.parseFrom(bytes);
            if (acknowledgement.getSchemaVersion() != CanonicalTransfers.SCHEMA_VERSION) {
                throw new CanonicalContractException(
                        "ACK_SCHEMA_UNSUPPORTED",
                        "Unsupported acknowledgement schema");
            }
            CanonicalIds.requireUuid(
                    acknowledgement.getTransferId(),
                    "Transfer identifier");
            CanonicalIds.requireUuid(
                    acknowledgement.getSessionId(),
                    "Session identifier");
            return acknowledgement;
        } catch (InvalidProtocolBufferException error) {
            throw new CanonicalContractException(
                    "ACK_CORRUPT",
                    "Acknowledgement is not valid protobuf",
                    error);
        }
    }

    private static TombstoneOutboxItem tombstoneOutbox(
            PhoneTombstoneOutboxEntity entity) {
        return new TombstoneOutboxItem(
                entity.transferId,
                entity.sessionId,
                entity.deleteRevision,
                entity.coveredSessionRevision,
                entity.origin,
                entity.payload.clone(),
                entity.payloadSha256,
                entity.payloadLength,
                entity.state,
                entity.attemptCount,
                entity.commitAckPayload == null ? null : entity.commitAckPayload.clone(),
                entity.committedAtUtcMs);
    }

    private static byte[] hexBytes(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int index = 0; index < value.length(); index += 2) {
            bytes[index / 2] = (byte) Integer.parseInt(
                    value.substring(index, index + 2),
                    16);
        }
        return bytes;
    }

    private static void validateOutboxLimit(int limit) {
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException(
                    "Tombstone outbox limit must be between 1 and 1000");
        }
    }

    @Override
    public void close() {
        if (ownsDatabase) {
            database.close();
        }
    }

    public enum ConnectorState {
        PENDING,
        DELIVERED,
        UNKNOWN_DELIVERY,
        DUPLICATE_REVIEW,
        FAILED
    }

    public static final class TombstoneOutboxItem {
        private final String transferId;
        private final String sessionId;
        private final long deleteRevision;
        private final long coveredSessionRevision;
        private final int origin;
        private final byte[] payload;
        private final String payloadSha256;
        private final long payloadLength;
        private final String state;
        private final long attemptCount;
        private final byte[] commitAckPayload;
        private final Long committedAtUtcMs;

        private TombstoneOutboxItem(
                String transferId,
                String sessionId,
                long deleteRevision,
                long coveredSessionRevision,
                int origin,
                byte[] payload,
                String payloadSha256,
                long payloadLength,
                String state,
                long attemptCount,
                byte[] commitAckPayload,
                Long committedAtUtcMs) {
            this.transferId = transferId;
            this.sessionId = sessionId;
            this.deleteRevision = deleteRevision;
            this.coveredSessionRevision = coveredSessionRevision;
            this.origin = origin;
            this.payload = payload;
            this.payloadSha256 = payloadSha256;
            this.payloadLength = payloadLength;
            this.state = state;
            this.attemptCount = attemptCount;
            this.commitAckPayload = commitAckPayload;
            this.committedAtUtcMs = committedAtUtcMs;
        }

        public String getTransferId() {
            return transferId;
        }

        public String getSessionId() {
            return sessionId;
        }

        public long getDeleteRevision() {
            return deleteRevision;
        }

        public long getCoveredSessionRevision() {
            return coveredSessionRevision;
        }

        public int getOrigin() {
            return origin;
        }

        public byte[] getPayload() {
            return payload.clone();
        }

        public String getPayloadSha256() {
            return payloadSha256;
        }

        public long getPayloadLength() {
            return payloadLength;
        }

        public String getState() {
            return state;
        }

        public long getAttemptCount() {
            return attemptCount;
        }

        public byte[] getCommitAckPayload() {
            return commitAckPayload == null ? null : commitAckPayload.clone();
        }

        public Long getCommittedAtUtcMs() {
            return committedAtUtcMs;
        }
    }

    public static final class TombstoneAckAcceptance {
        private final TombstoneOutboxItem outbox;
        private final CommitAck acknowledgement;
        private final boolean committedNow;

        private TombstoneAckAcceptance(
                TombstoneOutboxItem outbox,
                CommitAck acknowledgement,
                boolean committedNow) {
            this.outbox = outbox;
            this.acknowledgement = acknowledgement;
            this.committedNow = committedNow;
        }

        public TombstoneOutboxItem getOutbox() {
            return outbox;
        }

        public CommitAck getAcknowledgement() {
            return acknowledgement;
        }

        public boolean isCommittedNow() {
            return committedNow;
        }
    }

    public static final class TombstoneReceiveResult {
        private final CommitAck acknowledgement;
        private final byte[] exactAcknowledgementBytes;
        private final boolean created;

        private TombstoneReceiveResult(
                CommitAck acknowledgement,
                byte[] exactAcknowledgementBytes,
                boolean created) {
            this.acknowledgement = acknowledgement;
            this.exactAcknowledgementBytes = exactAcknowledgementBytes;
            this.created = created;
        }

        public CommitAck getAcknowledgement() {
            return acknowledgement;
        }

        public byte[] getExactAcknowledgementBytes() {
            return exactAcknowledgementBytes.clone();
        }

        public boolean isCreated() {
            return created;
        }
    }

    public static final class AcceptResult {
        private final CommitAck acknowledgement;
        private final byte[] exactAcknowledgementBytes;
        private final boolean created;

        private AcceptResult(
                CommitAck acknowledgement,
                byte[] exactAcknowledgementBytes,
                boolean created) {
            this.acknowledgement = acknowledgement;
            this.exactAcknowledgementBytes = exactAcknowledgementBytes;
            this.created = created;
        }

        public CommitAck getAcknowledgement() {
            return acknowledgement;
        }

        public byte[] getExactAcknowledgementBytes() {
            return exactAcknowledgementBytes.clone();
        }

        public boolean isCreated() {
            return created;
        }
    }

    public static final class ActivitySummary {
        private final String sessionId;
        private final long revision;
        private final int activityType;
        private final long startTimeUtcMs;
        private final long endTimeUtcMs;
        private final long elapsedDurationMs;
        private final Long activeDurationMs;
        private final Double distanceM;
        private final long sampleCount;
        private final long routePointCount;
        private final long heartRateSampleCount;
        private final String transferState;
        private final String lastTransferId;
        private final String importHealth;
        private final String title;

        private ActivitySummary(
                String sessionId,
                long revision,
                int activityType,
                long startTimeUtcMs,
                long endTimeUtcMs,
                long elapsedDurationMs,
                Long activeDurationMs,
                Double distanceM,
                long sampleCount,
                long routePointCount,
                long heartRateSampleCount,
                String transferState,
                String lastTransferId,
                String importHealth,
                String title) {
            this.sessionId = sessionId;
            this.revision = revision;
            this.activityType = activityType;
            this.startTimeUtcMs = startTimeUtcMs;
            this.endTimeUtcMs = endTimeUtcMs;
            this.elapsedDurationMs = elapsedDurationMs;
            this.activeDurationMs = activeDurationMs;
            this.distanceM = distanceM;
            this.sampleCount = sampleCount;
            this.routePointCount = routePointCount;
            this.heartRateSampleCount = heartRateSampleCount;
            this.transferState = transferState;
            this.lastTransferId = lastTransferId;
            this.importHealth = importHealth;
            this.title = title;
        }

        public String getSessionId() {
            return sessionId;
        }

        public long getRevision() {
            return revision;
        }

        public int getActivityType() {
            return activityType;
        }

        public long getStartTimeUtcMs() {
            return startTimeUtcMs;
        }

        public long getEndTimeUtcMs() {
            return endTimeUtcMs;
        }

        public long getElapsedDurationMs() {
            return elapsedDurationMs;
        }

        public Long getActiveDurationMs() {
            return activeDurationMs;
        }

        public Double getDistanceM() {
            return distanceM;
        }

        public long getSampleCount() {
            return sampleCount;
        }

        public long getRoutePointCount() {
            return routePointCount;
        }

        public long getHeartRateSampleCount() {
            return heartRateSampleCount;
        }

        public String getTransferState() {
            return transferState;
        }

        public String getLastTransferId() {
            return lastTransferId;
        }

        public String getImportHealth() {
            return importHealth;
        }

        public String getTitle() {
            return title;
        }
    }

    public static final class ActivityDetail {
        private final String sessionId;
        private final long revision;
        private final ActivityRecord activity;
        private final byte[] exactPayload;
        private final String payloadSha256;
        private final Metadata metadata;

        private ActivityDetail(
                String sessionId,
                long revision,
                ActivityRecord activity,
                byte[] exactPayload,
                String payloadSha256,
                Metadata metadata) {
            this.sessionId = sessionId;
            this.revision = revision;
            this.activity = activity;
            this.exactPayload = exactPayload;
            this.payloadSha256 = payloadSha256;
            this.metadata = metadata;
        }

        public String getSessionId() {
            return sessionId;
        }

        public long getRevision() {
            return revision;
        }

        public ActivityRecord getActivity() {
            return activity;
        }

        public byte[] getExactPayload() {
            return exactPayload.clone();
        }

        public String getPayloadSha256() {
            return payloadSha256;
        }

        public Metadata getMetadata() {
            return metadata;
        }
    }

    public static final class PointPage {
        private final List<MeasurementSample> samples;
        private final long nextSequence;
        private final boolean hasMore;

        private PointPage(
                List<MeasurementSample> samples,
                long nextSequence,
                boolean hasMore) {
            this.samples = Collections.unmodifiableList(new ArrayList<>(samples));
            this.nextSequence = nextSequence;
            this.hasMore = hasMore;
        }

        public List<MeasurementSample> getSamples() {
            return samples;
        }

        public long getNextSequence() {
            return nextSequence;
        }

        public boolean hasMore() {
            return hasMore;
        }
    }

    public static final class Metadata {
        private final String sessionId;
        private final String title;
        private final String notes;
        private final List<String> tags;
        private final long updatedAtUtcMs;

        private Metadata(
                String sessionId,
                String title,
                String notes,
                List<String> tags,
                long updatedAtUtcMs) {
            this.sessionId = sessionId;
            this.title = title;
            this.notes = notes;
            this.tags = tags;
            this.updatedAtUtcMs = updatedAtUtcMs;
        }

        private static Metadata empty(String sessionId) {
            return new Metadata(
                    sessionId,
                    null,
                    null,
                    Collections.emptyList(),
                    0L);
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getTitle() {
            return title;
        }

        public String getNotes() {
            return notes;
        }

        public List<String> getTags() {
            return tags;
        }

        public long getUpdatedAtUtcMs() {
            return updatedAtUtcMs;
        }
    }

    public static final class ConnectorKey {
        private final String sessionId;
        private final long revision;
        private final String connectorId;
        private final String normalizedInstanceUrl;
        private final String connectionIdentity;

        public ConnectorKey(
                String sessionId,
                long revision,
                String connectorId,
                String normalizedInstanceUrl,
                String connectionIdentity) {
            this.sessionId = sessionId;
            this.revision = revision;
            this.connectorId = connectorId;
            this.normalizedInstanceUrl = normalizedInstanceUrl;
            this.connectionIdentity = connectionIdentity;
        }

        public String getSessionId() {
            return sessionId;
        }

        public long getRevision() {
            return revision;
        }

        public String getConnectorId() {
            return connectorId;
        }

        public String getNormalizedInstanceUrl() {
            return normalizedInstanceUrl;
        }

        public String getConnectionIdentity() {
            return connectionIdentity;
        }
    }

    public static final class ConnectorDelivery {
        private final ConnectorKey key;
        private final ConnectorState state;
        private final long artifactLength;
        private final String artifactSha256;
        private final String credentialReference;
        private final List<Long> remoteIds;
        private final Long retryNotBeforeUtcMs;
        private final boolean deleteUnsupported;

        private ConnectorDelivery(
                ConnectorKey key,
                ConnectorState state,
                long artifactLength,
                String artifactSha256,
                String credentialReference,
                List<Long> remoteIds,
                Long retryNotBeforeUtcMs,
                boolean deleteUnsupported) {
            this.key = key;
            this.state = state;
            this.artifactLength = artifactLength;
            this.artifactSha256 = artifactSha256;
            this.credentialReference = credentialReference;
            this.remoteIds = remoteIds;
            this.retryNotBeforeUtcMs = retryNotBeforeUtcMs;
            this.deleteUnsupported = deleteUnsupported;
        }

        public ConnectorKey getKey() {
            return key;
        }

        public ConnectorState getState() {
            return state;
        }

        public long getArtifactLength() {
            return artifactLength;
        }

        public String getArtifactSha256() {
            return artifactSha256;
        }

        public String getCredentialReference() {
            return credentialReference;
        }

        public List<Long> getRemoteIds() {
            return remoteIds;
        }

        public Long getRetryNotBeforeUtcMs() {
            return retryNotBeforeUtcMs;
        }

        public boolean isDeleteUnsupported() {
            return deleteUnsupported;
        }
    }
}
