package com.long2know.sportlogger.persistence.watch;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import com.long2know.utilities.contract.CanonicalContractException;
import com.long2know.utilities.contract.CanonicalIds;
import com.long2know.utilities.contract.CanonicalPayload;
import com.long2know.utilities.contract.proto.ActivityRecord;
import com.long2know.utilities.contract.proto.ActivitySummary;
import com.long2know.utilities.contract.proto.ActivityType;
import com.long2know.utilities.contract.proto.AggregationSemantics;
import com.long2know.utilities.contract.proto.CapabilityState;
import com.long2know.utilities.contract.proto.DoubleMeasurement;
import com.long2know.utilities.contract.proto.LegacyIdentity;
import com.long2know.utilities.contract.proto.LegacySourceKind;
import com.long2know.utilities.contract.proto.LiveAvailability;
import com.long2know.utilities.contract.proto.MeasurementSample;
import com.long2know.utilities.contract.proto.MeasurementStatus;
import com.long2know.utilities.contract.proto.PermissionState;
import com.long2know.utilities.contract.proto.Position;
import com.long2know.utilities.contract.proto.PositionMeasurement;
import com.long2know.utilities.contract.proto.Quality;
import com.long2know.utilities.contract.proto.SourceKind;
import com.long2know.utilities.contract.proto.SourceMetadata;
import com.long2know.utilities.data_access.SqlLogger;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LegacyWatchMigrator {
    public static final String STATUS_COMPLETE = "COMPLETE";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_CORRUPT = "CORRUPT";
    public static final String STATUS_UNSUPPORTED = "UNSUPPORTED";

    private static final String ACTIVITY_TABLE = "ACTIVITY";
    private static final String POINT_TABLE = "GPS_POINTS";
    private static final List<Column> ACTIVITY_COLUMNS = Arrays.asList(
            new Column("ID", "INTEGER"),
            new Column("GMTSTART", "VARCHAR"),
            new Column("GMTEND", "VARCHAR"),
            new Column("NAME", "VARCHAR"),
            new Column("DESCRIPTION", "VARCHAR"),
            new Column("DISTANCE", "REAL"),
            new Column("TIME", "REAL"),
            new Column("PACE", "REAL"));
    private static final List<Column> POINT_COLUMNS = Arrays.asList(
            new Column("ID", "INTEGER"),
            new Column("ACTIVITYID", "INTEGER"),
            new Column("GMTTIMESTAMP", "VARCHAR"),
            new Column("LATITUDE", "REAL"),
            new Column("LONGITUDE", "REAL"),
            new Column("ALTITUDE", "REAL"),
            new Column("ACCURACY", "REAL"),
            new Column("SPEED", "REAL"),
            new Column("BEARING", "REAL"),
            new Column("HEARTRATE", "REAL"));

    private final WatchActivityRepository repository;

    public LegacyWatchMigrator(WatchActivityRepository repository) {
        this.repository = repository;
    }

    public static File defaultLegacyDatabase(Context context) {
        return context.getApplicationContext().getDatabasePath(SqlLogger.DATABASE_NAME);
    }

    public static String defaultSourceIdentity(Context context) {
        Context application = context.getApplicationContext();
        return application.getPackageName()
                + ":"
                + defaultLegacyDatabase(application).getAbsolutePath();
    }

    public MigrationResult migrateDefault(Context context, long completedAtUtcMs)
            throws IOException {
        return migrate(
                defaultLegacyDatabase(context),
                defaultSourceIdentity(context),
                completedAtUtcMs);
    }

    public MigrationResult migrate(
            File legacyDatabase,
            String sourceIdentity,
            long completedAtUtcMs) throws IOException {
        return migrate(legacyDatabase, sourceIdentity, completedAtUtcMs, count -> {
        });
    }

    MigrationResult migrate(
            File legacyDatabase,
            String sourceIdentity,
            long completedAtUtcMs,
            InterruptionHook interruptionHook) throws IOException {
        if (legacyDatabase == null || !legacyDatabase.isFile()) {
            throw new IOException("Legacy database file does not exist");
        }
        if (sourceIdentity == null || sourceIdentity.isEmpty()) {
            throw new IllegalArgumentException("Legacy source identity is required");
        }
        Map<String, String> before = sourceSnapshot(legacyDatabase);
        String sourceSha256 = before.get(legacyDatabase.getName());
        String sourceKey = sha256Hex(sourceIdentity.getBytes(StandardCharsets.UTF_8));
        String receiptId = sha256Hex(
                (sourceKey + "\u0000" + sourceSha256).getBytes(StandardCharsets.UTF_8));
        WatchMigrationReceiptEntity existing = repository.migrationReceipt(receiptId);
        if (existing != null) {
            return MigrationResult.from(existing, true);
        }

        Accumulator accumulator = new Accumulator();
        SQLiteDatabase source = null;
        try {
            source = SQLiteDatabase.openDatabase(
                    legacyDatabase.getAbsolutePath(),
                    null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            migrateOpenDatabase(
                    source,
                    sourceIdentity,
                    sourceKey,
                    completedAtUtcMs,
                    accumulator,
                    interruptionHook);
        } catch (SQLiteException error) {
            accumulator.status = STATUS_CORRUPT;
            quarantine(
                    sourceKey,
                    "DATABASE",
                    null,
                    "SOURCE_CORRUPT",
                    error.getMessage(),
                    legacyDatabase.getName(),
                    completedAtUtcMs);
        } finally {
            if (source != null) {
                source.close();
            }
            Map<String, String> after = sourceSnapshot(legacyDatabase);
            if (!before.equals(after)) {
                throw new CanonicalContractException(
                        "SOURCE_MUTATED",
                        "Read-only legacy migration changed source database bytes");
            }
        }

        accumulator.quarantineCount = repository.quarantineCount(sourceKey);
        WatchMigrationReceiptEntity receipt = new WatchMigrationReceiptEntity();
        receipt.receiptId = receiptId;
        receipt.sourceKey = sourceKey;
        receipt.sourceIdentity = sourceIdentity;
        receipt.sourceSha256 = sourceSha256;
        receipt.sourceSize = legacyDatabase.length();
        receipt.status = accumulator.status;
        receipt.sourceActivityCount = accumulator.sourceActivityCount;
        receipt.sourceSampleCount = accumulator.sourceSampleCount;
        receipt.importedActivityCount = accumulator.importedActivityCount;
        receipt.importedSampleCount = accumulator.importedSampleCount;
        receipt.orphanCount = accumulator.orphanCount;
        receipt.quarantineCount = accumulator.quarantineCount;
        receipt.minTimestampUtcMs = accumulator.minTimestampUtcMs;
        receipt.maxTimestampUtcMs = accumulator.maxTimestampUtcMs;
        receipt.canonicalSha256 = CanonicalPayload.hex(accumulator.digest.digest());
        receipt.completedAtUtcMs = completedAtUtcMs;
        repository.insertMigrationReceipt(receipt);
        return MigrationResult.from(receipt, false);
    }

    private void migrateOpenDatabase(
            SQLiteDatabase source,
            String sourceIdentity,
            String sourceKey,
            long completedAtUtcMs,
            Accumulator accumulator,
            InterruptionHook interruptionHook) {
        if (!tableExists(source, ACTIVITY_TABLE)) {
            accumulator.status = STATUS_UNSUPPORTED;
            quarantine(
                    sourceKey,
                    ACTIVITY_TABLE,
                    null,
                    "ACTIVITY_TABLE_MISSING",
                    "Legacy activity table is missing",
                    null,
                    completedAtUtcMs);
            return;
        }
        if (!validateTable(source, ACTIVITY_TABLE, ACTIVITY_COLUMNS)) {
            accumulator.status = STATUS_UNSUPPORTED;
            quarantine(
                    sourceKey,
                    ACTIVITY_TABLE,
                    null,
                    "ACTIVITY_SCHEMA_UNSUPPORTED",
                    "Legacy activity schema does not match the supported writer",
                    null,
                    completedAtUtcMs);
            return;
        }
        boolean hasPointTable = tableExists(source, POINT_TABLE)
                && validateTable(source, POINT_TABLE, POINT_COLUMNS);
        if (!hasPointTable) {
            accumulator.status = STATUS_PARTIAL;
            quarantine(
                    sourceKey,
                    POINT_TABLE,
                    null,
                    "POINT_TABLE_MISSING_OR_UNSUPPORTED",
                    "Legacy point table is missing or has unsupported schema",
                    null,
                    completedAtUtcMs);
        } else {
            accumulator.sourceSampleCount = countRows(source, POINT_TABLE);
        }

        String activitySql = "SELECT "
                + "ID AS legacy_id, GMTSTART AS started_at, GMTEND AS ended_at, "
                + "NAME AS activity_name, DESCRIPTION AS activity_description, "
                + "DISTANCE AS distance_m, TIME AS time_value, PACE AS pace "
                + "FROM ACTIVITY ORDER BY ID";
        try (Cursor activities = source.rawQuery(activitySql, null)) {
            while (activities.moveToNext()) {
                accumulator.sourceActivityCount++;
                long activityId = activities.getLong(
                        activities.getColumnIndexOrThrow("legacy_id"));
                try {
                    LegacyActivityRow activity = readActivity(activities, activityId);
                    PointResult points = hasPointTable
                            ? readPoints(
                                    source,
                                    sourceKey,
                                    activityId,
                                    completedAtUtcMs,
                                    accumulator)
                            : PointResult.empty();
                    ActivityRecord canonical = toCanonical(
                            sourceIdentity,
                            sourceKey,
                            activity,
                            points);
                    String transferId = CanonicalIds.deterministicLegacyTransferId(
                            sourceIdentity,
                            activityId,
                            1L);
                    WatchActivityRepository.CompletionResult imported =
                            repository.importCompletedSession(
                                    canonical,
                                    transferId,
                                    completedAtUtcMs,
                                    sourceKey,
                                    activityId);
                    accumulator.importedActivityCount++;
                    accumulator.importedSampleCount += points.samples.size();
                    updateDigest(
                            accumulator.digest,
                            imported.getSession().getPayload());
                    accumulator.recordTimestamp(activity.startedAtUtcMs);
                    accumulator.recordTimestamp(activity.endedAtUtcMs);
                    for (MeasurementSample sample : points.samples) {
                        accumulator.recordTimestamp(sample.getTimestampUtcMs());
                    }
                    interruptionHook.afterActivity(
                            Math.toIntExact(accumulator.importedActivityCount));
                } catch (RowException | CanonicalContractException error) {
                    accumulator.status = STATUS_PARTIAL;
                    quarantine(
                            sourceKey,
                            ACTIVITY_TABLE,
                            activityId,
                            error instanceof RowException
                                    ? ((RowException) error).code
                                    : ((CanonicalContractException) error).getCode(),
                            error.getMessage(),
                            rawActivity(activities),
                            completedAtUtcMs);
                }
            }
        }

        if (hasPointTable) {
            String orphanSql = "SELECT p.ID AS point_id FROM GPS_POINTS p "
                    + "LEFT JOIN ACTIVITY a ON a.ID = p.ACTIVITYID "
                    + "WHERE a.ID IS NULL ORDER BY p.ID";
            try (Cursor orphans = source.rawQuery(orphanSql, null)) {
                while (orphans.moveToNext()) {
                    long pointId = orphans.getLong(
                            orphans.getColumnIndexOrThrow("point_id"));
                    accumulator.orphanCount++;
                    accumulator.status = STATUS_PARTIAL;
                    quarantine(
                            sourceKey,
                            POINT_TABLE,
                            pointId,
                            "ORPHAN_POINT",
                            "Legacy point references no activity",
                            Long.toString(pointId),
                            completedAtUtcMs);
                }
            }
        }
    }

    private PointResult readPoints(
            SQLiteDatabase source,
            String sourceKey,
            long activityId,
            long completedAtUtcMs,
            Accumulator accumulator) {
        String sql = "SELECT ID AS point_id, ACTIVITYID AS activity_id, "
                + "GMTTIMESTAMP AS timestamp_utc, LATITUDE AS latitude, "
                + "LONGITUDE AS longitude, ALTITUDE AS altitude_m, "
                + "ACCURACY AS accuracy_m, SPEED AS speed_mps, "
                + "BEARING AS bearing_degrees, HEARTRATE AS heart_rate_bpm "
                + "FROM GPS_POINTS WHERE ACTIVITYID = ? ORDER BY ID";
        List<MeasurementSample> samples = new ArrayList<>();
        try (Cursor points = source.rawQuery(sql, new String[]{Long.toString(activityId)})) {
            while (points.moveToNext()) {
                long pointId = points.getLong(points.getColumnIndexOrThrow("point_id"));
                try {
                    samples.add(toSample(points, pointId));
                } catch (RowException | CanonicalContractException error) {
                    accumulator.status = STATUS_PARTIAL;
                    quarantine(
                            sourceKey,
                            POINT_TABLE,
                            pointId,
                            error instanceof RowException
                                    ? ((RowException) error).code
                                    : ((CanonicalContractException) error).getCode(),
                            error.getMessage(),
                            rawPoint(points),
                            completedAtUtcMs);
                }
            }
        }
        return new PointResult(samples);
    }

    private static LegacyActivityRow readActivity(Cursor cursor, long activityId) {
        if (activityId <= 0L) {
            throw new RowException(
                    "ACTIVITY_ID_INVALID",
                    "Legacy activity identifier must be positive");
        }
        String start = requiredText(cursor, "started_at");
        String end = nullableText(cursor, "ended_at");
        if (end == null) {
            throw new RowException(
                    "INCOMPLETE_ACTIVITY",
                    "Legacy activity has no completion timestamp");
        }
        long startedAtUtcMs = LegacyTimestampParser.parseUtcMillis(start);
        long endedAtUtcMs = LegacyTimestampParser.parseUtcMillis(end);
        if (endedAtUtcMs < startedAtUtcMs) {
            throw new RowException(
                    "ACTIVITY_TIME_INVALID",
                    "Legacy activity ends before it starts");
        }
        return new LegacyActivityRow(
                activityId,
                startedAtUtcMs,
                endedAtUtcMs,
                nullableText(cursor, "activity_name"),
                nullableText(cursor, "activity_description"),
                nullableDouble(cursor, "distance_m"),
                rawActivity(cursor));
    }

    private static MeasurementSample toSample(Cursor cursor, long pointId) {
        if (pointId <= 0L) {
            throw new RowException(
                    "POINT_ID_INVALID",
                    "Legacy point identifier must be positive");
        }
        long timestamp = LegacyTimestampParser.parseUtcMillis(
                requiredText(cursor, "timestamp_utc"));
        Double latitude = nullableDouble(cursor, "latitude");
        Double longitude = nullableDouble(cursor, "longitude");
        if ((latitude == null) != (longitude == null)) {
            throw new RowException(
                    "POSITION_PARTIAL",
                    "Legacy position must contain both latitude and longitude");
        }
        SourceMetadata source = legacySource();
        MeasurementSample.Builder sample = MeasurementSample.newBuilder()
                .setSequence(pointId)
                .setTimestampUtcMs(timestamp);
        if (latitude != null) {
            Position.Builder position = Position.newBuilder()
                    .setLatitudeDegrees(finite(latitude, "latitude"))
                    .setLongitudeDegrees(finite(longitude, "longitude"));
            Double altitude = nullableDouble(cursor, "altitude_m");
            Double accuracy = nullableDouble(cursor, "accuracy_m");
            Double bearing = nullableDouble(cursor, "bearing_degrees");
            if (altitude != null) {
                position.setAltitudeM(finite(altitude, "altitude"));
            }
            if (accuracy != null) {
                position.setAccuracyM(nonNegative(accuracy, "accuracy"));
            }
            if (bearing != null) {
                position.setBearingDegrees(finite(bearing, "bearing"));
            }
            MeasurementStatus.Builder locationStatus = status(
                    "LOCATION",
                    "degrees",
                    AggregationSemantics.AGGREGATION_SEMANTICS_INSTANTANEOUS,
                    source);
            if (accuracy != null) {
                locationStatus.setPlatformAccuracy(accuracy);
            }
            sample.setLocation(PositionMeasurement.newBuilder()
                    .setValue(position)
                    .setStatus(locationStatus));
        }
        Double speed = nullableDouble(cursor, "speed_mps");
        if (speed != null) {
            sample.setSpeedMps(doubleMeasurement(
                    nonNegative(speed, "speed"),
                    "SPEED",
                    "metres/second",
                    AggregationSemantics.AGGREGATION_SEMANTICS_INSTANTANEOUS,
                    source));
        }
        Double heartRate = nullableDouble(cursor, "heart_rate_bpm");
        if (heartRate != null && heartRate > 0D) {
            sample.setHeartRateBpm(doubleMeasurement(
                    nonNegative(heartRate, "heart rate"),
                    "HEART_RATE_BPM",
                    "beats/minute",
                    AggregationSemantics.AGGREGATION_SEMANTICS_INSTANTANEOUS,
                    source));
        }
        return sample.build();
    }

    private static ActivityRecord toCanonical(
            String sourceIdentity,
            String sourceKey,
            LegacyActivityRow legacy,
            PointResult points) {
        long wallDuration = legacy.endedAtUtcMs - legacy.startedAtUtcMs;
        SourceMetadata source = legacySource();
        ActivitySummary.Builder summary = ActivitySummary.newBuilder()
                .setSampleCount(points.samples.size());
        long routeCount = 0L;
        long heartRateCount = 0L;
        for (MeasurementSample sample : points.samples) {
            if (sample.hasLocation() && sample.getLocation().hasValue()) {
                routeCount++;
            }
            if (sample.hasHeartRateBpm() && sample.getHeartRateBpm().hasValue()) {
                heartRateCount++;
            }
        }
        summary.setRoutePointCount(routeCount);
        summary.setHeartRateSampleCount(heartRateCount);
        if (legacy.distanceM != null && legacy.distanceM > 0D) {
            summary.setDistanceM(doubleMeasurement(
                    nonNegative(legacy.distanceM, "activity distance"),
                    "DISTANCE_TOTAL",
                    "metres",
                    AggregationSemantics.AGGREGATION_SEMANTICS_CUMULATIVE,
                    source));
        }

        ActivityRecord.Builder activity = ActivityRecord.newBuilder()
                .setSessionId(CanonicalIds.deterministicLegacySessionId(
                        sourceIdentity,
                        legacy.activityId))
                .setWatchRevision(1L)
                .setActivityType(ActivityType.ACTIVITY_TYPE_UNSPECIFIED)
                .setStartTimeUtcMs(legacy.startedAtUtcMs)
                .setEndTimeUtcMs(legacy.endedAtUtcMs)
                .setElapsedDurationMs(wallDuration)
                .setSummary(summary)
                .setSource(source)
                .setLegacyIdentity(LegacyIdentity.newBuilder()
                        .setKind(LegacySourceKind.LEGACY_SOURCE_KIND_WATCH_SQLITE)
                        .setSourceIdentity(sourceIdentity)
                        .setDatabaseName("GPSLOGGERDB_LONG2KNOW")
                        .setActivityId(legacy.activityId)
                        .setSourceRowSha256(sha256Hex(
                                legacy.rawRow.getBytes(StandardCharsets.UTF_8))))
                .addAllSamples(points.samples);
        if (legacy.name != null) {
            activity.setRecordedName(legacy.name);
        }
        if (legacy.description != null) {
            activity.setRecordedDescription(legacy.description);
        }
        return activity.build();
    }

    private static SourceMetadata legacySource() {
        return SourceMetadata.newBuilder()
                .setKind(SourceKind.SOURCE_KIND_LEGACY_SQLITE)
                .setProvider("GPSLOGGERDB_LONG2KNOW")
                .setAppContractVersion(1)
                .build();
    }

    private static DoubleMeasurement doubleMeasurement(
            double value,
            String identifier,
            String unit,
            AggregationSemantics aggregation,
            SourceMetadata source) {
        return DoubleMeasurement.newBuilder()
                .setValue(value)
                .setStatus(status(identifier, unit, aggregation, source))
                .build();
    }

    private static MeasurementStatus.Builder status(
            String identifier,
            String unit,
            AggregationSemantics aggregation,
            SourceMetadata source) {
        return MeasurementStatus.newBuilder()
                .setMetricIdentifier(identifier)
                .setPublicUnit(unit)
                .setAggregation(aggregation)
                .setExerciseCapability(CapabilityState.CAPABILITY_STATE_UNKNOWN)
                .setRuntimePermission(PermissionState.PERMISSION_STATE_UNKNOWN)
                .setRawHardware(CapabilityState.CAPABILITY_STATE_UNKNOWN)
                .setVendorCapability(CapabilityState.CAPABILITY_STATE_UNKNOWN)
                .setLiveAvailability(LiveAvailability.LIVE_AVAILABILITY_AVAILABLE)
                .setSource(source)
                .setQuality(Quality.QUALITY_VALID);
    }

    private static boolean tableExists(SQLiteDatabase database, String tableName) {
        try (Cursor cursor = database.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
                new String[]{tableName})) {
            return cursor.moveToFirst();
        }
    }

    private static boolean validateTable(
            SQLiteDatabase database,
            String tableName,
            List<Column> expected) {
        Map<String, String> actual = new LinkedHashMap<>();
        try (Cursor cursor = database.rawQuery(
                "PRAGMA table_info(\"" + tableName + "\")",
                null)) {
            int nameColumn = cursor.getColumnIndexOrThrow("name");
            int typeColumn = cursor.getColumnIndexOrThrow("type");
            while (cursor.moveToNext()) {
                actual.put(
                        cursor.getString(nameColumn).toUpperCase(Locale.US),
                        cursor.getString(typeColumn).toUpperCase(Locale.US));
            }
        }
        for (Column column : expected) {
            if (!column.type.equals(actual.get(column.name))) {
                return false;
            }
        }
        return true;
    }

    private static long countRows(SQLiteDatabase database, String tableName) {
        try (Cursor cursor = database.rawQuery(
                "SELECT COUNT(*) AS row_count FROM \"" + tableName + "\"",
                null)) {
            if (!cursor.moveToFirst()) {
                return 0L;
            }
            return cursor.getLong(cursor.getColumnIndexOrThrow("row_count"));
        }
    }

    private void quarantine(
            String sourceKey,
            String sourceTable,
            Long sourceRowId,
            String code,
            String detail,
            String rawValue,
            long createdAtUtcMs) {
        WatchMigrationQuarantineEntity entity = new WatchMigrationQuarantineEntity();
        entity.sourceKey = sourceKey;
        entity.sourceTable = sourceTable;
        entity.sourceRowId = sourceRowId == null ? 0L : sourceRowId;
        entity.code = code;
        entity.detail = detail == null ? code : detail;
        entity.rawValue = rawValue;
        entity.rawSha256 = sha256Hex(
                String.valueOf(rawValue).getBytes(StandardCharsets.UTF_8));
        entity.createdAtUtcMs = createdAtUtcMs;
        repository.quarantine(entity);
    }

    private static String requiredText(Cursor cursor, String column) {
        String value = nullableText(cursor, column);
        if (value == null) {
            throw new RowException(
                    "MISSING_REQUIRED_VALUE",
                    "Legacy column " + column + " is NULL");
        }
        return value;
    }

    private static String nullableText(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        if (cursor.isNull(index)) {
            return null;
        }
        if (cursor.getType(index) != Cursor.FIELD_TYPE_STRING) {
            throw new RowException(
                    "LEGACY_TYPE_MISMATCH",
                    "Legacy column " + column + " is not text");
        }
        return cursor.getString(index);
    }

    private static Double nullableDouble(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        if (cursor.isNull(index)) {
            return null;
        }
        int type = cursor.getType(index);
        if (type != Cursor.FIELD_TYPE_FLOAT && type != Cursor.FIELD_TYPE_INTEGER) {
            throw new RowException(
                    "LEGACY_TYPE_MISMATCH",
                    "Legacy column " + column + " is not numeric");
        }
        return finite(cursor.getDouble(index), column);
    }

    private static double finite(double value, String label) {
        if (!Double.isFinite(value)) {
            throw new RowException(
                    "LEGACY_NUMBER_INVALID",
                    "Legacy " + label + " is not finite");
        }
        return value;
    }

    private static double nonNegative(double value, String label) {
        finite(value, label);
        if (value < 0D) {
            throw new RowException(
                    "LEGACY_NUMBER_INVALID",
                    "Legacy " + label + " is negative");
        }
        return value;
    }

    private static String rawActivity(Cursor cursor) {
        return raw(cursor,
                "legacy_id",
                "started_at",
                "ended_at",
                "activity_name",
                "activity_description",
                "distance_m",
                "time_value",
                "pace");
    }

    private static String rawPoint(Cursor cursor) {
        return raw(cursor,
                "point_id",
                "activity_id",
                "timestamp_utc",
                "latitude",
                "longitude",
                "altitude_m",
                "accuracy_m",
                "speed_mps",
                "bearing_degrees",
                "heart_rate_bpm");
    }

    private static String raw(Cursor cursor, String... columns) {
        StringBuilder value = new StringBuilder();
        for (String column : columns) {
            int index = cursor.getColumnIndexOrThrow(column);
            if (value.length() > 0) {
                value.append('\u001f');
            }
            value.append(column).append('=');
            if (cursor.isNull(index)) {
                value.append("<null>");
            } else {
                value.append(cursor.getString(index));
            }
        }
        return value.toString();
    }

    private static Map<String, String> sourceSnapshot(File database) throws IOException {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (String suffix : new String[]{"", "-journal", "-wal", "-shm"}) {
            File file = suffix.isEmpty()
                    ? database
                    : new File(database.getPath() + suffix);
            if (file.isFile()) {
                snapshot.put(file.getName(), sha256File(file));
            }
        }
        return snapshot;
    }

    private static String sha256File(File file) throws IOException {
        MessageDigest digest = sha256();
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read = input.read(buffer); read != -1; read = input.read(buffer)) {
                digest.update(buffer, 0, read);
            }
        }
        return CanonicalPayload.hex(digest.digest());
    }

    private static String sha256Hex(byte[] bytes) {
        return CanonicalPayload.hex(sha256().digest(bytes));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void updateDigest(MessageDigest digest, byte[] bytes) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(bytes.length).array());
        digest.update(bytes);
    }

    interface InterruptionHook {
        void afterActivity(int importedCount);
    }

    public static final class MigrationResult {
        private final String receiptId;
        private final String status;
        private final long sourceActivityCount;
        private final long sourceSampleCount;
        private final long importedActivityCount;
        private final long importedSampleCount;
        private final long orphanCount;
        private final long quarantineCount;
        private final String canonicalSha256;
        private final boolean alreadyCompleted;

        private MigrationResult(
                String receiptId,
                String status,
                long sourceActivityCount,
                long sourceSampleCount,
                long importedActivityCount,
                long importedSampleCount,
                long orphanCount,
                long quarantineCount,
                String canonicalSha256,
                boolean alreadyCompleted) {
            this.receiptId = receiptId;
            this.status = status;
            this.sourceActivityCount = sourceActivityCount;
            this.sourceSampleCount = sourceSampleCount;
            this.importedActivityCount = importedActivityCount;
            this.importedSampleCount = importedSampleCount;
            this.orphanCount = orphanCount;
            this.quarantineCount = quarantineCount;
            this.canonicalSha256 = canonicalSha256;
            this.alreadyCompleted = alreadyCompleted;
        }

        private static MigrationResult from(
                WatchMigrationReceiptEntity receipt,
                boolean alreadyCompleted) {
            return new MigrationResult(
                    receipt.receiptId,
                    receipt.status,
                    receipt.sourceActivityCount,
                    receipt.sourceSampleCount,
                    receipt.importedActivityCount,
                    receipt.importedSampleCount,
                    receipt.orphanCount,
                    receipt.quarantineCount,
                    receipt.canonicalSha256,
                    alreadyCompleted);
        }

        public String getReceiptId() {
            return receiptId;
        }

        public String getStatus() {
            return status;
        }

        public long getSourceActivityCount() {
            return sourceActivityCount;
        }

        public long getSourceSampleCount() {
            return sourceSampleCount;
        }

        public long getImportedActivityCount() {
            return importedActivityCount;
        }

        public long getImportedSampleCount() {
            return importedSampleCount;
        }

        public long getOrphanCount() {
            return orphanCount;
        }

        public long getQuarantineCount() {
            return quarantineCount;
        }

        public String getCanonicalSha256() {
            return canonicalSha256;
        }

        public boolean isAlreadyCompleted() {
            return alreadyCompleted;
        }
    }

    private static final class Column {
        private final String name;
        private final String type;

        private Column(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    private static final class LegacyActivityRow {
        private final long activityId;
        private final long startedAtUtcMs;
        private final long endedAtUtcMs;
        private final String name;
        private final String description;
        private final Double distanceM;
        private final String rawRow;

        private LegacyActivityRow(
                long activityId,
                long startedAtUtcMs,
                long endedAtUtcMs,
                String name,
                String description,
                Double distanceM,
                String rawRow) {
            this.activityId = activityId;
            this.startedAtUtcMs = startedAtUtcMs;
            this.endedAtUtcMs = endedAtUtcMs;
            this.name = name;
            this.description = description;
            this.distanceM = distanceM;
            this.rawRow = rawRow;
        }
    }

    private static final class PointResult {
        private final List<MeasurementSample> samples;

        private PointResult(List<MeasurementSample> samples) {
            this.samples = Collections.unmodifiableList(new ArrayList<>(samples));
        }

        private static PointResult empty() {
            return new PointResult(Collections.emptyList());
        }
    }

    private static final class Accumulator {
        private String status = STATUS_COMPLETE;
        private long sourceActivityCount;
        private long sourceSampleCount;
        private long importedActivityCount;
        private long importedSampleCount;
        private long orphanCount;
        private long quarantineCount;
        private Long minTimestampUtcMs;
        private Long maxTimestampUtcMs;
        private final MessageDigest digest = sha256();

        private void recordTimestamp(long value) {
            minTimestampUtcMs = minTimestampUtcMs == null
                    ? value
                    : Math.min(minTimestampUtcMs, value);
            maxTimestampUtcMs = maxTimestampUtcMs == null
                    ? value
                    : Math.max(maxTimestampUtcMs, value);
        }
    }

    private static final class RowException extends RuntimeException {
        private final String code;

        private RowException(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
