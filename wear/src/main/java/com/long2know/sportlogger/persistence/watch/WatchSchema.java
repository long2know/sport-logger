package com.long2know.sportlogger.persistence.watch;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Dao;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.PrimaryKey;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Entity(
        tableName = "watch_sessions",
        indices = {
                @Index(value = {"legacy_source_key", "legacy_activity_id"}, unique = true),
                @Index(value = {"state", "updated_at_utc_ms"})
        })
final class WatchSessionEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "session_id")
    public String sessionId;
    @ColumnInfo(name = "watch_revision")
    public long watchRevision;
    @NonNull
    public String state;
    @ColumnInfo(name = "activity_type")
    public int activityType;
    @ColumnInfo(name = "started_at_utc_ms")
    public long startedAtUtcMs;
    @ColumnInfo(name = "ended_at_utc_ms")
    public Long endedAtUtcMs;
    @ColumnInfo(name = "elapsed_duration_ms")
    public Long elapsedDurationMs;
    @ColumnInfo(name = "active_duration_ms")
    public Long activeDurationMs;
    @ColumnInfo(name = "time_zone_id")
    public String timeZoneId;
    @ColumnInfo(name = "utc_offset_minutes")
    public Integer utcOffsetMinutes;
    @ColumnInfo(name = "boot_id")
    public String bootId;
    @ColumnInfo(name = "monotonic_anchor_ms")
    public Long monotonicAnchorMs;
    @ColumnInfo(name = "active_since_monotonic_ms")
    public Long activeSinceMonotonicMs;
    @ColumnInfo(name = "accumulated_active_ms")
    public long accumulatedActiveMs;
    @ColumnInfo(name = "lease_token")
    public long leaseToken;
    @ColumnInfo(name = "sample_count")
    public long sampleCount;
    @ColumnInfo(name = "route_point_count")
    public long routePointCount;
    @ColumnInfo(name = "heart_rate_sample_count")
    public long heartRateSampleCount;
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB)
    public byte[] payload;
    @ColumnInfo(name = "payload_sha256")
    public String payloadSha256;
    @ColumnInfo(name = "legacy_source_key")
    public String legacySourceKey;
    @ColumnInfo(name = "legacy_activity_id")
    public Long legacyActivityId;
    @ColumnInfo(name = "last_error_code")
    public String lastErrorCode;
    @ColumnInfo(name = "last_error_message")
    public String lastErrorMessage;
    @ColumnInfo(name = "created_at_utc_ms")
    public long createdAtUtcMs;
    @ColumnInfo(name = "updated_at_utc_ms")
    public long updatedAtUtcMs;
}

@Entity(
        tableName = "watch_samples",
        primaryKeys = {"session_id", "sequence"},
        foreignKeys = @ForeignKey(
                entity = WatchSessionEntity.class,
                parentColumns = "session_id",
                childColumns = "session_id",
                onDelete = ForeignKey.CASCADE),
        indices = {
                @Index("session_id"),
                @Index(value = {"session_id", "timestamp_utc_ms"})
        })
final class WatchSampleEntity {
    @NonNull
    @ColumnInfo(name = "session_id")
    public String sessionId;
    public long sequence;
    @ColumnInfo(name = "timestamp_utc_ms")
    public long timestampUtcMs;
    @ColumnInfo(name = "monotonic_time_ms")
    public Long monotonicTimeMs;
    @ColumnInfo(name = "session_active_duration_ms")
    public Long sessionActiveDurationMs;
    @ColumnInfo(name = "has_location")
    public boolean hasLocation;
    @ColumnInfo(name = "has_heart_rate")
    public boolean hasHeartRate;
    @NonNull
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB)
    public byte[] payload;
    @NonNull
    @ColumnInfo(name = "payload_sha256")
    public String payloadSha256;
}

@Entity(
        tableName = "watch_activity_outbox",
        foreignKeys = @ForeignKey(
                entity = WatchSessionEntity.class,
                parentColumns = "session_id",
                childColumns = "session_id",
                onDelete = ForeignKey.CASCADE),
        indices = {
                @Index("session_id"),
                @Index(value = {"session_id", "session_revision"}, unique = true),
                @Index(value = {"state", "created_at_utc_ms"})
        })
final class WatchOutboxEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "transfer_id")
    public String transferId;
    @NonNull
    @ColumnInfo(name = "session_id")
    public String sessionId;
    @ColumnInfo(name = "session_revision")
    public long sessionRevision;
    @NonNull
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB)
    public byte[] payload;
    @NonNull
    @ColumnInfo(name = "payload_sha256")
    public String payloadSha256;
    @ColumnInfo(name = "payload_length")
    public long payloadLength;
    @NonNull
    public String state;
    @ColumnInfo(name = "created_at_utc_ms")
    public long createdAtUtcMs;
    @ColumnInfo(name = "last_attempt_at_utc_ms")
    public Long lastAttemptAtUtcMs;
    @ColumnInfo(name = "attempt_count")
    public long attemptCount;
    @ColumnInfo(name = "last_failure_code")
    public String lastFailureCode;
    @ColumnInfo(name = "last_failure_message")
    public String lastFailureMessage;
    @ColumnInfo(name = "commit_ack_payload", typeAffinity = ColumnInfo.BLOB)
    public byte[] commitAckPayload;
    @ColumnInfo(name = "committed_at_utc_ms")
    public Long committedAtUtcMs;
}

@Entity(
        tableName = "watch_migration_receipts",
        indices = {
                @Index("source_key"),
                @Index(value = {"source_key", "source_sha256"}, unique = true)
        })
final class WatchMigrationReceiptEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "receipt_id")
    public String receiptId;
    @NonNull
    @ColumnInfo(name = "source_key")
    public String sourceKey;
    @NonNull
    @ColumnInfo(name = "source_identity")
    public String sourceIdentity;
    @NonNull
    @ColumnInfo(name = "source_sha256")
    public String sourceSha256;
    @ColumnInfo(name = "source_size")
    public long sourceSize;
    @NonNull
    public String status;
    @ColumnInfo(name = "source_activity_count")
    public long sourceActivityCount;
    @ColumnInfo(name = "source_sample_count")
    public long sourceSampleCount;
    @ColumnInfo(name = "imported_activity_count")
    public long importedActivityCount;
    @ColumnInfo(name = "imported_sample_count")
    public long importedSampleCount;
    @ColumnInfo(name = "orphan_count")
    public long orphanCount;
    @ColumnInfo(name = "quarantine_count")
    public long quarantineCount;
    @ColumnInfo(name = "min_timestamp_utc_ms")
    public Long minTimestampUtcMs;
    @ColumnInfo(name = "max_timestamp_utc_ms")
    public Long maxTimestampUtcMs;
    @NonNull
    @ColumnInfo(name = "canonical_sha256")
    public String canonicalSha256;
    @ColumnInfo(name = "completed_at_utc_ms")
    public long completedAtUtcMs;
}

@Entity(
        tableName = "watch_migration_quarantine",
        indices = @Index(
                value = {"source_key", "source_table", "source_row_id", "code"},
                unique = true))
final class WatchMigrationQuarantineEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    @NonNull
    @ColumnInfo(name = "source_key")
    public String sourceKey;
    @NonNull
    @ColumnInfo(name = "source_table")
    public String sourceTable;
    @ColumnInfo(name = "source_row_id")
    public Long sourceRowId;
    @NonNull
    public String code;
    @NonNull
    public String detail;
    @ColumnInfo(name = "raw_value")
    public String rawValue;
    @NonNull
    @ColumnInfo(name = "raw_sha256")
    public String rawSha256;
    @ColumnInfo(name = "created_at_utc_ms")
    public long createdAtUtcMs;
}

@Entity(
        tableName = "watch_tombstones",
        indices = @Index("transfer_id"))
final class WatchTombstoneEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "session_id")
    public String sessionId;
    @ColumnInfo(name = "delete_revision")
    public long deleteRevision;
    @ColumnInfo(name = "covered_session_revision")
    public long coveredSessionRevision;
    public int origin;
    @NonNull
    @ColumnInfo(name = "transfer_id")
    public String transferId;
    @ColumnInfo(name = "deleted_at_utc_ms")
    public long deletedAtUtcMs;
    @NonNull
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB)
    public byte[] payload;
    @NonNull
    @ColumnInfo(name = "payload_sha256")
    public String payloadSha256;
}

@Entity(
        tableName = "watch_tombstone_outbox",
        indices = {
                @Index("session_id"),
                @Index(value = {"state", "created_at_utc_ms"})
        })
final class WatchTombstoneOutboxEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "transfer_id")
    public String transferId;
    @NonNull
    @ColumnInfo(name = "session_id")
    public String sessionId;
    @ColumnInfo(name = "delete_revision")
    public long deleteRevision;
    @ColumnInfo(name = "covered_session_revision")
    public long coveredSessionRevision;
    public int origin;
    @NonNull
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB)
    public byte[] payload;
    @NonNull
    @ColumnInfo(name = "payload_sha256")
    public String payloadSha256;
    @ColumnInfo(name = "payload_length")
    public long payloadLength;
    @NonNull
    public String state;
    @ColumnInfo(name = "created_at_utc_ms")
    public long createdAtUtcMs;
    @ColumnInfo(name = "last_attempt_at_utc_ms")
    public Long lastAttemptAtUtcMs;
    @ColumnInfo(name = "attempt_count")
    public long attemptCount;
    @ColumnInfo(name = "last_failure_code")
    public String lastFailureCode;
    @ColumnInfo(name = "last_failure_message")
    public String lastFailureMessage;
    @ColumnInfo(name = "commit_ack_payload", typeAffinity = ColumnInfo.BLOB)
    public byte[] commitAckPayload;
    @ColumnInfo(name = "committed_at_utc_ms")
    public Long committedAtUtcMs;
}

@Entity(
        tableName = "watch_tombstone_receipts",
        indices = @Index("session_id"))
final class WatchTombstoneReceiptEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "transfer_id")
    public String transferId;
    @NonNull
    @ColumnInfo(name = "session_id")
    public String sessionId;
    @ColumnInfo(name = "delete_revision")
    public long deleteRevision;
    @ColumnInfo(name = "covered_session_revision")
    public long coveredSessionRevision;
    public int origin;
    @NonNull
    @ColumnInfo(name = "payload_sha256")
    public String payloadSha256;
    @ColumnInfo(name = "committed_at_utc_ms")
    public long committedAtUtcMs;
    @NonNull
    @ColumnInfo(name = "ack_payload", typeAffinity = ColumnInfo.BLOB)
    public byte[] ackPayload;
}

@Dao
interface WatchDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertSession(WatchSessionEntity session);

    @Update
    int updateSession(WatchSessionEntity session);

    @Query("SELECT * FROM watch_sessions WHERE session_id = :sessionId")
    WatchSessionEntity session(String sessionId);

    @Query("SELECT * FROM watch_sessions WHERE legacy_source_key = :sourceKey "
            + "AND legacy_activity_id = :legacyActivityId")
    WatchSessionEntity legacySession(String sourceKey, long legacyActivityId);

    @Query("UPDATE watch_sessions SET state = :state, boot_id = :bootId, "
            + "active_since_monotonic_ms = :activeSinceMonotonicMs, "
            + "accumulated_active_ms = :accumulatedActiveMs, "
            + "last_error_code = :errorCode, last_error_message = :errorMessage, "
            + "updated_at_utc_ms = :updatedAtUtcMs "
            + "WHERE session_id = :sessionId AND lease_token = :leaseToken "
            + "AND state NOT IN ('COMPLETED', 'DISCARDED')")
    int updateLifecycle(
            String sessionId,
            long leaseToken,
            String state,
            String bootId,
            Long activeSinceMonotonicMs,
            long accumulatedActiveMs,
            String errorCode,
            String errorMessage,
            long updatedAtUtcMs);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertSample(WatchSampleEntity sample);

    @Query("SELECT * FROM watch_samples WHERE session_id = :sessionId AND sequence = :sequence")
    WatchSampleEntity sample(String sessionId, long sequence);

    @Query("SELECT * FROM watch_samples WHERE session_id = :sessionId ORDER BY sequence")
    List<WatchSampleEntity> samples(String sessionId);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertOutbox(WatchOutboxEntity outbox);

    @Query("SELECT * FROM watch_activity_outbox WHERE transfer_id = :transferId")
    WatchOutboxEntity outbox(String transferId);

    @Update
    int updateOutbox(WatchOutboxEntity outbox);

    @Query("SELECT * FROM watch_activity_outbox WHERE state IN ('READY', 'RETRYABLE') "
            + "ORDER BY created_at_utc_ms, transfer_id LIMIT :limit")
    List<WatchOutboxEntity> readyOutbox(int limit);

    @Query("SELECT * FROM watch_activity_outbox "
            + "WHERE state IN ('READY', 'RETRYABLE', 'SENDING', 'AWAITING_COMMIT_ACK') "
            + "ORDER BY created_at_utc_ms, transfer_id LIMIT :limit")
    List<WatchOutboxEntity> pendingOutbox(int limit);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertMigrationReceipt(WatchMigrationReceiptEntity receipt);

    @Query("SELECT * FROM watch_migration_receipts WHERE receipt_id = :receiptId")
    WatchMigrationReceiptEntity migrationReceipt(String receiptId);

    @Query("SELECT COUNT(*) FROM watch_migration_receipts WHERE source_key = :sourceKey")
    long migrationReceiptCount(String sourceKey);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertQuarantine(WatchMigrationQuarantineEntity quarantine);

    @Query("SELECT COUNT(*) FROM watch_migration_quarantine WHERE source_key = :sourceKey")
    long quarantineCount(String sourceKey);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putTombstone(WatchTombstoneEntity tombstone);

    @Query("SELECT * FROM watch_tombstones WHERE session_id = :sessionId")
    WatchTombstoneEntity tombstone(String sessionId);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertTombstoneOutbox(WatchTombstoneOutboxEntity outbox);

    @Query("SELECT * FROM watch_tombstone_outbox WHERE transfer_id = :transferId")
    WatchTombstoneOutboxEntity tombstoneOutbox(String transferId);

    @Update
    int updateTombstoneOutbox(WatchTombstoneOutboxEntity outbox);

    @Query("SELECT * FROM watch_tombstone_outbox "
            + "WHERE state IN ('READY', 'RETRYABLE', 'SENDING', 'AWAITING_COMMIT_ACK') "
            + "ORDER BY created_at_utc_ms, transfer_id LIMIT :limit")
    List<WatchTombstoneOutboxEntity> pendingTombstoneOutbox(int limit);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertTombstoneReceipt(WatchTombstoneReceiptEntity receipt);

    @Query("SELECT * FROM watch_tombstone_receipts WHERE transfer_id = :transferId")
    WatchTombstoneReceiptEntity tombstoneReceipt(String transferId);
}
