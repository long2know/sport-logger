package com.long2know.sportlogger.persistence.watch;

import android.content.Context;

import androidx.room.InvalidationTracker;

import com.google.protobuf.InvalidProtocolBufferException;
import com.long2know.utilities.contract.CanonicalActivityValidator;
import com.long2know.utilities.contract.CanonicalContractException;
import com.long2know.utilities.contract.CanonicalIds;
import com.long2know.utilities.contract.CanonicalPayload;
import com.long2know.utilities.contract.CanonicalTransfers;
import com.long2know.utilities.contract.proto.ActivityRecord;
import com.long2know.utilities.contract.proto.ActivityTransferFile;
import com.long2know.utilities.contract.proto.ActivityType;
import com.long2know.utilities.contract.proto.AckResult;
import com.long2know.utilities.contract.proto.CommitAck;
import com.long2know.utilities.contract.proto.MeasurementSample;
import com.long2know.utilities.contract.proto.TombstoneRecord;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WatchActivityRepository implements AutoCloseable {
    public static final String OUTBOX_READY = "READY";
    public static final String OUTBOX_SENDING = "SENDING";
    public static final String OUTBOX_AWAITING_COMMIT_ACK = "AWAITING_COMMIT_ACK";
    public static final String OUTBOX_COMMITTED = "COMMITTED";
    public static final String OUTBOX_RETRYABLE = "RETRYABLE";

    private final WatchDatabase database;
    private final WatchDao dao;
    private final Executor writeExecutor;
    private final boolean ownsDatabase;
    private final ExecutorService ownedExecutor;

    public WatchActivityRepository(
            WatchDatabase database,
            Executor writeExecutor) {
        this(database, writeExecutor, false);
    }

    private WatchActivityRepository(
            WatchDatabase database,
            Executor writeExecutor,
            boolean ownsDatabase) {
        this.database = Objects.requireNonNull(database);
        this.dao = database.watchDao();
        this.writeExecutor = Objects.requireNonNull(writeExecutor);
        this.ownsDatabase = ownsDatabase;
        this.ownedExecutor = ownsDatabase && writeExecutor instanceof ExecutorService
                ? (ExecutorService) writeExecutor
                : null;
    }

    public static WatchActivityRepository open(Context context) {
        return new WatchActivityRepository(
                WatchDatabase.open(context),
                Executors.newSingleThreadExecutor(),
                true);
    }

    public CompletableFuture<SessionSnapshot> startSession(StartRequest request) {
        Objects.requireNonNull(request);
        return CompletableFuture.supplyAsync(() -> database.runInTransaction(() -> {
            CanonicalIds.requireUuid(request.sessionId, "Session identifier");
            if (request.leaseToken == 0L) {
                throw new CanonicalContractException(
                        "LEASE_REQUIRED",
                        "A non-zero recording lease is required");
            }
            WatchSessionEntity existing = dao.session(request.sessionId);
            if (existing != null) {
                if (sameStart(existing, request)) {
                    return snapshot(existing);
                }
                throw new CanonicalContractException(
                        "START_REPLAY_CONFLICT",
                        "Session UUID already stores different start metadata or lease");
            }
            WatchSessionEntity session = new WatchSessionEntity();
            session.sessionId = request.sessionId;
            session.watchRevision = 0L;
            session.state = LifecycleState.RECORDING.name();
            session.activityType = request.activityType.getNumber();
            session.startedAtUtcMs = request.startedAtUtcMs;
            session.timeZoneId = request.timeZoneId;
            session.utcOffsetMinutes = request.utcOffsetMinutes;
            session.bootId = request.bootId;
            session.monotonicAnchorMs = request.monotonicAnchorMs;
            session.activeSinceMonotonicMs = request.monotonicAnchorMs;
            session.accumulatedActiveMs = request.accumulatedActiveMs;
            session.leaseToken = request.leaseToken;
            session.legacySourceKey = request.legacySourceKey;
            session.legacyActivityId = request.legacyActivityId;
            session.createdAtUtcMs = request.startedAtUtcMs;
            session.updatedAtUtcMs = request.startedAtUtcMs;
            dao.insertSession(session);
            return snapshot(session);
        }), writeExecutor);
    }

    public CompletableFuture<OutboxItem> outboxForSessionRevision(
            String sessionId,
            long revision) {
        return CompletableFuture.supplyAsync(() -> {
            CanonicalIds.requireUuid(sessionId, "Session identifier");
            WatchOutboxEntity entity = dao.outboxForRevision(sessionId, revision);
            return entity == null ? null : outbox(entity);
        }, writeExecutor);
    }

    public CompletableFuture<Boolean> writeSample(
            String sessionId,
            long leaseToken,
            MeasurementSample sample) {
        return CompletableFuture.supplyAsync(() ->
                database.runInTransaction(() -> writeSampleInternal(
                        sessionId,
                        leaseToken,
                        sample)), writeExecutor);
    }

    public CompletableFuture<SessionSnapshot> transition(
            String sessionId,
            long leaseToken,
            LifecycleState state,
            String bootId,
            Long activeSinceMonotonicMs,
            long accumulatedActiveMs,
            String errorCode,
            String errorMessage,
            long updatedAtUtcMs) {
        return CompletableFuture.supplyAsync(() -> {
            int updated = dao.updateLifecycle(
                    CanonicalIds.requireUuid(sessionId, "Session identifier"),
                    requireLease(leaseToken),
                    Objects.requireNonNull(state).name(),
                    bootId,
                    activeSinceMonotonicMs,
                    accumulatedActiveMs,
                    errorCode,
                    errorMessage,
                    updatedAtUtcMs);
            if (updated != 1) {
                throw new CanonicalContractException(
                        "SESSION_FENCE_REJECTED",
                        "Session transition was rejected by its lease or terminal state");
            }
            return snapshot(requireSession(sessionId));
        }, writeExecutor);
    }

    public CompletableFuture<CompletionResult> finishSession(
            String sessionId,
            long leaseToken,
            ActivityRecord activity,
            String transferId,
            long completedAtUtcMs) {
        return CompletableFuture.supplyAsync(() ->
                database.runInTransaction(() -> finishInternal(
                        sessionId,
                        leaseToken,
                        activity,
                        transferId,
                        completedAtUtcMs,
                        false,
                        null,
                        null)), writeExecutor);
    }

    public CompletableFuture<SessionSnapshot> session(String sessionId) {
        return CompletableFuture.supplyAsync(
                () -> snapshot(requireSession(
                        CanonicalIds.requireUuid(sessionId, "Session identifier"))),
                writeExecutor);
    }

    public CompletableFuture<List<OutboxItem>> readyOutbox(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            if (limit < 1 || limit > 1_000) {
                throw new IllegalArgumentException("Outbox limit must be between 1 and 1000");
            }
            List<OutboxItem> items = new ArrayList<>();
            for (WatchOutboxEntity entity : dao.readyOutbox(limit)) {
                items.add(outbox(entity));
            }
            return Collections.unmodifiableList(items);
        }, writeExecutor);
    }

    public CompletableFuture<OutboxItem> outbox(String transferId) {
        return CompletableFuture.supplyAsync(() -> {
            CanonicalIds.requireUuid(transferId, "Transfer identifier");
            WatchOutboxEntity entity = dao.outbox(transferId);
            return entity == null ? null : outbox(entity);
        }, writeExecutor);
    }

    public CompletableFuture<List<OutboxItem>> pendingOutbox(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            validateLimit(limit);
            List<OutboxItem> items = new ArrayList<>();
            for (WatchOutboxEntity entity : dao.pendingOutbox(limit)) {
                items.add(outbox(entity));
            }
            return Collections.unmodifiableList(items);
        }, writeExecutor);
    }

    public CompletableFuture<List<OutboxItem>> reclaimPendingOutbox(
            int limit,
            long recoveredAtUtcMs) {
        return CompletableFuture.supplyAsync(() -> database.runInTransaction(() -> {
            validateLimit(limit);
            List<OutboxItem> items = new ArrayList<>();
            for (WatchOutboxEntity entity : dao.pendingOutbox(limit)) {
                if (OUTBOX_SENDING.equals(entity.state)) {
                    entity.state = OUTBOX_RETRYABLE;
                    entity.lastFailureCode = "PROCESS_RESTART";
                    entity.lastFailureMessage =
                            "Recovered transfer that was sending when the process stopped";
                    entity.lastAttemptAtUtcMs = recoveredAtUtcMs;
                    if (dao.updateOutbox(entity) != 1) {
                        throw new CanonicalContractException(
                                "OUTBOX_UPDATE_FAILED",
                                "Recovered activity outbox was not updated");
                    }
                }
                items.add(outbox(entity));
            }
            return Collections.unmodifiableList(items);
        }), writeExecutor);
    }

    public CompletableFuture<SessionSnapshot> reserveCompletion(
            String sessionId,
            long leaseToken,
            long revision,
            String transferId,
            long updatedAtUtcMs) {
        return CompletableFuture.supplyAsync(() -> database.runInTransaction(() -> {
            CanonicalIds.requireUuid(sessionId, "Session identifier");
            CanonicalIds.requireUuid(transferId, "Transfer identifier");
            if (revision <= 0L) {
                throw new CanonicalContractException(
                        "REVISION_INVALID",
                        "Completion revision must be positive");
            }
            WatchSessionEntity session = requireSession(sessionId);
            if (session.leaseToken != requireLease(leaseToken)) {
                throw new CanonicalContractException(
                        "SESSION_FENCE_REJECTED",
                        "Completion reservation was rejected by its recording lease");
            }
            if (session.completionTransferId != null
                    && (!session.completionTransferId.equals(transferId)
                    || !Objects.equals(session.completionRevision, revision))) {
                throw new CanonicalContractException(
                        "COMPLETION_RESERVATION_CONFLICT",
                        "Session already reserves a different completion identity");
            }
            session.completionTransferId = transferId;
            session.completionRevision = revision;
            session.state = LifecycleState.ENDING.name();
            session.updatedAtUtcMs = updatedAtUtcMs;
            if (dao.updateSession(session) != 1) {
                throw new CanonicalContractException(
                        "SESSION_UPDATE_FAILED",
                        "Completion reservation was not persisted");
            }
            return snapshot(session);
        }), writeExecutor);
    }

    public CompletableFuture<CompletionResult> finishSessionFromPersistedSamples(
            String sessionId,
            long leaseToken,
            ActivityRecord finalMetadata,
            long completedAtUtcMs) {
        return CompletableFuture.supplyAsync(() -> database.runInTransaction(() -> {
            WatchSessionEntity session = requireSession(
                    CanonicalIds.requireUuid(sessionId, "Session identifier"));
            if (session.leaseToken != requireLease(leaseToken)) {
                throw new CanonicalContractException(
                        "SESSION_FENCE_REJECTED",
                        "Completion was rejected by its recording lease");
            }
            if (session.completionTransferId == null
                    || session.completionRevision == null) {
                throw new CanonicalContractException(
                        "COMPLETION_NOT_RESERVED",
                        "Completion identity must be durably reserved before finalization");
            }
            ActivityRecord activity = finalMetadata.toBuilder()
                    .setSessionId(sessionId)
                    .setWatchRevision(session.completionRevision)
                    .clearSamples()
                    .addAllSamples(samplesInternal(sessionId))
                    .build();
            return finishInternal(
                    sessionId,
                    leaseToken,
                    activity,
                    session.completionTransferId,
                    completedAtUtcMs,
                    false,
                    null,
                    null);
        }), writeExecutor);
    }

    public CompletableFuture<List<MeasurementSample>> samples(String sessionId) {
        return CompletableFuture.supplyAsync(() ->
                Collections.unmodifiableList(new ArrayList<>(
                        samplesInternal(CanonicalIds.requireUuid(
                                sessionId,
                                "Session identifier")))), writeExecutor);
    }

    public CompletableFuture<OutboxItem> transitionOutbox(
            String transferId,
            String expectedState,
            String nextState,
            Long attemptedAtUtcMs,
            String failureCode,
            String failureMessage) {
        return CompletableFuture.supplyAsync(() -> database.runInTransaction(() -> {
            CanonicalIds.requireUuid(transferId, "Transfer identifier");
            WatchOutboxEntity entity = dao.outbox(transferId);
            if (entity == null) {
                throw new CanonicalContractException(
                        "OUTBOX_NOT_FOUND",
                        "Activity outbox transfer does not exist");
            }
            if (!entity.state.equals(expectedState)) {
                throw new CanonicalContractException(
                        "OUTBOX_CAS_REJECTED",
                        "Activity outbox transition was rejected by its state");
            }
            entity.state = nextState;
            if (attemptedAtUtcMs != null) {
                entity.lastAttemptAtUtcMs = attemptedAtUtcMs;
                entity.attemptCount++;
            }
            entity.lastFailureCode = failureCode;
            entity.lastFailureMessage = failureMessage;
            if (dao.updateOutbox(entity) != 1) {
                throw new CanonicalContractException(
                        "OUTBOX_UPDATE_FAILED",
                        "Activity outbox transition did not update exactly one row");
            }
            return outbox(entity);
        }), writeExecutor);
    }

    public CompletableFuture<AckAcceptance> acceptCommitAck(
            byte[] acknowledgementBytes,
            long committedAtUtcMs) {
        return CompletableFuture.supplyAsync(() -> database.runInTransaction(() -> {
            CommitAck acknowledgement = parseAck(acknowledgementBytes);
            WatchOutboxEntity entity = dao.outbox(acknowledgement.getTransferId());
            if (entity == null) {
                throw new CanonicalContractException(
                        "OUTBOX_NOT_FOUND",
                        "Acknowledged activity transfer does not exist");
            }
            ActivityTransferFile transfer =
                    CanonicalTransfers.parseActivityTransfer(entity.payload).getTransfer();
            validateActivityAck(entity, transfer, acknowledgement);
            if (OUTBOX_COMMITTED.equals(entity.state)) {
                if (!MessageDigest.isEqual(entity.commitAckPayload, acknowledgementBytes)) {
                    throw new CanonicalContractException(
                            "ACK_CONFLICT",
                            "Committed transfer already stores different acknowledgement bytes");
                }
                return new AckAcceptance(outbox(entity), acknowledgement, false);
            }
            if (!terminalActivityAck(acknowledgement.getResult())) {
                throw new CanonicalContractException(
                        "ACK_NOT_TERMINAL",
                        "Activity acknowledgement does not terminate retry");
            }
            entity.state = OUTBOX_COMMITTED;
            entity.commitAckPayload = acknowledgementBytes.clone();
            entity.committedAtUtcMs = committedAtUtcMs;
            entity.lastFailureCode = null;
            entity.lastFailureMessage = null;
            if (dao.updateOutbox(entity) != 1) {
                throw new CanonicalContractException(
                        "OUTBOX_UPDATE_FAILED",
                        "Committed activity outbox was not updated");
            }
            return new AckAcceptance(outbox(entity), acknowledgement, true);
        }), writeExecutor);
    }

    public CompletableFuture<List<TombstoneOutboxItem>> pendingTombstoneOutbox(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            validateLimit(limit);
            List<TombstoneOutboxItem> items = new ArrayList<>();
            for (WatchTombstoneOutboxEntity entity : dao.pendingTombstoneOutbox(limit)) {
                items.add(tombstoneOutbox(entity));
            }
            return Collections.unmodifiableList(items);
        }, writeExecutor);
    }

    public CompletableFuture<TombstoneOutboxItem> tombstoneOutbox(String transferId) {
        return CompletableFuture.supplyAsync(() -> {
            CanonicalIds.requireUuid(transferId, "Transfer identifier");
            WatchTombstoneOutboxEntity entity = dao.tombstoneOutbox(transferId);
            return entity == null ? null : tombstoneOutbox(entity);
        }, writeExecutor);
    }

    public CompletableFuture<TombstoneOutboxItem> transitionTombstoneOutbox(
            String transferId,
            String expectedState,
            String nextState,
            Long attemptedAtUtcMs,
            String failureCode,
            String failureMessage) {
        return CompletableFuture.supplyAsync(() -> database.runInTransaction(() -> {
            CanonicalIds.requireUuid(transferId, "Transfer identifier");
            WatchTombstoneOutboxEntity entity = dao.tombstoneOutbox(transferId);
            if (entity == null) {
                throw new CanonicalContractException(
                        "TOMBSTONE_OUTBOX_NOT_FOUND",
                        "Tombstone outbox transfer does not exist");
            }
            if (!entity.state.equals(expectedState)) {
                throw new CanonicalContractException(
                        "TOMBSTONE_OUTBOX_CAS_REJECTED",
                        "Tombstone outbox transition was rejected by its state");
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
                        "Tombstone outbox transition did not update exactly one row");
            }
            return tombstoneOutbox(entity);
        }), writeExecutor);
    }

    public CompletableFuture<TombstoneAckAcceptance> acceptTombstoneCommitAck(
            byte[] acknowledgementBytes,
            long committedAtUtcMs) {
        return CompletableFuture.supplyAsync(() -> database.runInTransaction(() -> {
            CommitAck acknowledgement = parseAck(acknowledgementBytes);
            WatchTombstoneOutboxEntity entity =
                    dao.tombstoneOutbox(acknowledgement.getTransferId());
            if (entity == null) {
                throw new CanonicalContractException(
                        "TOMBSTONE_OUTBOX_NOT_FOUND",
                        "Acknowledged tombstone transfer does not exist");
            }
            validateTombstoneAck(entity, acknowledgement);
            if (OUTBOX_COMMITTED.equals(entity.state)) {
                if (!MessageDigest.isEqual(entity.commitAckPayload, acknowledgementBytes)) {
                    throw new CanonicalContractException(
                            "ACK_CONFLICT",
                            "Committed tombstone already stores different acknowledgement bytes");
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
                        "Tombstone acknowledgement does not confirm receiver commit");
            }
            entity.state = OUTBOX_COMMITTED;
            entity.commitAckPayload = acknowledgementBytes.clone();
            entity.committedAtUtcMs = committedAtUtcMs;
            entity.lastFailureCode = null;
            entity.lastFailureMessage = null;
            if (dao.updateTombstoneOutbox(entity) != 1) {
                throw new CanonicalContractException(
                        "TOMBSTONE_OUTBOX_UPDATE_FAILED",
                        "Committed tombstone outbox was not updated");
            }
            return new TombstoneAckAcceptance(
                    tombstoneOutbox(entity),
                    acknowledgement,
                    true);
        }), writeExecutor);
    }

    public CompletableFuture<TombstoneReceiveResult> acceptTombstoneTransfer(
            byte[] tombstoneBytes,
            long committedAtUtcMs) {
        return CompletableFuture.supplyAsync(() -> database.runInTransaction(() -> {
            CanonicalTransfers.VerifiedTombstone verified =
                    CanonicalTransfers.parseTombstone(tombstoneBytes);
            TombstoneRecord incoming = verified.getTombstone();
            WatchTombstoneReceiptEntity existing =
                    dao.tombstoneReceipt(incoming.getTransferId());
            if (existing != null) {
                if (!existing.payloadSha256.equals(verified.getSha256Hex())) {
                    throw new CanonicalContractException(
                            "TRANSFER_ID_REUSED",
                            "Tombstone transfer identifier already stores different bytes");
                }
                try {
                    CommitAck ack = CommitAck.parseFrom(existing.ackPayload);
                    return new TombstoneReceiveResult(ack, existing.ackPayload.clone(), false);
                } catch (InvalidProtocolBufferException error) {
                    throw new CanonicalContractException(
                            "ACK_PAYLOAD_CORRUPT",
                            "Stored tombstone acknowledgement is corrupt",
                            error);
                }
            }
            WatchTombstoneEntity current = dao.tombstone(incoming.getSessionId());
            boolean wins = current == null || CanonicalTransfers.compareTombstoneOrder(
                    incoming,
                    CanonicalTransfers.parseTombstone(current.payload).getTombstone()) > 0;
            AckResult result = wins
                    ? AckResult.ACK_RESULT_ACCEPTED
                    : AckResult.ACK_RESULT_STALE;
            if (wins) {
                dao.putTombstone(tombstoneEntity(
                        incoming,
                        verified.getExactBytes(),
                        verified.getSha256Hex()));
                WatchSessionEntity session = dao.session(incoming.getSessionId());
                if (session != null
                        && session.watchRevision <= incoming.getCoveredSessionRevision()) {
                    session.state = LifecycleState.DISCARDED.name();
                    session.leaseToken = 0L;
                    session.updatedAtUtcMs = committedAtUtcMs;
                    if (dao.updateSession(session) != 1) {
                        throw new CanonicalContractException(
                                "SESSION_UPDATE_FAILED",
                                "Tombstoned watch session was not updated");
                    }
                }
                dao.suppressCoveredActivityOutbox(
                        incoming.getSessionId(),
                        incoming.getCoveredSessionRevision());
            }
            CommitAck ack = CanonicalTransfers.tombstoneCommitAck(
                    verified,
                    result,
                    null,
                    null);
            byte[] ackBytes = ack.toByteArray();
            WatchTombstoneReceiptEntity receipt = new WatchTombstoneReceiptEntity();
            receipt.transferId = incoming.getTransferId();
            receipt.sessionId = incoming.getSessionId();
            receipt.deleteRevision = incoming.getDeleteRevision();
            receipt.coveredSessionRevision = incoming.getCoveredSessionRevision();
            receipt.origin = incoming.getOriginValue();
            receipt.payloadSha256 = verified.getSha256Hex();
            receipt.committedAtUtcMs = committedAtUtcMs;
            receipt.ackPayload = ackBytes;
            dao.insertTombstoneReceipt(receipt);
            return new TombstoneReceiveResult(ack, ackBytes, true);
        }), writeExecutor);
    }

    public AutoCloseable observeOutbox(Executor executor, Runnable listener) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(listener);
        InvalidationTracker.Observer observer = new InvalidationTracker.Observer(
                "watch_activity_outbox",
                "watch_tombstone_outbox") {
            @Override
            public void onInvalidated(java.util.Set<String> tables) {
                executor.execute(listener);
            }
        };
        database.getInvalidationTracker().addObserver(observer);
        return () -> database.getInvalidationTracker().removeObserver(observer);
    }

    public CompletableFuture<Void> discardSession(
            String sessionId,
            long leaseToken,
            TombstoneRecord tombstone,
            long createdAtUtcMs) {
        return CompletableFuture.runAsync(() -> database.runInTransaction(() -> {
            WatchSessionEntity session = requireSession(
                    CanonicalIds.requireUuid(sessionId, "Session identifier"));
            if (session.leaseToken != requireLease(leaseToken)) {
                throw new CanonicalContractException(
                        "SESSION_FENCE_REJECTED",
                        "Discard was rejected by its recording lease");
            }
            if (!session.sessionId.equals(tombstone.getSessionId())) {
                throw new CanonicalContractException(
                        "TOMBSTONE_IDENTITY_MISMATCH",
                        "Tombstone session does not match the discarded session");
            }
            byte[] bytes = tombstone.toByteArray();
            String hash = sha256Hex(bytes);
            WatchTombstoneEntity stored = tombstoneEntity(tombstone, bytes, hash);
            dao.putTombstone(stored);
            WatchTombstoneOutboxEntity outbox = new WatchTombstoneOutboxEntity();
            outbox.transferId = tombstone.getTransferId();
            outbox.sessionId = tombstone.getSessionId();
            outbox.deleteRevision = tombstone.getDeleteRevision();
            outbox.coveredSessionRevision = tombstone.getCoveredSessionRevision();
            outbox.origin = tombstone.getOriginValue();
            outbox.payload = bytes;
            outbox.payloadSha256 = hash;
            outbox.payloadLength = bytes.length;
            outbox.state = OUTBOX_READY;
            outbox.createdAtUtcMs = createdAtUtcMs;
            dao.insertTombstoneOutbox(outbox);
            session.state = LifecycleState.DISCARDED.name();
            session.leaseToken = 0L;
            session.updatedAtUtcMs = createdAtUtcMs;
            if (dao.updateSession(session) != 1) {
                throw new CanonicalContractException(
                        "SESSION_UPDATE_FAILED",
                        "Discarded session was not updated");
            }
        }), writeExecutor);
    }

    CompletionResult importCompletedSession(
            ActivityRecord activity,
            String transferId,
            long completedAtUtcMs,
            String legacySourceKey,
            long legacyActivityId) {
        return database.runInTransaction(() -> finishInternal(
                activity.getSessionId(),
                0L,
                activity,
                transferId,
                completedAtUtcMs,
                true,
                legacySourceKey,
                legacyActivityId));
    }

    WatchMigrationReceiptEntity migrationReceipt(String receiptId) {
        return dao.migrationReceipt(receiptId);
    }

    void insertMigrationReceipt(WatchMigrationReceiptEntity receipt) {
        dao.insertMigrationReceipt(receipt);
    }

    void quarantine(WatchMigrationQuarantineEntity quarantine) {
        dao.insertQuarantine(quarantine);
    }

    long quarantineCount(String sourceKey) {
        return dao.quarantineCount(sourceKey);
    }

    private CompletionResult finishInternal(
            String sessionId,
            long leaseToken,
            ActivityRecord activity,
            String transferId,
            long completedAtUtcMs,
            boolean legacyImport,
            String legacySourceKey,
            Long legacyActivityId) {
        CanonicalActivityValidator.validateCompleted(activity);
        CanonicalIds.requireUuid(sessionId, "Session identifier");
        CanonicalIds.requireUuid(transferId, "Transfer identifier");
        if (!sessionId.equals(activity.getSessionId())) {
            throw new CanonicalContractException(
                    "SESSION_IDENTITY_MISMATCH",
                    "Completion activity does not match the requested session");
        }
        CanonicalPayload payload = CanonicalPayload.create(activity);
        ActivityTransferFile transfer = CanonicalTransfers.activityTransfer(transferId, payload);
        byte[] transferBytes = transfer.toByteArray();
        String transferHash = sha256Hex(transferBytes);
        WatchOutboxEntity existingOutbox = dao.outbox(transferId);
        if (existingOutbox != null) {
            if (!MessageDigest.isEqual(existingOutbox.payload, transferBytes)) {
                throw new CanonicalContractException(
                        "TRANSFER_ID_REUSED",
                        "Transfer identifier already has different bytes");
            }
            WatchSessionEntity existing = requireSession(sessionId);
            return new CompletionResult(snapshot(existing), outbox(existingOutbox), false);
        }

        WatchSessionEntity session = dao.session(sessionId);
        if (session == null) {
            if (!legacyImport) {
                throw new CanonicalContractException(
                        "SESSION_NOT_FOUND",
                        "Session must be started before completion");
            }
            session = new WatchSessionEntity();
            session.sessionId = sessionId;
            session.createdAtUtcMs = activity.getStartTimeUtcMs();
            session.legacySourceKey = legacySourceKey;
            session.legacyActivityId = legacyActivityId;
            dao.insertSession(sessionFromActivity(session, activity, payload, completedAtUtcMs));
            session = requireSession(sessionId);
        } else {
            if (!legacyImport && session.leaseToken != requireLease(leaseToken)) {
                throw new CanonicalContractException(
                        "SESSION_FENCE_REJECTED",
                        "Completion was rejected by its recording lease");
            }
            if (activity.getWatchRevision() <= session.watchRevision) {
                throw new CanonicalContractException(
                        "REVISION_STALE",
                        "Completion revision must increase");
            }
            if (session.completionTransferId == null) {
                session.completionTransferId = transferId;
                session.completionRevision = activity.getWatchRevision();
            } else if (!session.completionTransferId.equals(transferId)
                    || !Objects.equals(
                    session.completionRevision,
                    activity.getWatchRevision())) {
                throw new CanonicalContractException(
                        "COMPLETION_RESERVATION_CONFLICT",
                        "Completion does not match the durably reserved identity");
            }
            for (MeasurementSample sample : activity.getSamplesList()) {
                writeSampleInternal(sessionId, leaseToken, sample, legacyImport);
            }
            sessionFromActivity(session, activity, payload, completedAtUtcMs);
            if (dao.updateSession(session) != 1) {
                throw new CanonicalContractException(
                        "SESSION_UPDATE_FAILED",
                        "Completed session was not updated");
            }
        }

        if (legacyImport) {
            for (MeasurementSample sample : activity.getSamplesList()) {
                writeSampleInternal(sessionId, 0L, sample, true);
            }
        }
        session = requireSession(sessionId);
        session.sampleCount = activity.getSamplesCount();
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
        session.routePointCount = routeCount;
        session.heartRateSampleCount = heartRateCount;
        if (dao.updateSession(session) != 1) {
            throw new CanonicalContractException(
                    "SESSION_UPDATE_FAILED",
                    "Completed session counts were not updated");
        }

        WatchOutboxEntity outbox = new WatchOutboxEntity();
        outbox.transferId = transferId;
        outbox.sessionId = sessionId;
        outbox.sessionRevision = activity.getWatchRevision();
        outbox.payload = transferBytes;
        outbox.payloadSha256 = transferHash;
        outbox.payloadLength = transferBytes.length;
        outbox.state = OUTBOX_READY;
        outbox.createdAtUtcMs = completedAtUtcMs;
        dao.insertOutbox(outbox);
        return new CompletionResult(snapshot(session), outbox(outbox), true);
    }

    private WatchSessionEntity sessionFromActivity(
            WatchSessionEntity session,
            ActivityRecord activity,
            CanonicalPayload payload,
            long completedAtUtcMs) {
        session.watchRevision = activity.getWatchRevision();
        session.state = LifecycleState.COMPLETED.name();
        session.activityType = activity.getActivityTypeValue();
        session.startedAtUtcMs = activity.getStartTimeUtcMs();
        session.endedAtUtcMs = activity.getEndTimeUtcMs();
        session.elapsedDurationMs = activity.getElapsedDurationMs();
        session.activeDurationMs =
                activity.hasActiveDurationMs() ? activity.getActiveDurationMs() : null;
        session.timeZoneId = activity.hasTimeZoneId() ? activity.getTimeZoneId() : null;
        session.utcOffsetMinutes = activity.hasUtcOffsetMinutesAtStart()
                ? activity.getUtcOffsetMinutesAtStart()
                : null;
        session.leaseToken = 0L;
        session.payload = payload.getExactBytes();
        session.payloadSha256 = payload.sha256Hex();
        session.updatedAtUtcMs = completedAtUtcMs;
        return session;
    }

    private List<MeasurementSample> samplesInternal(String sessionId) {
        List<MeasurementSample> samples = new ArrayList<>();
        for (WatchSampleEntity entity : dao.samples(sessionId)) {
            try {
                samples.add(MeasurementSample.parseFrom(entity.payload));
            } catch (InvalidProtocolBufferException error) {
                throw new CanonicalContractException(
                        "SAMPLE_PAYLOAD_CORRUPT",
                        "Stored sample payload is not valid protobuf",
                        error);
            }
        }
        return samples;
    }

    private boolean writeSampleInternal(
            String sessionId,
            long leaseToken,
            MeasurementSample sample) {
        return writeSampleInternal(sessionId, leaseToken, sample, false);
    }

    private boolean writeSampleInternal(
            String sessionId,
            long leaseToken,
            MeasurementSample sample,
            boolean legacyImport) {
        CanonicalIds.requireUuid(sessionId, "Session identifier");
        CanonicalActivityValidator.validateSample(sample);
        WatchSessionEntity session = requireSession(sessionId);
        if (!legacyImport && session.leaseToken != requireLease(leaseToken)) {
            throw new CanonicalContractException(
                    "SESSION_FENCE_REJECTED",
                    "Sample was rejected by its recording lease");
        }
        if (!legacyImport
                && !LifecycleState.RECORDING.name().equals(session.state)
                && !LifecycleState.ENDING.name().equals(session.state)) {
            throw new CanonicalContractException(
                    "SESSION_NOT_WRITABLE",
                    "Samples can only be appended while recording or ending");
        }
        byte[] bytes = sample.toByteArray();
        String hash = sha256Hex(bytes);
        WatchSampleEntity existing = dao.sample(sessionId, sample.getSequence());
        if (existing != null) {
            if (!existing.payloadSha256.equals(hash)
                    || !MessageDigest.isEqual(existing.payload, bytes)) {
                throw new CanonicalContractException(
                        "SAMPLE_SEQUENCE_CONFLICT",
                        "Sample sequence already stores different bytes");
            }
            return false;
        }
        WatchSampleEntity entity = new WatchSampleEntity();
        entity.sessionId = sessionId;
        entity.sequence = sample.getSequence();
        entity.timestampUtcMs = sample.getTimestampUtcMs();
        entity.monotonicTimeMs =
                sample.hasMonotonicTimeMs() ? sample.getMonotonicTimeMs() : null;
        entity.sessionActiveDurationMs = sample.hasSessionActiveDurationMs()
                ? sample.getSessionActiveDurationMs()
                : null;
        entity.hasLocation = sample.hasLocation() && sample.getLocation().hasValue();
        entity.hasHeartRate =
                sample.hasHeartRateBpm() && sample.getHeartRateBpm().hasValue();
        entity.payload = bytes;
        entity.payloadSha256 = hash;
        dao.insertSample(entity);
        return true;
    }

    private WatchSessionEntity requireSession(String sessionId) {
        WatchSessionEntity session = dao.session(sessionId);
        if (session == null) {
            throw new CanonicalContractException(
                    "SESSION_NOT_FOUND",
                    "Session " + sessionId + " does not exist");
        }
        return session;
    }

    private static boolean sameStart(
            WatchSessionEntity session,
            StartRequest request) {
        return session.sessionId.equals(request.sessionId)
                && session.leaseToken == request.leaseToken
                && session.activityType == request.activityType.getNumber()
                && session.startedAtUtcMs == request.startedAtUtcMs
                && Objects.equals(session.timeZoneId, request.timeZoneId)
                && Objects.equals(session.utcOffsetMinutes, request.utcOffsetMinutes)
                && Objects.equals(session.bootId, request.bootId)
                && Objects.equals(session.monotonicAnchorMs, request.monotonicAnchorMs)
                && Objects.equals(session.legacySourceKey, request.legacySourceKey)
                && Objects.equals(session.legacyActivityId, request.legacyActivityId);
    }

    private static long requireLease(long leaseToken) {
        if (leaseToken == 0L) {
            throw new CanonicalContractException(
                    "LEASE_REQUIRED",
                    "A non-zero recording lease is required");
        }
        return leaseToken;
    }

    private static SessionSnapshot snapshot(WatchSessionEntity entity) {
        return new SessionSnapshot(
                entity.sessionId,
                entity.watchRevision,
                LifecycleState.valueOf(entity.state),
                entity.leaseToken,
                entity.startedAtUtcMs,
                entity.endedAtUtcMs,
                entity.accumulatedActiveMs,
                entity.bootId,
                entity.completionTransferId,
                entity.completionRevision,
                entity.sampleCount,
                entity.routePointCount,
                entity.heartRateSampleCount,
                entity.payload == null ? null : entity.payload.clone(),
                entity.payloadSha256);
    }

    private static OutboxItem outbox(WatchOutboxEntity entity) {
        return new OutboxItem(
                entity.transferId,
                entity.sessionId,
                entity.sessionRevision,
                entity.payload.clone(),
                entity.payloadSha256,
                entity.payloadLength,
                entity.state,
                entity.attemptCount,
                entity.commitAckPayload == null ? null : entity.commitAckPayload.clone(),
                entity.committedAtUtcMs);
    }

    private static TombstoneOutboxItem tombstoneOutbox(
            WatchTombstoneOutboxEntity entity) {
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

    private static void validateActivityAck(
            WatchOutboxEntity entity,
            ActivityTransferFile transfer,
            CommitAck acknowledgement) {
        if (!entity.transferId.equals(acknowledgement.getTransferId())
                || !entity.sessionId.equals(acknowledgement.getSessionId())
                || acknowledgement.getAcceptedSessionRevision() < entity.sessionRevision
                || !MessageDigest.isEqual(
                transfer.getPayloadSha256().toByteArray(),
                acknowledgement.getPayloadSha256().toByteArray())) {
            throw new CanonicalContractException(
                    "ACK_IDENTITY_MISMATCH",
                    "Activity acknowledgement does not match immutable outbox identity");
        }
    }

    private static void validateTombstoneAck(
            WatchTombstoneOutboxEntity entity,
            CommitAck acknowledgement) {
        if (!entity.transferId.equals(acknowledgement.getTransferId())
                || !entity.sessionId.equals(acknowledgement.getSessionId())
                || !acknowledgement.hasDeleteRevision()
                || acknowledgement.getDeleteRevision() != entity.deleteRevision
                || !acknowledgement.hasTombstoneOrigin()
                || acknowledgement.getTombstoneOriginValue() != entity.origin
                || !MessageDigest.isEqual(
                hexBytes(entity.payloadSha256),
                acknowledgement.getPayloadSha256().toByteArray())) {
            throw new CanonicalContractException(
                    "ACK_IDENTITY_MISMATCH",
                    "Tombstone acknowledgement does not match immutable outbox identity");
        }
    }

    private static boolean terminalActivityAck(AckResult result) {
        return result == AckResult.ACK_RESULT_ACCEPTED
                || result == AckResult.ACK_RESULT_DUPLICATE
                || result == AckResult.ACK_RESULT_STALE
                || result == AckResult.ACK_RESULT_TOMBSTONED;
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

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("Outbox limit must be between 1 and 1000");
        }
    }

    private static WatchTombstoneEntity tombstoneEntity(
            TombstoneRecord tombstone,
            byte[] bytes,
            String hash) {
        WatchTombstoneEntity entity = new WatchTombstoneEntity();
        entity.sessionId = tombstone.getSessionId();
        entity.deleteRevision = tombstone.getDeleteRevision();
        entity.coveredSessionRevision = tombstone.getCoveredSessionRevision();
        entity.origin = tombstone.getOriginValue();
        entity.transferId = tombstone.getTransferId();
        entity.deletedAtUtcMs = tombstone.getDeletedAtUtcMs();
        entity.payload = bytes;
        entity.payloadSha256 = hash;
        return entity;
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return CanonicalPayload.hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    @Override
    public void close() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdown();
        }
        if (ownsDatabase) {
            database.close();
        }
    }

    public enum LifecycleState {
        STARTING,
        RECORDING,
        PAUSED,
        ENDING,
        ERROR,
        COMPLETED,
        DISCARDED
    }

    public static final class StartRequest {
        private final String sessionId;
        private final long leaseToken;
        private final ActivityType activityType;
        private final long startedAtUtcMs;
        private final String timeZoneId;
        private final Integer utcOffsetMinutes;
        private final String bootId;
        private final Long monotonicAnchorMs;
        private final long accumulatedActiveMs;
        private final String legacySourceKey;
        private final Long legacyActivityId;

        private StartRequest(Builder builder) {
            sessionId = builder.sessionId;
            leaseToken = builder.leaseToken;
            activityType = builder.activityType;
            startedAtUtcMs = builder.startedAtUtcMs;
            timeZoneId = builder.timeZoneId;
            utcOffsetMinutes = builder.utcOffsetMinutes;
            bootId = builder.bootId;
            monotonicAnchorMs = builder.monotonicAnchorMs;
            accumulatedActiveMs = builder.accumulatedActiveMs;
            legacySourceKey = builder.legacySourceKey;
            legacyActivityId = builder.legacyActivityId;
        }

        public static Builder builder(String sessionId, long leaseToken, long startedAtUtcMs) {
            return new Builder(sessionId, leaseToken, startedAtUtcMs);
        }

        public static final class Builder {
            private final String sessionId;
            private final long leaseToken;
            private final long startedAtUtcMs;
            private ActivityType activityType = ActivityType.ACTIVITY_TYPE_UNSPECIFIED;
            private String timeZoneId;
            private Integer utcOffsetMinutes;
            private String bootId;
            private Long monotonicAnchorMs;
            private long accumulatedActiveMs;
            private String legacySourceKey;
            private Long legacyActivityId;

            private Builder(String sessionId, long leaseToken, long startedAtUtcMs) {
                this.sessionId = sessionId;
                this.leaseToken = leaseToken;
                this.startedAtUtcMs = startedAtUtcMs;
            }

            public Builder activityType(ActivityType value) {
                activityType = Objects.requireNonNull(value);
                return this;
            }

            public Builder timeZone(String id, int offsetMinutes) {
                timeZoneId = id;
                utcOffsetMinutes = offsetMinutes;
                return this;
            }

            public Builder monotonicAnchor(String valueBootId, long valueMonotonicMs) {
                bootId = valueBootId;
                monotonicAnchorMs = valueMonotonicMs;
                return this;
            }

            public Builder accumulatedActiveMs(long value) {
                accumulatedActiveMs = value;
                return this;
            }

            public Builder legacyIdentity(String sourceKey, long activityId) {
                legacySourceKey = sourceKey;
                legacyActivityId = activityId;
                return this;
            }

            public StartRequest build() {
                return new StartRequest(this);
            }
        }
    }

    public static final class SessionSnapshot {
        private final String sessionId;
        private final long watchRevision;
        private final LifecycleState state;
        private final long leaseToken;
        private final long startedAtUtcMs;
        private final Long endedAtUtcMs;
        private final long accumulatedActiveMs;
        private final String bootId;
        private final String completionTransferId;
        private final Long completionRevision;
        private final long sampleCount;
        private final long routePointCount;
        private final long heartRateSampleCount;
        private final byte[] payload;
        private final String payloadSha256;

        private SessionSnapshot(
                String sessionId,
                long watchRevision,
                LifecycleState state,
                long leaseToken,
                long startedAtUtcMs,
                Long endedAtUtcMs,
                long accumulatedActiveMs,
                String bootId,
                String completionTransferId,
                Long completionRevision,
                long sampleCount,
                long routePointCount,
                long heartRateSampleCount,
                byte[] payload,
                String payloadSha256) {
            this.sessionId = sessionId;
            this.watchRevision = watchRevision;
            this.state = state;
            this.leaseToken = leaseToken;
            this.startedAtUtcMs = startedAtUtcMs;
            this.endedAtUtcMs = endedAtUtcMs;
            this.accumulatedActiveMs = accumulatedActiveMs;
            this.bootId = bootId;
            this.completionTransferId = completionTransferId;
            this.completionRevision = completionRevision;
            this.sampleCount = sampleCount;
            this.routePointCount = routePointCount;
            this.heartRateSampleCount = heartRateSampleCount;
            this.payload = payload;
            this.payloadSha256 = payloadSha256;
        }

        public String getSessionId() {
            return sessionId;
        }

        public long getWatchRevision() {
            return watchRevision;
        }

        public LifecycleState getState() {
            return state;
        }

        public long getLeaseToken() {
            return leaseToken;
        }

        public long getStartedAtUtcMs() {
            return startedAtUtcMs;
        }

        public Long getEndedAtUtcMs() {
            return endedAtUtcMs;
        }

        public long getAccumulatedActiveMs() {
            return accumulatedActiveMs;
        }

        public String getBootId() {
            return bootId;
        }

        public String getCompletionTransferId() {
            return completionTransferId;
        }

        public Long getCompletionRevision() {
            return completionRevision;
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

        public byte[] getPayload() {
            return payload == null ? null : payload.clone();
        }

        public String getPayloadSha256() {
            return payloadSha256;
        }
    }

    public static final class OutboxItem {
        private final String transferId;
        private final String sessionId;
        private final long sessionRevision;
        private final byte[] payload;
        private final String payloadSha256;
        private final long payloadLength;
        private final String state;
        private final long attemptCount;
        private final byte[] commitAckPayload;
        private final Long committedAtUtcMs;

        private OutboxItem(
                String transferId,
                String sessionId,
                long sessionRevision,
                byte[] payload,
                String payloadSha256,
                long payloadLength,
                String state,
                long attemptCount,
                byte[] commitAckPayload,
                Long committedAtUtcMs) {
            this.transferId = transferId;
            this.sessionId = sessionId;
            this.sessionRevision = sessionRevision;
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

        public long getSessionRevision() {
            return sessionRevision;
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

    public static final class AckAcceptance {
        private final OutboxItem outbox;
        private final CommitAck acknowledgement;
        private final boolean committedNow;

        private AckAcceptance(
                OutboxItem outbox,
                CommitAck acknowledgement,
                boolean committedNow) {
            this.outbox = outbox;
            this.acknowledgement = acknowledgement;
            this.committedNow = committedNow;
        }

        public OutboxItem getOutbox() {
            return outbox;
        }

        public CommitAck getAcknowledgement() {
            return acknowledgement;
        }

        public boolean isCommittedNow() {
            return committedNow;
        }
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

    public static final class CompletionResult {
        private final SessionSnapshot session;
        private final OutboxItem outbox;
        private final boolean created;

        private CompletionResult(
                SessionSnapshot session,
                OutboxItem outbox,
                boolean created) {
            this.session = session;
            this.outbox = outbox;
            this.created = created;
        }

        public SessionSnapshot getSession() {
            return session;
        }

        public OutboxItem getOutbox() {
            return outbox;
        }

        public boolean isCreated() {
            return created;
        }
    }
}
