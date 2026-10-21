package com.long2know.sportlogger.history;

import com.long2know.sportlogger.transfer.PhoneActivityStore;
import com.long2know.utilities.export.tcx.LegacySportActivityTcxSource;
import com.long2know.utilities.models.SportActivity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class LegacyInboxActivityDataSource implements PhoneActivityDataSource {
    public static final int DEFAULT_HISTORY_LIMIT = 200;

    private final PhoneActivityStore store;

    public LegacyInboxActivityDataSource(PhoneActivityStore store) {
        this.store = Objects.requireNonNull(store);
    }

    @Override
    public HistoryResult loadHistory(int limit) throws IOException {
        PhoneActivityStore.StoredActivityListing listing =
                store.listStoredActivities(limit);
        List<ActivityListItem> activities = new ArrayList<>();
        int corrupt = 0;
        int tooLarge = 0;
        int unsupported = 0;
        int ioFailures = 0;

        // Each immutable payload is released before the next one is opened; list rows retain no
        // serialized track-point graph.
        for (PhoneActivityStore.StoredActivityFile file : listing.getActivities()) {
            try {
                SportActivity activity = store.loadStoredActivity(file.getStorageId());
                activities.add(summary(
                        file.getStorageId(),
                        activity,
                        file.isAcknowledgementPending()));
            } catch (PhoneActivityStore.ActivityReadException error) {
                switch (error.getFailure()) {
                    case CORRUPT:
                        corrupt++;
                        break;
                    case TOO_LARGE:
                        tooLarge++;
                        break;
                    case UNSUPPORTED:
                        unsupported++;
                        break;
                    default:
                        ioFailures++;
                        break;
                }
            } catch (IOException error) {
                ioFailures++;
            }
        }
        activities.sort(
                Comparator.comparingLong(LegacyInboxActivityDataSource::sortTime)
                        .reversed()
                        .thenComparing(ActivityListItem::getStorageId));
        return new HistoryResult(
                activities,
                listing.getTotalCount(),
                corrupt,
                tooLarge,
                unsupported,
                ioFailures);
    }

    @Override
    public ActivityRecord loadActivity(String storageId) throws IOException {
        SportActivity activity = store.loadStoredActivity(storageId);
        LegacySportActivityTcxSource source =
                new LegacySportActivityTcxSource(activity);
        return new ActivityRecord(
                summary(
                        storageId,
                        activity,
                        source,
                        store.isAcknowledgementPending(storageId)),
                normalized(activity.Description),
                source);
    }

    private static ActivityListItem summary(
            String storageId,
            SportActivity activity,
            boolean acknowledgementPending) {
        return summary(
                storageId,
                activity,
                new LegacySportActivityTcxSource(activity),
                acknowledgementPending);
    }

    private static ActivityListItem summary(
            String storageId,
            SportActivity activity,
            LegacySportActivityTcxSource source,
            boolean acknowledgementPending) {
        return new ActivityListItem(
                storageId,
                normalized(activity.Name),
                source.getStartTimeUtcMillis(),
                source.getEndTimeUtcMillis(),
                source.getDurationSeconds(),
                source.getDistanceMeters(),
                source.getTrackPointCount(),
                source.getRoutePointCount(),
                source.getHeartRatePointCount(),
                acknowledgementPending);
    }

    private static long sortTime(ActivityListItem item) {
        Long start = item.getStartTimeUtcMillis();
        return start == null ? Long.MIN_VALUE : start;
    }

    private static String normalized(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
