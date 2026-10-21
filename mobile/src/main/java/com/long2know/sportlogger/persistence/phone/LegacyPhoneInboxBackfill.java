package com.long2know.sportlogger.persistence.phone;

import android.content.Context;

import com.long2know.utilities.contract.CanonicalIds;
import com.long2know.utilities.contract.CanonicalPayload;
import com.long2know.utilities.contract.CanonicalTransfers;
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
import com.long2know.utilities.models.SportActivity;
import com.long2know.utilities.models.SportTrackPoint;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class LegacyPhoneInboxBackfill {
    public static final String LEGACY_INBOX_DIRECTORY = "received-sport-activities";

    private final PhoneActivityRepository repository;

    public LegacyPhoneInboxBackfill(PhoneActivityRepository repository) {
        this.repository = repository;
    }

    public static File defaultInboxDirectory(Context context) {
        return new File(
                context.getApplicationContext().getFilesDir(),
                LEGACY_INBOX_DIRECTORY);
    }

    public BackfillResult backfillDefault(Context context, long completedAtUtcMs)
            throws IOException {
        return backfill(defaultInboxDirectory(context), completedAtUtcMs);
    }

    public BackfillResult backfill(File inboxDirectory, long completedAtUtcMs) throws IOException {
        if (inboxDirectory == null || !inboxDirectory.exists()) {
            return new BackfillResult(0, 0, 0, 0);
        }
        File[] files = inboxDirectory.listFiles(
                file -> file.isFile() && file.getName().endsWith(".ser"));
        if (files == null) {
            throw new IOException("Could not list legacy phone inbox");
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        int imported = 0;
        int skipped = 0;
        int partial = 0;
        int failed = 0;
        for (File file : files) {
            byte[] bytes = Files.readAllBytes(file.toPath());
            String beforeHash = sha256Hex(bytes);
            String sourceFileKey = sha256Hex(
                    file.getName().getBytes(StandardCharsets.UTF_8));
            PhoneLegacyBackfillReceiptEntity existing =
                    repository.legacyBackfillReceipt(sourceFileKey);
            if (existing != null) {
                if (!existing.sourceSha256.equals(beforeHash)) {
                    throw new IOException(
                            "Legacy inbox file changed after receipt: " + file.getName());
                }
                skipped++;
                continue;
            }
            PhoneLegacyBackfillReceiptEntity receipt = new PhoneLegacyBackfillReceiptEntity();
            receipt.sourceFileKey = sourceFileKey;
            receipt.sourceSha256 = beforeHash;
            receipt.completedAtUtcMs = completedAtUtcMs;
            try {
                SportActivity legacy = SportActivity.deserialize(bytes);
                MappedActivity mapped = map(file.getName(), sourceFileKey, beforeHash, legacy);
                receipt.sessionId = mapped.activity.getSessionId();
                receipt.status = mapped.skippedPoints == 0
                        ? PhoneActivityRepository.IMPORT_COMPLETE
                        : PhoneActivityRepository.IMPORT_PARTIAL;
                receipt.errorCode = mapped.skippedPoints == 0
                        ? null
                        : "LEGACY_POINTS_SKIPPED";
                String transferId = CanonicalIds.deterministicLegacyTransferId(
                        mapped.sourceIdentity,
                        mapped.legacyActivityId,
                        1L);
                byte[] transfer = CanonicalTransfers.activityTransfer(
                                transferId,
                                CanonicalPayload.create(mapped.activity))
                        .toByteArray();
                repository.acceptActivityTransfer(transfer, completedAtUtcMs);
                PhoneActivityRepository.Metadata metadata =
                        repository.metadata(mapped.activity.getSessionId());
                repository.setMetadata(
                        mapped.activity.getSessionId(),
                        metadata.getTitle() == null ? legacy.Name : metadata.getTitle(),
                        metadata.getNotes() == null ? legacy.Description : metadata.getNotes(),
                        metadata.getTags(),
                        completedAtUtcMs);
                if (mapped.skippedPoints > 0) {
                    repository.markImportHealth(
                            mapped.activity.getSessionId(),
                            PhoneActivityRepository.IMPORT_PARTIAL,
                            "LEGACY_POINTS_SKIPPED",
                            completedAtUtcMs);
                    partial++;
                } else {
                    imported++;
                }
            } catch (Exception error) {
                receipt.status = PhoneActivityRepository.IMPORT_CORRUPT;
                receipt.errorCode = "LEGACY_SERIALIZED_PAYLOAD_INVALID";
                failed++;
            }
            repository.insertLegacyBackfillReceipt(receipt);
            String afterHash = sha256Hex(Files.readAllBytes(file.toPath()));
            if (!beforeHash.equals(afterHash)) {
                throw new IOException(
                        "Legacy inbox backfill changed source bytes: " + file.getName());
            }
        }
        return new BackfillResult(files.length, imported, skipped, partial + failed);
    }

    private static MappedActivity map(
            String fileName,
            String sourceFileKey,
            String sourceSha256,
            SportActivity legacy) {
        if (legacy == null || legacy.StartTimeUTC == null || legacy.EndTimeUTC == null) {
            throw new IllegalArgumentException("Legacy activity start and end are required");
        }
        long legacyActivityId = legacy.Id > 0
                ? legacy.Id
                : positiveLong(sourceSha256);
        String sourceIdentity = "phone-serialized:" + sourceFileKey;
        List<SportTrackPoint> legacyPoints = legacy.SportTrackPoints == null
                ? new ArrayList<>()
                : new ArrayList<>(legacy.SportTrackPoints);
        legacyPoints.sort(Comparator
                .comparing(
                        (SportTrackPoint point) -> point.TimeStampUTC,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(point -> point.Id));
        List<MeasurementSample> samples = new ArrayList<>();
        int skippedPoints = 0;
        long sequence = 1L;
        for (SportTrackPoint point : legacyPoints) {
            try {
                samples.add(mapPoint(sequence++, point));
            } catch (IllegalArgumentException error) {
                skippedPoints++;
            }
        }

        long wallDuration = Math.max(
                0L,
                legacy.EndTimeUTC.getTime() - legacy.StartTimeUTC.getTime());
        Long activeDuration = legacy.Time > 0D
                ? Math.round(finiteNonNegative(legacy.Time, "duration") * 1_000D)
                : null;
        long elapsedDuration = activeDuration == null
                ? wallDuration
                : Math.max(wallDuration, activeDuration);
        SourceMetadata source = source();
        ActivitySummary.Builder summary = ActivitySummary.newBuilder()
                .setSampleCount(samples.size());
        long routeCount = samples.stream()
                .filter(sample -> sample.hasLocation() && sample.getLocation().hasValue())
                .count();
        long heartRateCount = samples.stream()
                .filter(sample -> sample.hasHeartRateBpm()
                        && sample.getHeartRateBpm().hasValue())
                .count();
        summary.setRoutePointCount(routeCount);
        summary.setHeartRateSampleCount(heartRateCount);
        if (legacy.Distance > 0D) {
            summary.setDistanceM(measurement(
                    finiteNonNegative(legacy.Distance, "distance"),
                    "DISTANCE_TOTAL",
                    "metres",
                    AggregationSemantics.AGGREGATION_SEMANTICS_CUMULATIVE));
        }

        ActivityRecord.Builder activity = ActivityRecord.newBuilder()
                .setSessionId(CanonicalIds.deterministicLegacySessionId(
                        sourceIdentity,
                        legacyActivityId))
                .setWatchRevision(1L)
                .setActivityType(ActivityType.ACTIVITY_TYPE_UNSPECIFIED)
                .setStartTimeUtcMs(legacy.StartTimeUTC.getTime())
                .setEndTimeUtcMs(legacy.EndTimeUTC.getTime())
                .setElapsedDurationMs(elapsedDuration)
                .setSummary(summary)
                .setSource(source)
                .setLegacyIdentity(LegacyIdentity.newBuilder()
                        .setKind(
                                LegacySourceKind
                                        .LEGACY_SOURCE_KIND_PHONE_SERIALIZED_INBOX)
                        .setSourceIdentity(sourceIdentity)
                        .setActivityId(legacyActivityId)
                        .setRawPayloadSha256(sourceSha256))
                .addAllSamples(samples);
        if (activeDuration != null) {
            activity.setActiveDurationMs(activeDuration);
        }
        if (legacy.Name != null) {
            activity.setRecordedName(legacy.Name);
        }
        if (legacy.Description != null) {
            activity.setRecordedDescription(legacy.Description);
        }
        return new MappedActivity(
                sourceIdentity,
                legacyActivityId,
                activity.build(),
                skippedPoints);
    }

    private static MeasurementSample mapPoint(long sequence, SportTrackPoint point) {
        if (point == null || point.TimeStampUTC == null) {
            throw new IllegalArgumentException("Legacy point timestamp is required");
        }
        SourceMetadata source = source();
        Position.Builder position = Position.newBuilder()
                .setLatitudeDegrees(finite(point.Latitude, "latitude"))
                .setLongitudeDegrees(finite(point.Longitude, "longitude"));
        if (point.Altitude != 0D) {
            position.setAltitudeM(finite(point.Altitude, "altitude"));
        }
        if (point.Accuracy > 0D) {
            position.setAccuracyM(finiteNonNegative(point.Accuracy, "accuracy"));
        }
        if (point.Bearing != 0D) {
            position.setBearingDegrees(finite(point.Bearing, "bearing"));
        }
        MeasurementStatus.Builder locationStatus = status(
                "LOCATION",
                "degrees",
                AggregationSemantics.AGGREGATION_SEMANTICS_INSTANTANEOUS);
        if (point.Accuracy > 0D) {
            locationStatus.setPlatformAccuracy(point.Accuracy);
        }
        MeasurementSample.Builder sample = MeasurementSample.newBuilder()
                .setSequence(sequence)
                .setTimestampUtcMs(point.TimeStampUTC.getTime())
                .setLocation(PositionMeasurement.newBuilder()
                        .setValue(position)
                        .setStatus(locationStatus));
        if (point.Speed > 0D) {
            sample.setSpeedMps(measurement(
                    finiteNonNegative(point.Speed, "speed"),
                    "SPEED",
                    "metres/second",
                    AggregationSemantics.AGGREGATION_SEMANTICS_INSTANTANEOUS));
        }
        if (point.HeartRate > 0D) {
            sample.setHeartRateBpm(measurement(
                    finiteNonNegative(point.HeartRate, "heart rate"),
                    "HEART_RATE_BPM",
                    "beats/minute",
                    AggregationSemantics.AGGREGATION_SEMANTICS_INSTANTANEOUS));
        }
        return sample.build();
    }

    private static DoubleMeasurement measurement(
            double value,
            String identifier,
            String unit,
            AggregationSemantics aggregation) {
        return DoubleMeasurement.newBuilder()
                .setValue(value)
                .setStatus(status(identifier, unit, aggregation))
                .build();
    }

    private static MeasurementStatus.Builder status(
            String identifier,
            String unit,
            AggregationSemantics aggregation) {
        return MeasurementStatus.newBuilder()
                .setMetricIdentifier(identifier)
                .setPublicUnit(unit)
                .setAggregation(aggregation)
                .setExerciseCapability(CapabilityState.CAPABILITY_STATE_UNKNOWN)
                .setRuntimePermission(PermissionState.PERMISSION_STATE_UNKNOWN)
                .setRawHardware(CapabilityState.CAPABILITY_STATE_UNKNOWN)
                .setVendorCapability(CapabilityState.CAPABILITY_STATE_UNKNOWN)
                .setLiveAvailability(LiveAvailability.LIVE_AVAILABILITY_AVAILABLE)
                .setSource(source())
                .setQuality(Quality.QUALITY_VALID);
    }

    private static SourceMetadata source() {
        return SourceMetadata.newBuilder()
                .setKind(SourceKind.SOURCE_KIND_LEGACY_SERIALIZED)
                .setProvider("received-sport-activities")
                .setAppContractVersion(1)
                .build();
    }

    private static long positiveLong(String sha256) {
        byte[] bytes = hexBytes(sha256.substring(0, 16));
        long value = java.nio.ByteBuffer.wrap(bytes).getLong() & Long.MAX_VALUE;
        return value == 0L ? 1L : value;
    }

    private static byte[] hexBytes(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int index = 0; index < value.length(); index += 2) {
            bytes[index / 2] = (byte) Integer.parseInt(value.substring(index, index + 2), 16);
        }
        return bytes;
    }

    private static double finite(double value, String label) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Legacy " + label + " is not finite");
        }
        return value;
    }

    private static double finiteNonNegative(double value, String label) {
        finite(value, label);
        if (value < 0D) {
            throw new IllegalArgumentException("Legacy " + label + " is negative");
        }
        return value;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return CanonicalPayload.hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    public static final class BackfillResult {
        private final int sourceFiles;
        private final int imported;
        private final int alreadyProcessed;
        private final int partialOrFailed;

        private BackfillResult(
                int sourceFiles,
                int imported,
                int alreadyProcessed,
                int partialOrFailed) {
            this.sourceFiles = sourceFiles;
            this.imported = imported;
            this.alreadyProcessed = alreadyProcessed;
            this.partialOrFailed = partialOrFailed;
        }

        public int getSourceFiles() {
            return sourceFiles;
        }

        public int getImported() {
            return imported;
        }

        public int getAlreadyProcessed() {
            return alreadyProcessed;
        }

        public int getPartialOrFailed() {
            return partialOrFailed;
        }
    }

    private static final class MappedActivity {
        private final String sourceIdentity;
        private final long legacyActivityId;
        private final ActivityRecord activity;
        private final int skippedPoints;

        private MappedActivity(
                String sourceIdentity,
                long legacyActivityId,
                ActivityRecord activity,
                int skippedPoints) {
            this.sourceIdentity = sourceIdentity;
            this.legacyActivityId = legacyActivityId;
            this.activity = activity;
            this.skippedPoints = skippedPoints;
        }
    }
}
