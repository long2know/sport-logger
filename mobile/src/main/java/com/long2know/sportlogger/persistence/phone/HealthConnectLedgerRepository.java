package com.long2know.sportlogger.persistence.phone;

import android.content.Context;

import androidx.room.InvalidationTracker;

import com.long2know.utilities.contract.CanonicalContractException;
import com.long2know.utilities.contract.CanonicalIds;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

public final class HealthConnectLedgerRepository implements AutoCloseable {
    private final PhoneDatabase database;
    private final PhoneDao dao;
    private final boolean ownsDatabase;

    public HealthConnectLedgerRepository(PhoneDatabase database) {
        this(database, false);
    }

    private HealthConnectLedgerRepository(PhoneDatabase database, boolean ownsDatabase) {
        this.database = Objects.requireNonNull(database);
        this.dao = database.phoneDao();
        this.ownsDatabase = ownsDatabase;
    }

    public static HealthConnectLedgerRepository open(Context context) {
        return new HealthConnectLedgerRepository(PhoneDatabase.open(context), true);
    }

    public static String deterministicClientRecordId(
            String sessionId,
            String recordType,
            int chunkIndex) {
        CanonicalIds.requireUuid(sessionId, "Session identifier");
        validateRecordType(recordType);
        if (chunkIndex < 0) {
            throw new IllegalArgumentException("Health Connect chunk index cannot be negative");
        }
        return "sl:" + sessionId + ":" + recordType + ":" + chunkIndex;
    }

    public long allocateNextClientRecordVersion(
            String sessionId,
            String clientRecordId,
            long nowUtcMs) {
        CanonicalIds.requireUuid(sessionId, "Session identifier");
        requireText(clientRecordId, "Client record ID");
        return database.runInTransaction(() -> {
            HealthConnectClientVersionEntity version =
                    dao.healthConnectClientVersion(sessionId, clientRecordId);
            if (version == null) {
                version = new HealthConnectClientVersionEntity();
                version.sessionId = sessionId;
                version.clientRecordId = clientRecordId;
                version.allocatedVersion = 1L;
                version.updatedAtUtcMs = nowUtcMs;
                dao.insertHealthConnectClientVersion(version);
                return 1L;
            }
            if (version.allocatedVersion == Long.MAX_VALUE) {
                throw new CanonicalContractException(
                        "HEALTH_CONNECT_VERSION_EXHAUSTED",
                        "Health Connect client record version is exhausted");
            }
            version.allocatedVersion++;
            version.updatedAtUtcMs = nowUtcMs;
            if (dao.updateHealthConnectClientVersion(version) != 1) {
                throw updateFailed();
            }
            return version.allocatedVersion;
        });
    }

    public List<LedgerRecord> prepareRecords(
            String sessionId,
            long revision,
            List<RecordPlan> plans,
            long nowUtcMs) {
        CanonicalIds.requireUuid(sessionId, "Session identifier");
        if (revision <= 0L || plans == null || plans.isEmpty()) {
            throw new IllegalArgumentException(
                    "Stored revision and at least one Health Connect record plan are required");
        }
        return database.runInTransaction(() -> {
            if (dao.revision(sessionId, revision) == null) {
                throw new CanonicalContractException(
                        "ACTIVITY_REVISION_NOT_FOUND",
                        "Health Connect records require an immutable stored activity revision");
            }
            List<LedgerRecord> prepared = new ArrayList<>();
            for (RecordPlan plan : plans) {
                validatePlan(sessionId, revision, plan);
                RecordKey key = new RecordKey(
                        sessionId,
                        revision,
                        plan.recordType,
                        plan.chunkIndex);
                HealthConnectRecordEntity entity = entity(key);
                if (entity == null) {
                    entity = new HealthConnectRecordEntity();
                    applyKey(entity, key);
                    entity.clientRecordId = plan.clientRecordId;
                    entity.clientRecordVersion = plan.clientRecordVersion;
                    entity.mappingSha256 = plan.mappingSha256;
                    entity.state = RecordState.PENDING.name();
                    HealthConnectRecordEntity prior =
                            priorRecord(sessionId, plan.clientRecordId);
                    entity.operation = prior == null
                            ? Operation.PUBLISH.name()
                            : Operation.UPDATE.name();
                    entity.providerRecordId =
                            prior == null ? null : prior.providerRecordId;
                    entity.createdAtUtcMs = nowUtcMs;
                    entity.updatedAtUtcMs = nowUtcMs;
                    dao.insertHealthConnectRecord(entity);
                } else {
                    if (!entity.clientRecordId.equals(plan.clientRecordId)) {
                        throw new CanonicalContractException(
                                "HEALTH_CONNECT_CLIENT_ID_CONFLICT",
                                "Health Connect client record identity cannot change");
                    }
                    if (plan.clientRecordVersion < entity.clientRecordVersion) {
                        throw new CanonicalContractException(
                                "HEALTH_CONNECT_VERSION_STALE",
                                "Health Connect client record version cannot decrease");
                    }
                    if (plan.clientRecordVersion == entity.clientRecordVersion) {
                        if (!entity.mappingSha256.equals(plan.mappingSha256)) {
                            throw new CanonicalContractException(
                                    "HEALTH_CONNECT_VERSION_CONFLICT",
                                    "Changed Health Connect mapping requires a higher client version");
                        }
                    } else {
                        entity.clientRecordVersion = plan.clientRecordVersion;
                        entity.mappingSha256 = plan.mappingSha256;
                        entity.state = RecordState.PENDING.name();
                        entity.operation = Operation.UPDATE.name();
                        entity.lastErrorCode = null;
                        entity.lastErrorMessage = null;
                        entity.updatedAtUtcMs = nowUtcMs;
                        if (dao.updateHealthConnectRecord(entity) != 1) {
                            throw updateFailed();
                        }
                    }
                }
                prepared.add(toRecord(entity));
            }
            return Collections.unmodifiableList(prepared);
        });
    }

    public List<LedgerRecord> pendingUpserts(String sessionId, long revision) {
        return recordsInStates(
                sessionId,
                revision,
                Arrays.asList(RecordState.PENDING.name(), RecordState.FAILED.name()));
    }

    public List<LedgerRecord> pendingDeletes(String sessionId, long revision) {
        return recordsInStates(
                sessionId,
                revision,
                Collections.singletonList(RecordState.DELETE_PENDING.name()));
    }

    public List<LedgerRecord> records(String sessionId, long revision) {
        CanonicalIds.requireUuid(sessionId, "Session identifier");
        List<LedgerRecord> records = new ArrayList<>();
        for (HealthConnectRecordEntity entity : dao.healthConnectRecords(sessionId, revision)) {
            records.add(toRecord(entity));
        }
        return Collections.unmodifiableList(records);
    }

    public List<LedgerRecord> ownedRecordsForSession(String sessionId) {
        CanonicalIds.requireUuid(sessionId, "Session identifier");
        List<LedgerRecord> records = new ArrayList<>();
        for (HealthConnectRecordEntity entity :
                dao.healthConnectRecordsForSession(sessionId)) {
            records.add(toRecord(entity));
        }
        return Collections.unmodifiableList(records);
    }

    public LedgerRecord beginOperation(
            RecordKey key,
            long expectedClientVersion,
            Operation operation,
            long nowUtcMs) {
        return database.runInTransaction(() -> {
            HealthConnectRecordEntity entity = requireEntity(key);
            requireVersion(entity, expectedClientVersion);
            Operation requested = Objects.requireNonNull(operation);
            if (requested == Operation.DELETE) {
                if (!entity.state.equals(RecordState.DELETE_PENDING.name())
                        && !entity.state.equals(RecordState.FAILED.name())) {
                    throw new CanonicalContractException(
                            "HEALTH_CONNECT_STATE_INVALID",
                            "Delete operation requires delete-pending or failed state");
                }
            } else if (!entity.state.equals(RecordState.PENDING.name())
                    && !entity.state.equals(RecordState.FAILED.name())) {
                throw new CanonicalContractException(
                        "HEALTH_CONNECT_STATE_INVALID",
                        "Publish/update operation requires pending or failed state");
            }
            entity.operation = requested.name();
            entity.state = RecordState.IN_FLIGHT.name();
            entity.attemptCount++;
            entity.lastAttemptAtUtcMs = nowUtcMs;
            entity.lastErrorCode = null;
            entity.lastErrorMessage = null;
            entity.updatedAtUtcMs = nowUtcMs;
            if (dao.updateHealthConnectRecord(entity) != 1) {
                throw updateFailed();
            }
            insertOperationReceipt(
                    entity,
                    OperationPhase.BEFORE,
                    OperationResult.STARTED,
                    entity.providerRecordId,
                    null,
                    nowUtcMs);
            return toRecord(entity);
        });
    }

    public LedgerRecord markUpserted(
            RecordKey key,
            long expectedClientVersion,
            String providerRecordId,
            long nowUtcMs) {
        validateKey(key);
        if (providerRecordId == null || providerRecordId.isEmpty()) {
            throw new IllegalArgumentException(
                    "Health Connect provider record ID is required");
        }
        return database.runInTransaction(() -> {
            HealthConnectRecordEntity entity = requireEntity(key);
            requireVersion(entity, expectedClientVersion);
            if (!entity.state.equals(RecordState.IN_FLIGHT.name())
                    || (Operation.valueOf(entity.operation) != Operation.PUBLISH
                    && Operation.valueOf(entity.operation) != Operation.UPDATE)) {
                throw new CanonicalContractException(
                        "HEALTH_CONNECT_STATE_INVALID",
                        "Upsert completion requires an in-flight publish/update operation");
            }
            HealthConnectOwnedRecordIdEntity ownedId = new HealthConnectOwnedRecordIdEntity();
            applyKey(ownedId, key);
            ownedId.providerRecordId = providerRecordId;
            ownedId.recordedAtUtcMs = nowUtcMs;
            dao.insertHealthConnectOwnedRecordId(ownedId);
            entity.providerRecordId = providerRecordId;
            entity.state = RecordState.UPSERTED.name();
            entity.lastErrorCode = null;
            entity.lastErrorMessage = null;
            entity.updatedAtUtcMs = nowUtcMs;
            if (dao.updateHealthConnectRecord(entity) != 1) {
                throw updateFailed();
            }
            insertOperationReceipt(
                    entity,
                    OperationPhase.AFTER,
                    OperationResult.SUCCEEDED,
                    providerRecordId,
                    null,
                    nowUtcMs);
            return toRecord(entity);
        });
    }

    public LedgerRecord markFailure(
            RecordKey key,
            long expectedClientVersion,
            String errorCode,
            String errorMessage,
            long nowUtcMs) {
        return database.runInTransaction(() -> {
            HealthConnectRecordEntity entity = requireEntity(key);
            requireVersion(entity, expectedClientVersion);
            Operation operation = Operation.valueOf(entity.operation);
            entity.state = operation == Operation.DELETE
                    ? RecordState.DELETE_PENDING.name()
                    : RecordState.FAILED.name();
            entity.lastErrorCode = requireText(errorCode, "Failure code");
            entity.lastErrorMessage = errorMessage;
            entity.updatedAtUtcMs = nowUtcMs;
            if (dao.updateHealthConnectRecord(entity) != 1) {
                throw updateFailed();
            }
            insertOperationReceipt(
                    entity,
                    OperationPhase.AFTER,
                    OperationResult.FAILED,
                    entity.providerRecordId,
                    errorCode,
                    nowUtcMs);
            return toRecord(entity);
        });
    }

    public List<LedgerRecord> markDeletePending(
            String sessionId,
            long revision,
            long nowUtcMs) {
        return database.runInTransaction(() -> {
            List<LedgerRecord> records = new ArrayList<>();
            for (HealthConnectRecordEntity entity :
                    dao.healthConnectRecords(sessionId, revision)) {
                if (!entity.state.equals(RecordState.DELETED.name())) {
                    entity.state = RecordState.DELETE_PENDING.name();
                    entity.operation = Operation.DELETE.name();
                    entity.lastErrorCode = null;
                    entity.lastErrorMessage = null;
                    entity.updatedAtUtcMs = nowUtcMs;
                    if (dao.updateHealthConnectRecord(entity) != 1) {
                        throw updateFailed();
                    }
                }
                records.add(toRecord(entity));
            }
            return Collections.unmodifiableList(records);
        });
    }

    public List<LedgerRecord> markAllOwnedDeletePending(
            String sessionId,
            long nowUtcMs) {
        CanonicalIds.requireUuid(sessionId, "Session identifier");
        return database.runInTransaction(() -> {
            List<LedgerRecord> records = new ArrayList<>();
            for (HealthConnectRecordEntity entity :
                    dao.healthConnectRecordsForSession(sessionId)) {
                if (!entity.state.equals(RecordState.DELETED.name())) {
                    entity.state = RecordState.DELETE_PENDING.name();
                    entity.operation = Operation.DELETE.name();
                    entity.lastErrorCode = null;
                    entity.lastErrorMessage = null;
                    entity.updatedAtUtcMs = nowUtcMs;
                    if (dao.updateHealthConnectRecord(entity) != 1) {
                        throw updateFailed();
                    }
                }
                records.add(toRecord(entity));
            }
            return Collections.unmodifiableList(records);
        });
    }

    public DeletionReceipt markDeleted(
            RecordKey key,
            long expectedClientVersion,
            DeletionResult result,
            String providerRecordId,
            String errorCode,
            long deletedAtUtcMs) {
        return database.runInTransaction(() -> {
            HealthConnectRecordEntity entity = requireEntity(key);
            requireVersion(entity, expectedClientVersion);
            if (!entity.state.equals(RecordState.IN_FLIGHT.name())
                    || Operation.valueOf(entity.operation) != Operation.DELETE) {
                throw new CanonicalContractException(
                        "HEALTH_CONNECT_STATE_INVALID",
                        "Deletion completion requires an in-flight delete operation");
            }
            if (providerRecordId != null && !providerRecordId.isEmpty()) {
                HealthConnectOwnedRecordIdEntity ownedId =
                        new HealthConnectOwnedRecordIdEntity();
                applyKey(ownedId, key);
                ownedId.providerRecordId = providerRecordId;
                ownedId.recordedAtUtcMs = deletedAtUtcMs;
                dao.insertHealthConnectOwnedRecordId(ownedId);
            }
            HealthConnectDeletionReceiptEntity receipt =
                    new HealthConnectDeletionReceiptEntity();
            receipt.receiptId = CanonicalIds.newTransferId();
            applyKey(receipt, key);
            receipt.clientRecordId = entity.clientRecordId;
            receipt.clientRecordVersion = entity.clientRecordVersion;
            receipt.providerRecordId = providerRecordId == null
                    ? entity.providerRecordId
                    : providerRecordId;
            receipt.result = Objects.requireNonNull(result).name();
            receipt.errorCode = errorCode;
            receipt.deletedAtUtcMs = deletedAtUtcMs;
            dao.insertHealthConnectDeletionReceipt(receipt);
            if (result == DeletionResult.DELETED
                    || result == DeletionResult.ALREADY_ABSENT) {
                entity.state = RecordState.DELETED.name();
                entity.lastErrorCode = null;
                entity.lastErrorMessage = null;
            } else {
                entity.state = RecordState.DELETE_PENDING.name();
                entity.lastErrorCode = errorCode;
            }
            entity.updatedAtUtcMs = deletedAtUtcMs;
            if (dao.updateHealthConnectRecord(entity) != 1) {
                throw updateFailed();
            }
            insertOperationReceipt(
                    entity,
                    OperationPhase.AFTER,
                    result == DeletionResult.FAILED
                            ? OperationResult.FAILED
                            : OperationResult.SUCCEEDED,
                    receipt.providerRecordId,
                    errorCode,
                    deletedAtUtcMs);
            return toReceipt(receipt);
        });
    }

    public List<DeletionReceipt> deletionReceipts(String sessionId, long revision) {
        List<DeletionReceipt> receipts = new ArrayList<>();
        for (HealthConnectDeletionReceiptEntity entity :
                dao.healthConnectDeletionReceipts(sessionId, revision)) {
            receipts.add(toReceipt(entity));
        }
        return Collections.unmodifiableList(receipts);
    }

    public List<OperationReceipt> operationReceipts(String sessionId, long revision) {
        List<OperationReceipt> receipts = new ArrayList<>();
        for (HealthConnectOperationReceiptEntity entity :
                dao.healthConnectOperationReceipts(sessionId, revision)) {
            receipts.add(toOperationReceipt(entity));
        }
        return Collections.unmodifiableList(receipts);
    }

    public Progress progress(String sessionId, long revision) {
        int pending = 0;
        int upserted = 0;
        int failed = 0;
        int inFlight = 0;
        int deletePending = 0;
        int deleted = 0;
        for (HealthConnectRecordEntity entity :
                dao.healthConnectRecords(sessionId, revision)) {
            switch (RecordState.valueOf(entity.state)) {
                case PENDING:
                    pending++;
                    break;
                case UPSERTED:
                    upserted++;
                    break;
                case FAILED:
                    failed++;
                    break;
                case IN_FLIGHT:
                    inFlight++;
                    break;
                case DELETE_PENDING:
                    deletePending++;
                    break;
                case DELETED:
                    deleted++;
                    break;
                default:
                    throw new IllegalStateException("Unknown Health Connect state");
            }
        }
        return new Progress(pending, upserted, failed, inFlight, deletePending, deleted);
    }

    public AutoCloseable observeChanges(Executor executor, Runnable listener) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(listener);
        InvalidationTracker.Observer observer = new InvalidationTracker.Observer(
                "health_connect_records",
                "health_connect_owned_record_ids",
                "health_connect_deletion_receipts",
                "health_connect_operation_receipts") {
            @Override
            public void onInvalidated(Set<String> tables) {
                executor.execute(listener);
            }
        };
        database.getInvalidationTracker().addObserver(observer);
        return () -> database.getInvalidationTracker().removeObserver(observer);
    }

    private List<LedgerRecord> recordsInStates(
            String sessionId,
            long revision,
            List<String> states) {
        CanonicalIds.requireUuid(sessionId, "Session identifier");
        List<LedgerRecord> records = new ArrayList<>();
        for (HealthConnectRecordEntity entity :
                dao.healthConnectRecordsInStates(sessionId, revision, states)) {
            records.add(toRecord(entity));
        }
        return Collections.unmodifiableList(records);
    }

    private HealthConnectRecordEntity entity(RecordKey key) {
        return dao.healthConnectRecord(
                key.sessionId,
                key.revision,
                key.recordType,
                key.chunkIndex);
    }

    private HealthConnectRecordEntity priorRecord(
            String sessionId,
            String clientRecordId) {
        HealthConnectRecordEntity prior = null;
        for (HealthConnectRecordEntity candidate :
                dao.healthConnectRecordsForSession(sessionId)) {
            if (candidate.clientRecordId.equals(clientRecordId)
                    && (prior == null
                    || candidate.sessionRevision > prior.sessionRevision)) {
                prior = candidate;
            }
        }
        return prior;
    }

    private HealthConnectRecordEntity requireEntity(RecordKey key) {
        validateKey(key);
        HealthConnectRecordEntity entity = entity(key);
        if (entity == null) {
            throw new CanonicalContractException(
                    "HEALTH_CONNECT_RECORD_NOT_FOUND",
                    "Health Connect record plan does not exist");
        }
        return entity;
    }

    private LedgerRecord toRecord(HealthConnectRecordEntity entity) {
        RecordKey key = new RecordKey(
                entity.sessionId,
                entity.sessionRevision,
                entity.recordType,
                entity.chunkIndex);
        List<String> providerIds = dao.healthConnectOwnedRecordIds(
                entity.sessionId,
                entity.sessionRevision,
                entity.recordType,
                entity.chunkIndex);
        return new LedgerRecord(
                key,
                entity.clientRecordId,
                entity.clientRecordVersion,
                entity.mappingSha256,
                RecordState.valueOf(entity.state),
                Operation.valueOf(entity.operation),
                entity.providerRecordId,
                Collections.unmodifiableList(new ArrayList<>(providerIds)),
                entity.lastErrorCode,
                entity.lastErrorMessage,
                entity.attemptCount,
                entity.lastAttemptAtUtcMs,
                entity.updatedAtUtcMs);
    }

    private static DeletionReceipt toReceipt(
            HealthConnectDeletionReceiptEntity entity) {
        return new DeletionReceipt(
                entity.receiptId,
                new RecordKey(
                        entity.sessionId,
                        entity.sessionRevision,
                        entity.recordType,
                        entity.chunkIndex),
                entity.clientRecordId,
                entity.clientRecordVersion,
                entity.providerRecordId,
                DeletionResult.valueOf(entity.result),
                entity.errorCode,
                entity.deletedAtUtcMs);
    }

    private static OperationReceipt toOperationReceipt(
            HealthConnectOperationReceiptEntity entity) {
        return new OperationReceipt(
                entity.receiptId,
                new RecordKey(
                        entity.sessionId,
                        entity.sessionRevision,
                        entity.recordType,
                        entity.chunkIndex),
                entity.clientRecordId,
                entity.clientRecordVersion,
                Operation.valueOf(entity.operation),
                OperationPhase.valueOf(entity.phase),
                OperationResult.valueOf(entity.result),
                entity.providerRecordId,
                entity.errorCode,
                entity.recordedAtUtcMs);
    }

    private void insertOperationReceipt(
            HealthConnectRecordEntity record,
            OperationPhase phase,
            OperationResult result,
            String providerRecordId,
            String errorCode,
            long recordedAtUtcMs) {
        HealthConnectOperationReceiptEntity receipt =
                new HealthConnectOperationReceiptEntity();
        receipt.receiptId = CanonicalIds.newTransferId();
        receipt.sessionId = record.sessionId;
        receipt.sessionRevision = record.sessionRevision;
        receipt.recordType = record.recordType;
        receipt.chunkIndex = record.chunkIndex;
        receipt.clientRecordId = record.clientRecordId;
        receipt.clientRecordVersion = record.clientRecordVersion;
        receipt.operation = record.operation;
        receipt.phase = phase.name();
        receipt.result = result.name();
        receipt.providerRecordId = providerRecordId;
        receipt.errorCode = errorCode;
        receipt.recordedAtUtcMs = recordedAtUtcMs;
        dao.insertHealthConnectOperationReceipt(receipt);
    }

    private static void validatePlan(
            String sessionId,
            long revision,
            RecordPlan plan) {
        Objects.requireNonNull(plan);
        validateRecordType(plan.recordType);
        if (plan.chunkIndex < 0
                || plan.clientRecordVersion <= 0L
                || plan.mappingSha256 == null
                || plan.mappingSha256.length() != 64) {
            throw new IllegalArgumentException(
                    "Health Connect chunk, version, and mapping SHA-256 are required");
        }
        String expectedClientId = deterministicClientRecordId(
                sessionId,
                plan.recordType,
                plan.chunkIndex);
        if (!expectedClientId.equals(plan.clientRecordId)) {
            throw new CanonicalContractException(
                    "HEALTH_CONNECT_CLIENT_ID_INVALID",
                    "Health Connect client record ID must be deterministic per type/chunk");
        }
        if (revision <= 0L) {
            throw new IllegalArgumentException("Health Connect revision must be positive");
        }
    }

    private static void validateKey(RecordKey key) {
        Objects.requireNonNull(key);
        CanonicalIds.requireUuid(key.sessionId, "Session identifier");
        validateRecordType(key.recordType);
        if (key.revision <= 0L || key.chunkIndex < 0) {
            throw new IllegalArgumentException(
                    "Health Connect revision and chunk identity are invalid");
        }
    }

    private static void validateRecordType(String recordType) {
        if (recordType == null || !recordType.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException(
                    "Health Connect record type must be a stable identifier");
        }
    }

    private static void requireVersion(
            HealthConnectRecordEntity entity,
            long expectedClientVersion) {
        if (entity.clientRecordVersion != expectedClientVersion) {
            throw new CanonicalContractException(
                    "HEALTH_CONNECT_VERSION_CAS_REJECTED",
                    "Health Connect client record version changed");
        }
    }

    private static void applyKey(HealthConnectRecordEntity entity, RecordKey key) {
        entity.sessionId = key.sessionId;
        entity.sessionRevision = key.revision;
        entity.recordType = key.recordType;
        entity.chunkIndex = key.chunkIndex;
    }

    private static void applyKey(
            HealthConnectOwnedRecordIdEntity entity,
            RecordKey key) {
        entity.sessionId = key.sessionId;
        entity.sessionRevision = key.revision;
        entity.recordType = key.recordType;
        entity.chunkIndex = key.chunkIndex;
    }

    private static void applyKey(
            HealthConnectDeletionReceiptEntity entity,
            RecordKey key) {
        entity.sessionId = key.sessionId;
        entity.sessionRevision = key.revision;
        entity.recordType = key.recordType;
        entity.chunkIndex = key.chunkIndex;
    }

    private static CanonicalContractException updateFailed() {
        return new CanonicalContractException(
                "HEALTH_CONNECT_LEDGER_UPDATE_FAILED",
                "Health Connect ledger did not update exactly one row");
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }

    @Override
    public void close() {
        if (ownsDatabase) {
            database.close();
        }
    }

    public enum RecordState {
        PENDING,
        IN_FLIGHT,
        UPSERTED,
        FAILED,
        DELETE_PENDING,
        DELETED
    }

    public enum Operation {
        PUBLISH,
        UPDATE,
        DELETE
    }

    public enum OperationPhase {
        BEFORE,
        AFTER
    }

    public enum OperationResult {
        STARTED,
        SUCCEEDED,
        FAILED
    }

    public enum DeletionResult {
        DELETED,
        ALREADY_ABSENT,
        FAILED
    }

    public static final class RecordKey {
        private final String sessionId;
        private final long revision;
        private final String recordType;
        private final int chunkIndex;

        public RecordKey(
                String sessionId,
                long revision,
                String recordType,
                int chunkIndex) {
            this.sessionId = sessionId;
            this.revision = revision;
            this.recordType = recordType;
            this.chunkIndex = chunkIndex;
        }

        public String getSessionId() {
            return sessionId;
        }

        public long getRevision() {
            return revision;
        }

        public String getRecordType() {
            return recordType;
        }

        public int getChunkIndex() {
            return chunkIndex;
        }
    }

    public static final class RecordPlan {
        private final String recordType;
        private final int chunkIndex;
        private final String clientRecordId;
        private final long clientRecordVersion;
        private final String mappingSha256;

        public RecordPlan(
                String recordType,
                int chunkIndex,
                String clientRecordId,
                long clientRecordVersion,
                String mappingSha256) {
            this.recordType = recordType;
            this.chunkIndex = chunkIndex;
            this.clientRecordId = clientRecordId;
            this.clientRecordVersion = clientRecordVersion;
            this.mappingSha256 = mappingSha256;
        }

        public String getRecordType() {
            return recordType;
        }

        public int getChunkIndex() {
            return chunkIndex;
        }

        public String getClientRecordId() {
            return clientRecordId;
        }

        public long getClientRecordVersion() {
            return clientRecordVersion;
        }

        public String getMappingSha256() {
            return mappingSha256;
        }
    }

    public static final class LedgerRecord {
        private final RecordKey key;
        private final String clientRecordId;
        private final long clientRecordVersion;
        private final String mappingSha256;
        private final RecordState state;
        private final Operation operation;
        private final String providerRecordId;
        private final List<String> ownedProviderRecordIds;
        private final String lastErrorCode;
        private final String lastErrorMessage;
        private final long attemptCount;
        private final Long lastAttemptAtUtcMs;
        private final long updatedAtUtcMs;

        private LedgerRecord(
                RecordKey key,
                String clientRecordId,
                long clientRecordVersion,
                String mappingSha256,
                RecordState state,
                Operation operation,
                String providerRecordId,
                List<String> ownedProviderRecordIds,
                String lastErrorCode,
                String lastErrorMessage,
                long attemptCount,
                Long lastAttemptAtUtcMs,
                long updatedAtUtcMs) {
            this.key = key;
            this.clientRecordId = clientRecordId;
            this.clientRecordVersion = clientRecordVersion;
            this.mappingSha256 = mappingSha256;
            this.state = state;
            this.operation = operation;
            this.providerRecordId = providerRecordId;
            this.ownedProviderRecordIds = ownedProviderRecordIds;
            this.lastErrorCode = lastErrorCode;
            this.lastErrorMessage = lastErrorMessage;
            this.attemptCount = attemptCount;
            this.lastAttemptAtUtcMs = lastAttemptAtUtcMs;
            this.updatedAtUtcMs = updatedAtUtcMs;
        }

        public RecordKey getKey() {
            return key;
        }

        public String getClientRecordId() {
            return clientRecordId;
        }

        public long getClientRecordVersion() {
            return clientRecordVersion;
        }

        public String getMappingSha256() {
            return mappingSha256;
        }

        public RecordState getState() {
            return state;
        }

        public Operation getOperation() {
            return operation;
        }

        public String getProviderRecordId() {
            return providerRecordId;
        }

        public List<String> getOwnedProviderRecordIds() {
            return ownedProviderRecordIds;
        }

        public String getLastErrorCode() {
            return lastErrorCode;
        }

        public String getLastErrorMessage() {
            return lastErrorMessage;
        }

        public long getAttemptCount() {
            return attemptCount;
        }

        public Long getLastAttemptAtUtcMs() {
            return lastAttemptAtUtcMs;
        }

        public long getUpdatedAtUtcMs() {
            return updatedAtUtcMs;
        }
    }

    public static final class OperationReceipt {
            private final String receiptId;
            private final RecordKey key;
            private final String clientRecordId;
            private final long clientRecordVersion;
            private final Operation operation;
            private final OperationPhase phase;
            private final OperationResult result;
            private final String providerRecordId;
            private final String errorCode;
            private final long recordedAtUtcMs;

            private OperationReceipt(
                    String receiptId,
                    RecordKey key,
                    String clientRecordId,
                    long clientRecordVersion,
                    Operation operation,
                    OperationPhase phase,
                    OperationResult result,
                    String providerRecordId,
                    String errorCode,
                    long recordedAtUtcMs) {
                this.receiptId = receiptId;
                this.key = key;
                this.clientRecordId = clientRecordId;
                this.clientRecordVersion = clientRecordVersion;
                this.operation = operation;
                this.phase = phase;
                this.result = result;
                this.providerRecordId = providerRecordId;
                this.errorCode = errorCode;
                this.recordedAtUtcMs = recordedAtUtcMs;
            }

            public String getReceiptId() {
                return receiptId;
            }

            public RecordKey getKey() {
                return key;
            }

            public String getClientRecordId() {
                return clientRecordId;
            }

            public long getClientRecordVersion() {
                return clientRecordVersion;
            }

            public Operation getOperation() {
                return operation;
            }

            public OperationPhase getPhase() {
                return phase;
            }

            public OperationResult getResult() {
                return result;
            }

            public String getProviderRecordId() {
                return providerRecordId;
            }

            public String getErrorCode() {
                return errorCode;
            }

            public long getRecordedAtUtcMs() {
                return recordedAtUtcMs;
            }
    }

    public static final class DeletionReceipt {
        private final String receiptId;
        private final RecordKey key;
        private final String clientRecordId;
        private final long clientRecordVersion;
        private final String providerRecordId;
        private final DeletionResult result;
        private final String errorCode;
        private final long deletedAtUtcMs;

        private DeletionReceipt(
                String receiptId,
                RecordKey key,
                String clientRecordId,
                long clientRecordVersion,
                String providerRecordId,
                DeletionResult result,
                String errorCode,
                long deletedAtUtcMs) {
            this.receiptId = receiptId;
            this.key = key;
            this.clientRecordId = clientRecordId;
            this.clientRecordVersion = clientRecordVersion;
            this.providerRecordId = providerRecordId;
            this.result = result;
            this.errorCode = errorCode;
            this.deletedAtUtcMs = deletedAtUtcMs;
        }

        public String getReceiptId() {
            return receiptId;
        }

        public RecordKey getKey() {
            return key;
        }

        public String getClientRecordId() {
            return clientRecordId;
        }

        public long getClientRecordVersion() {
            return clientRecordVersion;
        }

        public String getProviderRecordId() {
            return providerRecordId;
        }

        public DeletionResult getResult() {
            return result;
        }

        public String getErrorCode() {
            return errorCode;
        }

        public long getDeletedAtUtcMs() {
            return deletedAtUtcMs;
        }
    }

    public static final class Progress {
        private final int pending;
        private final int upserted;
        private final int failed;
        private final int inFlight;
        private final int deletePending;
        private final int deleted;

        private Progress(
                int pending,
                int upserted,
                int failed,
                int inFlight,
                int deletePending,
                int deleted) {
            this.pending = pending;
            this.upserted = upserted;
            this.failed = failed;
            this.inFlight = inFlight;
            this.deletePending = deletePending;
            this.deleted = deleted;
        }

        public int getPending() {
            return pending;
        }

        public int getUpserted() {
            return upserted;
        }

        public int getFailed() {
            return failed;
        }

        public int getInFlight() {
            return inFlight;
        }

        public int getDeletePending() {
            return deletePending;
        }

        public int getDeleted() {
            return deleted;
        }
    }
}
