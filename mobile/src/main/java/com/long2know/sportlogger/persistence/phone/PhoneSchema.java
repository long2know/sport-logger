package com.long2know.sportlogger.persistence.phone;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Dao;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.PrimaryKey;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Entity(
        tableName = "phone_session_heads",
        indices = {
                @Index(value = {"deleted", "updated_at_utc_ms"}),
                @Index("accepted_revision")
        })
final class PhoneSessionHeadEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "session_id")
    public String sessionId;
    @ColumnInfo(name = "accepted_revision")
    public long acceptedRevision;
    public boolean deleted;
    @NonNull
    @ColumnInfo(name = "transfer_state")
    public String transferState;
    @ColumnInfo(name = "last_transfer_id")
    public String lastTransferId;
    @NonNull
    @ColumnInfo(name = "import_health")
    public String importHealth;
    @ColumnInfo(name = "import_error_code")
    public String importErrorCode;
    @ColumnInfo(name = "updated_at_utc_ms")
    public long updatedAtUtcMs;
}

@Entity(
        tableName = "phone_activity_revisions",
        primaryKeys = {"session_id", "revision"},
        indices = {
                @Index(value = {"start_time_utc_ms", "session_id"}),
                @Index("payload_sha256")
        })
final class PhoneActivityRevisionEntity {
    @NonNull
    @ColumnInfo(name = "session_id")
    public String sessionId;
    public long revision;
    @ColumnInfo(name = "activity_type")
    public int activityType;
    @ColumnInfo(name = "start_time_utc_ms")
    public long startTimeUtcMs;
    @ColumnInfo(name = "end_time_utc_ms")
    public long endTimeUtcMs;
    @ColumnInfo(name = "elapsed_duration_ms")
    public long elapsedDurationMs;
    @ColumnInfo(name = "active_duration_ms")
    public Long activeDurationMs;
    @ColumnInfo(name = "distance_m")
    public Double distanceM;
    @ColumnInfo(name = "calories_kcal")
    public Double caloriesKcal;
    public Long steps;
    @ColumnInfo(name = "sample_count")
    public long sampleCount;
    @ColumnInfo(name = "route_point_count")
    public long routePointCount;
    @ColumnInfo(name = "heart_rate_sample_count")
    public long heartRateSampleCount;
    @ColumnInfo(name = "time_zone_id")
    public String timeZoneId;
    @ColumnInfo(name = "utc_offset_minutes")
    public Integer utcOffsetMinutes;
    @ColumnInfo(name = "source_kind")
    public int sourceKind;
    @ColumnInfo(name = "source_provider")
    public String sourceProvider;
    @ColumnInfo(name = "source_manufacturer")
    public String sourceManufacturer;
    @ColumnInfo(name = "source_model")
    public String sourceModel;
    @ColumnInfo(name = "source_device_id")
    public String sourceDeviceId;
    @NonNull
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB)
    public byte[] payload;
    @NonNull
    @ColumnInfo(name = "payload_sha256")
    public String payloadSha256;
    @ColumnInfo(name = "accepted_at_utc_ms")
    public long acceptedAtUtcMs;
}

@Entity(
        tableName = "phone_points",
        primaryKeys = {"session_id", "revision", "sequence"},
        indices = {
                @Index(value = {"session_id", "revision", "sequence"}),
                @Index(value = {"session_id", "revision", "timestamp_utc_ms"})
        })
final class PhonePointEntity {
    @NonNull
    @ColumnInfo(name = "session_id")
    public String sessionId;
    public long revision;
    public long sequence;
    @ColumnInfo(name = "timestamp_utc_ms")
    public long timestampUtcMs;
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

@Entity(tableName = "phone_metadata")
final class PhoneMetadataEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "session_id")
    public String sessionId;
    public String title;
    public String notes;
    @NonNull
    @ColumnInfo(name = "tags_text")
    public String tagsText;
    @ColumnInfo(name = "updated_at_utc_ms")
    public long updatedAtUtcMs;
}

@Entity(
        tableName = "phone_inbox_receipts",
        indices = {
                @Index(value = {"session_id", "accepted_revision"}),
                @Index("payload_sha256")
        })
final class PhoneInboxReceiptEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "transfer_id")
    public String transferId;
    @NonNull
    @ColumnInfo(name = "session_id")
    public String sessionId;
    @ColumnInfo(name = "incoming_revision")
    public long incomingRevision;
    @ColumnInfo(name = "accepted_revision")
    public long acceptedRevision;
    @NonNull
    @ColumnInfo(name = "payload_sha256")
    public String payloadSha256;
    @ColumnInfo(name = "payload_length")
    public long payloadLength;
    @NonNull
    public String result;
    @ColumnInfo(name = "committed_at_utc_ms")
    public long committedAtUtcMs;
    @NonNull
    @ColumnInfo(name = "ack_payload", typeAffinity = ColumnInfo.BLOB)
    public byte[] ackPayload;
}

@Entity(tableName = "phone_tombstones", indices = @Index("transfer_id"))
final class PhoneTombstoneEntity {
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
        tableName = "phone_tombstone_outbox",
        indices = {
                @Index("session_id"),
                @Index(value = {"state", "created_at_utc_ms"})
        })
final class PhoneTombstoneOutboxEntity {
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
        tableName = "phone_tombstone_receipts",
        indices = @Index("session_id"))
final class PhoneTombstoneReceiptEntity {
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

@Entity(
        tableName = "connector_ledgers",
        primaryKeys = {
                "session_id",
                "session_revision",
                "connector_id",
                "normalized_instance_url",
                "connection_identity"
        },
        indices = {
                @Index(value = {"state", "retry_not_before_utc_ms"}),
                @Index(value = {"session_id", "session_revision"})
        })
final class ConnectorLedgerEntity {
    @NonNull
    @ColumnInfo(name = "session_id")
    public String sessionId;
    @ColumnInfo(name = "session_revision")
    public long sessionRevision;
    @NonNull
    @ColumnInfo(name = "connector_id")
    public String connectorId;
    @NonNull
    @ColumnInfo(name = "normalized_instance_url")
    public String normalizedInstanceUrl;
    @NonNull
    @ColumnInfo(name = "connection_identity")
    public String connectionIdentity;
    @NonNull
    @ColumnInfo(name = "credential_reference")
    public String credentialReference;
    @ColumnInfo(name = "artifact_length")
    public long artifactLength;
    @NonNull
    @ColumnInfo(name = "artifact_sha256")
    public String artifactSha256;
    @NonNull
    public String state;
    @ColumnInfo(name = "lease_token")
    public long leaseToken;
    @ColumnInfo(name = "lease_owner")
    public String leaseOwner;
    @ColumnInfo(name = "lease_expires_at_utc_ms")
    public Long leaseExpiresAtUtcMs;
    @ColumnInfo(name = "retry_not_before_utc_ms")
    public Long retryNotBeforeUtcMs;
    @ColumnInfo(name = "last_failure_code")
    public String lastFailureCode;
    @ColumnInfo(name = "last_failure_message")
    public String lastFailureMessage;
    @ColumnInfo(name = "delete_unsupported")
    public boolean deleteUnsupported;
    @ColumnInfo(name = "created_at_utc_ms")
    public long createdAtUtcMs;
    @ColumnInfo(name = "updated_at_utc_ms")
    public long updatedAtUtcMs;
}

@Entity(
        tableName = "connector_remote_ids",
        primaryKeys = {
                "session_id",
                "session_revision",
                "connector_id",
                "normalized_instance_url",
                "connection_identity",
                "remote_id"
        },
        indices = @Index(value = {
                "session_id",
                "session_revision",
                "connector_id",
                "normalized_instance_url",
                "connection_identity"
        }))
final class ConnectorRemoteIdEntity {
    @NonNull
    @ColumnInfo(name = "session_id")
    public String sessionId;
    @ColumnInfo(name = "session_revision")
    public long sessionRevision;
    @NonNull
    @ColumnInfo(name = "connector_id")
    public String connectorId;
    @NonNull
    @ColumnInfo(name = "normalized_instance_url")
    public String normalizedInstanceUrl;
    @NonNull
    @ColumnInfo(name = "connection_identity")
    public String connectionIdentity;
    @ColumnInfo(name = "remote_id")
    public long remoteId;
    @ColumnInfo(name = "recorded_at_utc_ms")
    public long recordedAtUtcMs;
}

@Entity(
        tableName = "phone_legacy_backfill_receipts",
        indices = @Index("session_id"))
final class PhoneLegacyBackfillReceiptEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "source_file_key")
    public String sourceFileKey;
    @NonNull
    @ColumnInfo(name = "source_sha256")
    public String sourceSha256;
    @ColumnInfo(name = "session_id")
    public String sessionId;
    @NonNull
    public String status;
    @ColumnInfo(name = "error_code")
    public String errorCode;
    @ColumnInfo(name = "completed_at_utc_ms")
    public long completedAtUtcMs;
}

@Entity(
        tableName = "health_connect_records",
        primaryKeys = {
                "session_id",
                "session_revision",
                "record_type",
                "chunk_index"
        },
        indices = {
                @Index(value = {"state", "updated_at_utc_ms"}),
                @Index("client_record_id"),
                @Index("provider_record_id")
        })
final class HealthConnectRecordEntity {
    @NonNull
    @ColumnInfo(name = "session_id")
    public String sessionId;
    @ColumnInfo(name = "session_revision")
    public long sessionRevision;
    @NonNull
    @ColumnInfo(name = "record_type")
    public String recordType;
    @ColumnInfo(name = "chunk_index")
    public int chunkIndex;
    @NonNull
    @ColumnInfo(name = "client_record_id")
    public String clientRecordId;
    @ColumnInfo(name = "client_record_version")
    public long clientRecordVersion;
    @ColumnInfo(name = "provider_record_id")
    public String providerRecordId;
    @NonNull
    @ColumnInfo(name = "mapping_sha256")
    public String mappingSha256;
    @NonNull
    public String state;
    @NonNull
    public String operation;
    @ColumnInfo(name = "attempt_count")
    public long attemptCount;
    @ColumnInfo(name = "last_attempt_at_utc_ms")
    public Long lastAttemptAtUtcMs;
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
        tableName = "health_connect_owned_record_ids",
        primaryKeys = {
                "session_id",
                "session_revision",
                "record_type",
                "chunk_index",
                "provider_record_id"
        },
        indices = @Index(value = {
                "session_id",
                "session_revision",
                "record_type",
                "chunk_index"
        }))
final class HealthConnectOwnedRecordIdEntity {
    @NonNull
    @ColumnInfo(name = "session_id")
    public String sessionId;
    @ColumnInfo(name = "session_revision")
    public long sessionRevision;
    @NonNull
    @ColumnInfo(name = "record_type")
    public String recordType;
    @ColumnInfo(name = "chunk_index")
    public int chunkIndex;
    @NonNull
    @ColumnInfo(name = "provider_record_id")
    public String providerRecordId;
    @ColumnInfo(name = "recorded_at_utc_ms")
    public long recordedAtUtcMs;
}

@Entity(
        tableName = "health_connect_deletion_receipts",
        indices = @Index(value = {
                "session_id",
                "session_revision",
                "record_type",
                "chunk_index"
        }))
final class HealthConnectDeletionReceiptEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "receipt_id")
    public String receiptId;
    @NonNull
    @ColumnInfo(name = "session_id")
    public String sessionId;
    @ColumnInfo(name = "session_revision")
    public long sessionRevision;
    @NonNull
    @ColumnInfo(name = "record_type")
    public String recordType;
    @ColumnInfo(name = "chunk_index")
    public int chunkIndex;
    @NonNull
    @ColumnInfo(name = "client_record_id")
    public String clientRecordId;
    @ColumnInfo(name = "client_record_version")
    public long clientRecordVersion;
    @ColumnInfo(name = "provider_record_id")
    public String providerRecordId;
    @NonNull
    public String result;
    @ColumnInfo(name = "error_code")
    public String errorCode;
    @ColumnInfo(name = "deleted_at_utc_ms")
    public long deletedAtUtcMs;
}

@Entity(
        tableName = "health_connect_client_versions",
        primaryKeys = {"session_id", "client_record_id"})
final class HealthConnectClientVersionEntity {
    @NonNull
    @ColumnInfo(name = "session_id")
    public String sessionId;
    @NonNull
    @ColumnInfo(name = "client_record_id")
    public String clientRecordId;
    @ColumnInfo(name = "allocated_version")
    public long allocatedVersion;
    @ColumnInfo(name = "updated_at_utc_ms")
    public long updatedAtUtcMs;
}

@Entity(
        tableName = "health_connect_operation_receipts",
        indices = @Index(value = {
                "session_id",
                "session_revision",
                "record_type",
                "chunk_index"
        }))
final class HealthConnectOperationReceiptEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "receipt_id")
    public String receiptId;
    @NonNull
    @ColumnInfo(name = "session_id")
    public String sessionId;
    @ColumnInfo(name = "session_revision")
    public long sessionRevision;
    @NonNull
    @ColumnInfo(name = "record_type")
    public String recordType;
    @ColumnInfo(name = "chunk_index")
    public int chunkIndex;
    @NonNull
    @ColumnInfo(name = "client_record_id")
    public String clientRecordId;
    @ColumnInfo(name = "client_record_version")
    public long clientRecordVersion;
    @NonNull
    public String operation;
    @NonNull
    public String phase;
    @NonNull
    public String result;
    @ColumnInfo(name = "provider_record_id")
    public String providerRecordId;
    @ColumnInfo(name = "error_code")
    public String errorCode;
    @ColumnInfo(name = "recorded_at_utc_ms")
    public long recordedAtUtcMs;
}

final class PhoneSummaryRow {
    @ColumnInfo(name = "session_id")
    public String sessionId;
    public long revision;
    @ColumnInfo(name = "activity_type")
    public int activityType;
    @ColumnInfo(name = "start_time_utc_ms")
    public long startTimeUtcMs;
    @ColumnInfo(name = "end_time_utc_ms")
    public long endTimeUtcMs;
    @ColumnInfo(name = "elapsed_duration_ms")
    public long elapsedDurationMs;
    @ColumnInfo(name = "active_duration_ms")
    public Long activeDurationMs;
    @ColumnInfo(name = "distance_m")
    public Double distanceM;
    @ColumnInfo(name = "sample_count")
    public long sampleCount;
    @ColumnInfo(name = "route_point_count")
    public long routePointCount;
    @ColumnInfo(name = "heart_rate_sample_count")
    public long heartRateSampleCount;
    @ColumnInfo(name = "transfer_state")
    public String transferState;
    @ColumnInfo(name = "last_transfer_id")
    public String lastTransferId;
    @ColumnInfo(name = "import_health")
    public String importHealth;
    public String title;
}

@Dao
interface PhoneDao {
    @Query("SELECT * FROM phone_session_heads WHERE session_id = :sessionId")
    PhoneSessionHeadEntity head(String sessionId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putHead(PhoneSessionHeadEntity head);

    @Query("SELECT * FROM phone_activity_revisions WHERE session_id = :sessionId "
            + "AND revision = :revision")
    PhoneActivityRevisionEntity revision(String sessionId, long revision);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertRevision(PhoneActivityRevisionEntity revision);

    @Query("SELECT * FROM phone_points WHERE session_id = :sessionId AND revision = :revision "
            + "AND sequence = :sequence")
    PhonePointEntity point(String sessionId, long revision, long sequence);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertPoint(PhonePointEntity point);

    @Query("SELECT h.session_id AS session_id, r.revision AS revision, "
            + "r.activity_type AS activity_type, r.start_time_utc_ms AS start_time_utc_ms, "
            + "r.end_time_utc_ms AS end_time_utc_ms, "
            + "r.elapsed_duration_ms AS elapsed_duration_ms, "
            + "r.active_duration_ms AS active_duration_ms, r.distance_m AS distance_m, "
            + "r.sample_count AS sample_count, r.route_point_count AS route_point_count, "
            + "r.heart_rate_sample_count AS heart_rate_sample_count, "
            + "h.transfer_state AS transfer_state, h.last_transfer_id AS last_transfer_id, "
            + "h.import_health AS import_health, m.title AS title "
            + "FROM phone_session_heads h "
            + "JOIN phone_activity_revisions r ON r.session_id = h.session_id "
            + "AND r.revision = h.accepted_revision "
            + "LEFT JOIN phone_metadata m ON m.session_id = h.session_id "
            + "WHERE h.deleted = 0 "
            + "ORDER BY r.start_time_utc_ms DESC, h.session_id ASC "
            + "LIMIT :limit OFFSET :offset")
    List<PhoneSummaryRow> summaries(int offset, int limit);

    @Query("SELECT COUNT(*) FROM phone_session_heads WHERE deleted = 0")
    long summaryCount();

    @Query("SELECT * FROM phone_metadata WHERE session_id = :sessionId")
    PhoneMetadataEntity metadata(String sessionId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putMetadata(PhoneMetadataEntity metadata);

    @Query("SELECT * FROM phone_inbox_receipts WHERE transfer_id = :transferId")
    PhoneInboxReceiptEntity inboxReceipt(String transferId);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertInboxReceipt(PhoneInboxReceiptEntity receipt);

    @Query("SELECT * FROM phone_tombstones WHERE session_id = :sessionId")
    PhoneTombstoneEntity tombstone(String sessionId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putTombstone(PhoneTombstoneEntity tombstone);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertTombstoneOutbox(PhoneTombstoneOutboxEntity outbox);

    @Query("SELECT * FROM phone_tombstone_outbox WHERE transfer_id = :transferId")
    PhoneTombstoneOutboxEntity tombstoneOutbox(String transferId);

    @Update
    int updateTombstoneOutbox(PhoneTombstoneOutboxEntity outbox);

    @Query("SELECT * FROM phone_tombstone_outbox "
            + "WHERE state IN ('READY', 'RETRYABLE', 'SENDING', 'AWAITING_COMMIT_ACK') "
            + "ORDER BY created_at_utc_ms, transfer_id LIMIT :limit")
    List<PhoneTombstoneOutboxEntity> pendingTombstoneOutbox(int limit);

    @Query("SELECT * FROM phone_tombstone_receipts WHERE transfer_id = :transferId")
    PhoneTombstoneReceiptEntity tombstoneReceipt(String transferId);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertTombstoneReceipt(PhoneTombstoneReceiptEntity receipt);

    @Query("SELECT * FROM connector_ledgers WHERE session_id = :sessionId "
            + "AND session_revision = :revision AND connector_id = :connectorId "
            + "AND normalized_instance_url = :instanceUrl "
            + "AND connection_identity = :connectionIdentity")
    ConnectorLedgerEntity connectorLedger(
            String sessionId,
            long revision,
            String connectorId,
            String instanceUrl,
            String connectionIdentity);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertConnectorLedger(ConnectorLedgerEntity ledger);

    @Update
    int updateConnectorLedger(ConnectorLedgerEntity ledger);

    @Query("UPDATE connector_ledgers SET lease_token = :newLeaseToken, "
            + "lease_owner = :leaseOwner, lease_expires_at_utc_ms = :leaseExpiresAtUtcMs, "
            + "updated_at_utc_ms = :updatedAtUtcMs "
            + "WHERE session_id = :sessionId AND session_revision = :revision "
            + "AND connector_id = :connectorId "
            + "AND normalized_instance_url = :instanceUrl "
            + "AND connection_identity = :connectionIdentity "
            + "AND state = :expectedState "
            + "AND (lease_token = 0 OR lease_expires_at_utc_ms < :updatedAtUtcMs)")
    int claimConnectorLedger(
            String sessionId,
            long revision,
            String connectorId,
            String instanceUrl,
            String connectionIdentity,
            String expectedState,
            long newLeaseToken,
            String leaseOwner,
            long leaseExpiresAtUtcMs,
            long updatedAtUtcMs);

    @Query("UPDATE connector_ledgers SET state = 'UNKNOWN_DELIVERY', "
            + "lease_token = 0, lease_owner = NULL, lease_expires_at_utc_ms = NULL, "
            + "retry_not_before_utc_ms = NULL, "
            + "last_failure_code = 'PROCESS_RESTART_AMBIGUOUS', "
            + "last_failure_message = 'Recovered expired claimed delivery after process restart', "
            + "updated_at_utc_ms = :nowUtcMs "
            + "WHERE session_id = :sessionId AND session_revision = :revision "
            + "AND connector_id = :connectorId "
            + "AND normalized_instance_url = :instanceUrl "
            + "AND connection_identity = :connectionIdentity "
            + "AND state = 'PENDING' AND lease_token != 0 "
            + "AND lease_expires_at_utc_ms IS NOT NULL "
            + "AND lease_expires_at_utc_ms <= :nowUtcMs")
    int recoverExpiredConnectorClaimAsUnknown(
            String sessionId,
            long revision,
            String connectorId,
            String instanceUrl,
            String connectionIdentity,
            long nowUtcMs);

    @Query("SELECT remote_id FROM connector_remote_ids WHERE session_id = :sessionId "
            + "AND session_revision = :revision AND connector_id = :connectorId "
            + "AND normalized_instance_url = :instanceUrl "
            + "AND connection_identity = :connectionIdentity ORDER BY remote_id")
    List<Long> connectorRemoteIds(
            String sessionId,
            long revision,
            String connectorId,
            String instanceUrl,
            String connectionIdentity);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertConnectorRemoteId(ConnectorRemoteIdEntity remoteId);

    @Query("SELECT * FROM phone_legacy_backfill_receipts WHERE source_file_key = :sourceFileKey")
    PhoneLegacyBackfillReceiptEntity legacyBackfillReceipt(String sourceFileKey);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertLegacyBackfillReceipt(PhoneLegacyBackfillReceiptEntity receipt);

    @Query("SELECT * FROM health_connect_records WHERE session_id = :sessionId "
            + "AND session_revision = :revision AND record_type = :recordType "
            + "AND chunk_index = :chunkIndex")
    HealthConnectRecordEntity healthConnectRecord(
            String sessionId,
            long revision,
            String recordType,
            int chunkIndex);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertHealthConnectRecord(HealthConnectRecordEntity record);

    @Update
    int updateHealthConnectRecord(HealthConnectRecordEntity record);

    @Query("SELECT * FROM health_connect_records WHERE session_id = :sessionId "
            + "AND session_revision = :revision ORDER BY record_type, chunk_index")
    List<HealthConnectRecordEntity> healthConnectRecords(
            String sessionId,
            long revision);

    @Query("SELECT * FROM health_connect_records WHERE session_id = :sessionId "
            + "ORDER BY session_revision, record_type, chunk_index")
    List<HealthConnectRecordEntity> healthConnectRecordsForSession(String sessionId);

    @Query("SELECT * FROM health_connect_records WHERE session_id = :sessionId "
            + "AND session_revision = :revision AND state IN (:states) "
            + "ORDER BY record_type, chunk_index")
    List<HealthConnectRecordEntity> healthConnectRecordsInStates(
            String sessionId,
            long revision,
            List<String> states);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertHealthConnectOwnedRecordId(HealthConnectOwnedRecordIdEntity recordId);

    @Query("SELECT provider_record_id FROM health_connect_owned_record_ids "
            + "WHERE session_id = :sessionId AND session_revision = :revision "
            + "AND record_type = :recordType AND chunk_index = :chunkIndex "
            + "ORDER BY recorded_at_utc_ms, provider_record_id")
    List<String> healthConnectOwnedRecordIds(
            String sessionId,
            long revision,
            String recordType,
            int chunkIndex);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertHealthConnectDeletionReceipt(HealthConnectDeletionReceiptEntity receipt);

    @Query("SELECT * FROM health_connect_deletion_receipts WHERE session_id = :sessionId "
            + "AND session_revision = :revision ORDER BY deleted_at_utc_ms, receipt_id")
    List<HealthConnectDeletionReceiptEntity> healthConnectDeletionReceipts(
            String sessionId,
            long revision);

    @Query("SELECT * FROM health_connect_client_versions WHERE session_id = :sessionId "
            + "AND client_record_id = :clientRecordId")
    HealthConnectClientVersionEntity healthConnectClientVersion(
            String sessionId,
            String clientRecordId);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertHealthConnectClientVersion(HealthConnectClientVersionEntity version);

    @Update
    int updateHealthConnectClientVersion(HealthConnectClientVersionEntity version);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertHealthConnectOperationReceipt(HealthConnectOperationReceiptEntity receipt);

    @Query("SELECT * FROM health_connect_operation_receipts WHERE session_id = :sessionId "
            + "AND session_revision = :revision ORDER BY recorded_at_utc_ms, receipt_id")
    List<HealthConnectOperationReceiptEntity> healthConnectOperationReceipts(
            String sessionId,
            long revision);
}
