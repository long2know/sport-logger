package com.long2know.sportlogger.export;

import android.os.CancellationSignal;

import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository;
import com.long2know.utilities.contract.proto.ActivityRecord;
import com.long2know.utilities.contract.proto.ActivityType;
import com.long2know.utilities.contract.proto.DoubleMeasurement;
import com.long2know.utilities.contract.proto.Lap;
import com.long2know.utilities.contract.proto.LapIntensity;
import com.long2know.utilities.contract.proto.LapTrigger;
import com.long2know.utilities.contract.proto.MeasurementSample;
import com.long2know.utilities.contract.proto.Position;
import com.long2know.utilities.export.tcx.TcxExportSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class CanonicalTcxExportSource implements TcxExportSource {
    private static final int POINT_PAGE_SIZE = 1_024;

    private final PhoneActivityRepository repository;
    private final PhoneActivityRepository.ActivityDetail detail;
    private final ActivityRecord activity;
    private final MetadataSnapshot metadata;
    private final List<String> formatWarnings;

    public CanonicalTcxExportSource(
            PhoneActivityRepository repository,
            PhoneActivityRepository.ActivityDetail detail) {
        this(repository, detail, MetadataSnapshot.from(detail.getMetadata()));
    }

    private CanonicalTcxExportSource(
            PhoneActivityRepository repository,
            PhoneActivityRepository.ActivityDetail detail,
            MetadataSnapshot metadata) {
        this.repository = Objects.requireNonNull(repository);
        this.detail = Objects.requireNonNull(detail);
        this.activity = detail.getActivity();
        this.metadata = Objects.requireNonNull(metadata);
        this.formatWarnings =
                Collections.unmodifiableList(findFormatWarnings(activity));
    }

    public static CanonicalTcxExportSource fromRevision(
            PhoneActivityRepository repository,
            String sessionId,
            long revision,
            MetadataSnapshot frozenMetadata) {
        PhoneActivityRepository.ActivityDetail detail =
                Objects.requireNonNull(repository).detail(sessionId, revision);
        if (detail == null) {
            throw new IllegalArgumentException(
                    "Canonical activity revision does not exist");
        }
        return new CanonicalTcxExportSource(
                repository,
                detail,
                Objects.requireNonNull(frozenMetadata));
    }

    public static CanonicalTcxExportSource fromCurrentMetadata(
            PhoneActivityRepository repository,
            String sessionId,
            long revision) {
        PhoneActivityRepository.ActivityDetail detail =
                Objects.requireNonNull(repository).detail(sessionId, revision);
        if (detail == null) {
            throw new IllegalArgumentException(
                    "Canonical activity revision does not exist");
        }
        return new CanonicalTcxExportSource(repository, detail);
    }

    @Override
    public Long getStartTimeUtcMillis() {
        return activity.getStartTimeUtcMs();
    }

    @Override
    public Double getDurationSeconds() {
        long duration = activity.hasActiveDurationMs()
                ? activity.getActiveDurationMs()
                : activity.getElapsedDurationMs();
        return duration / 1_000D;
    }

    @Override
    public Double getDistanceMeters() {
        if (!activity.hasSummary()
                || !activity.getSummary().hasDistanceM()
                || !activity.getSummary().getDistanceM().hasValue()) {
            return null;
        }
        return activity.getSummary().getDistanceM().getValue();
    }

    @Override
    public Integer getCalories() {
        if (!activity.hasSummary()
                || !activity.getSummary().hasCaloriesKcal()
                || !activity.getSummary().getCaloriesKcal().hasValue()) {
            return null;
        }
        return roundedInteger(activity.getSummary().getCaloriesKcal().getValue());
    }

    @Override
    public Intensity getIntensity() {
        if (activity.getLapsCount() != 1 || !activity.getLaps(0).hasIntensity()) {
            return null;
        }
        LapIntensity intensity = activity.getLaps(0).getIntensity();
        if (intensity == LapIntensity.LAP_INTENSITY_ACTIVE) {
            return Intensity.ACTIVE;
        }
        if (intensity == LapIntensity.LAP_INTENSITY_RESTING) {
            return Intensity.RESTING;
        }
        return null;
    }

    @Override
    public TriggerMethod getTriggerMethod() {
        if (activity.getLapsCount() != 1 || !activity.getLaps(0).hasTrigger()) {
            return null;
        }
        LapTrigger trigger = activity.getLaps(0).getTrigger();
        switch (trigger) {
            case LAP_TRIGGER_MANUAL:
                return TriggerMethod.MANUAL;
            case LAP_TRIGGER_DISTANCE:
                return TriggerMethod.DISTANCE;
            case LAP_TRIGGER_LOCATION:
                return TriggerMethod.LOCATION;
            case LAP_TRIGGER_TIME:
                return TriggerMethod.TIME;
            case LAP_TRIGGER_HEART_RATE:
                return TriggerMethod.HEART_RATE;
            case LAP_TRIGGER_UNSPECIFIED:
            case UNRECOGNIZED:
            default:
                return null;
        }
    }

    @Override
    public Sport getSport() {
        ActivityType type = activity.getActivityType();
        if (type == ActivityType.ACTIVITY_TYPE_RUNNING) {
            return Sport.RUNNING;
        }
        if (type == ActivityType.ACTIVITY_TYPE_CYCLING) {
            return Sport.BIKING;
        }
        if (type == ActivityType.ACTIVITY_TYPE_UNSPECIFIED
                || type == ActivityType.UNRECOGNIZED) {
            return null;
        }
        return Sport.OTHER;
    }

    @Override
    public Integer getAverageHeartRateBpm() {
        if (!activity.hasSummary()) {
            return null;
        }
        return roundedMeasurement(activity.getSummary().getAverageHeartRateBpm());
    }

    @Override
    public Integer getMaximumHeartRateBpm() {
        if (!activity.hasSummary()) {
            return null;
        }
        return roundedMeasurement(activity.getSummary().getMaximumHeartRateBpm());
    }

    @Override
    public String getNotes() {
        List<String> sections = new ArrayList<>();
        addNonBlank(sections, metadata.getTitle());
        addNonBlank(sections, metadata.getNotes());
        if (!metadata.getTags().isEmpty()) {
            sections.add("Tags: " + String.join(", ", metadata.getTags()));
        }
        if ((metadata.getTitle() == null || metadata.getTitle().trim().isEmpty())
                && activity.hasRecordedName()) {
            addNonBlank(sections, activity.getRecordedName());
        }
        if ((metadata.getNotes() == null || metadata.getNotes().trim().isEmpty())
                && activity.hasRecordedDescription()) {
            addNonBlank(sections, activity.getRecordedDescription());
        }
        if (!formatWarnings.isEmpty()) {
            sections.add(
                    "SportLogger format limitation: "
                            + String.join(" ", formatWarnings));
        }
        return sections.isEmpty() ? null : String.join("\n\n", sections);
    }

    @Override
    public TrackPointCursor openTrackPoints() {
        return new RepositoryPointCursor(
                repository,
                detail.getSessionId(),
                detail.getRevision());
    }

    public List<String> getFormatWarnings() {
        return formatWarnings;
    }

    public String getSessionId() {
        return detail.getSessionId();
    }

    public long getRevision() {
        return detail.getRevision();
    }

    public MetadataSnapshot getMetadataSnapshot() {
        return metadata;
    }

    private static Integer roundedMeasurement(DoubleMeasurement measurement) {
        return measurement.hasValue() ? roundedInteger(measurement.getValue()) : null;
    }

    private static int roundedInteger(double value) {
        if (!Double.isFinite(value)
                || value < Integer.MIN_VALUE
                || value > Integer.MAX_VALUE) {
            return value < 0D ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        }
        return (int) Math.round(value);
    }

    private static List<String> findFormatWarnings(ActivityRecord activity) {
        List<String> warnings = new ArrayList<>();
        if (activity.hasSummary()
                && activity.getSummary().hasCaloriesKcal()
                && activity.getSummary().getCaloriesKcal().hasValue()
                && isFractional(activity.getSummary().getCaloriesKcal().getValue())) {
            warnings.add("Fractional calories were rounded to the nearest TCX integer.");
        }
        if (activity.getLapsCount() > 1) {
            warnings.add(
                    "The canonical activity has "
                            + activity.getLapsCount()
                            + " laps; TCX export uses the activity aggregate as one lap.");
        }
        for (MeasurementSample sample : activity.getSamplesList()) {
            if (sample.hasHeartRateBpm()
                    && sample.getHeartRateBpm().hasValue()
                    && isFractional(sample.getHeartRateBpm().getValue())) {
                warnings.add(
                        "Fractional heart-rate samples were rounded to the nearest TCX beat.");
                break;
            }
        }
        return warnings;
    }

    private static boolean isFractional(double value) {
        return Double.isFinite(value) && Math.rint(value) != value;
    }

    private static void addNonBlank(List<String> sections, String value) {
        if (value != null && !value.trim().isEmpty()) {
            sections.add(value.trim());
        }
    }

    private static final class RepositoryPointCursor implements TrackPointCursor {
        private final PhoneActivityRepository repository;
        private final String sessionId;
        private final long revision;
        private final CancellationSignal cancellationSignal = new CancellationSignal();

        private List<MeasurementSample> page = Collections.emptyList();
        private int index;
        private long afterSequence = -1L;
        private boolean hasMore = true;
        private MeasurementSample current;

        private RepositoryPointCursor(
                PhoneActivityRepository repository,
                String sessionId,
                long revision) {
            this.repository = repository;
            this.sessionId = sessionId;
            this.revision = revision;
        }

        @Override
        public boolean moveNext() throws IOException {
            while (index >= page.size()) {
                if (!hasMore) {
                    current = null;
                    return false;
                }
                PhoneActivityRepository.PointPage next;
                try {
                    next = repository.pointPage(
                            sessionId,
                            revision,
                            afterSequence,
                            POINT_PAGE_SIZE,
                            cancellationSignal);
                } catch (RuntimeException error) {
                    throw new IOException("Could not page canonical activity points", error);
                }
                page = next.getSamples();
                index = 0;
                hasMore = next.hasMore();
                if (page.isEmpty()) {
                    if (hasMore) {
                        throw new IOException(
                                "Canonical point paging returned an empty non-terminal page");
                    }
                    current = null;
                    return false;
                }
                afterSequence = next.getNextSequence();
            }
            current = page.get(index++);
            return true;
        }

        @Override
        public Long getTimeUtcMillis() {
            return current == null ? null : current.getTimestampUtcMs();
        }

        @Override
        public boolean hasPosition() {
            return current != null
                    && current.hasLocation()
                    && current.getLocation().hasValue();
        }

        @Override
        public double getLatitudeDegrees() {
            return position().getLatitudeDegrees();
        }

        @Override
        public double getLongitudeDegrees() {
            return position().getLongitudeDegrees();
        }

        @Override
        public Double getAltitudeMeters() {
            Position position = hasPosition() ? position() : null;
            return position != null && position.hasAltitudeM()
                    ? position.getAltitudeM()
                    : null;
        }

        @Override
        public Double getDistanceMeters() {
            if (current != null
                    && current.hasDistanceTotalM()
                    && current.getDistanceTotalM().hasValue()) {
                return current.getDistanceTotalM().getValue();
            }
            return null;
        }

        @Override
        public Integer getHeartRateBpm() {
            if (current != null
                    && current.hasHeartRateBpm()
                    && current.getHeartRateBpm().hasValue()) {
                return roundedInteger(current.getHeartRateBpm().getValue());
            }
            return null;
        }

        @Override
        public void close() {
            cancellationSignal.cancel();
            page = Collections.emptyList();
            current = null;
            hasMore = false;
        }

        private Position position() {
            if (!hasPosition()) {
                throw new IllegalStateException("Current canonical point has no position");
            }
            return current.getLocation().getValue();
        }
    }

    public static final class MetadataSnapshot {
        private final String title;
        private final String notes;
        private final List<String> tags;
        private final long updatedAtUtcMs;

        public MetadataSnapshot(
                String title,
                String notes,
                List<String> tags,
                long updatedAtUtcMs) {
            this.title = title;
            this.notes = notes;
            this.tags = Collections.unmodifiableList(
                    new ArrayList<>(tags == null ? Collections.emptyList() : tags));
            this.updatedAtUtcMs = updatedAtUtcMs;
        }

        public static MetadataSnapshot from(
                PhoneActivityRepository.Metadata metadata) {
            Objects.requireNonNull(metadata);
            return new MetadataSnapshot(
                    metadata.getTitle(),
                    metadata.getNotes(),
                    metadata.getTags(),
                    metadata.getUpdatedAtUtcMs());
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
}
