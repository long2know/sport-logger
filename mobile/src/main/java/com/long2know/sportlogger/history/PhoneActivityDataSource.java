package com.long2know.sportlogger.history;

import com.long2know.utilities.export.tcx.TcxExportSource;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public interface PhoneActivityDataSource {
    HistoryResult loadHistory(int limit) throws IOException;

    ActivityRecord loadActivity(String storageId) throws IOException;

    final class HistoryResult {
        private final List<ActivityListItem> activities;
        private final int totalStoredCount;
        private final int corruptCount;
        private final int tooLargeCount;
        private final int unsupportedCount;
        private final int ioFailureCount;

        public HistoryResult(
                List<ActivityListItem> activities,
                int totalStoredCount,
                int corruptCount,
                int tooLargeCount,
                int unsupportedCount,
                int ioFailureCount) {
            this.activities = Collections.unmodifiableList(activities);
            this.totalStoredCount = totalStoredCount;
            this.corruptCount = corruptCount;
            this.tooLargeCount = tooLargeCount;
            this.unsupportedCount = unsupportedCount;
            this.ioFailureCount = ioFailureCount;
        }

        public List<ActivityListItem> getActivities() {
            return activities;
        }

        public int getTotalStoredCount() {
            return totalStoredCount;
        }

        public int getCorruptCount() {
            return corruptCount;
        }

        public int getTooLargeCount() {
            return tooLargeCount;
        }

        public int getUnsupportedCount() {
            return unsupportedCount;
        }

        public int getIoFailureCount() {
            return ioFailureCount;
        }

        public int getUnavailableCount() {
            return corruptCount + tooLargeCount + unsupportedCount + ioFailureCount;
        }

        public boolean isTruncated() {
            return totalStoredCount
                    > activities.size() + getUnavailableCount();
        }
    }

    final class ActivityListItem {
        private final String storageId;
        private final String name;
        private final Long startTimeUtcMillis;
        private final Long endTimeUtcMillis;
        private final Double durationSeconds;
        private final Double distanceMeters;
        private final int trackPointCount;
        private final int routePointCount;
        private final int heartRatePointCount;
        private final boolean acknowledgementPending;

        public ActivityListItem(
                String storageId,
                String name,
                Long startTimeUtcMillis,
                Long endTimeUtcMillis,
                Double durationSeconds,
                Double distanceMeters,
                int trackPointCount,
                int routePointCount,
                int heartRatePointCount,
                boolean acknowledgementPending) {
            this.storageId = storageId;
            this.name = name;
            this.startTimeUtcMillis = startTimeUtcMillis;
            this.endTimeUtcMillis = endTimeUtcMillis;
            this.durationSeconds = durationSeconds;
            this.distanceMeters = distanceMeters;
            this.trackPointCount = trackPointCount;
            this.routePointCount = routePointCount;
            this.heartRatePointCount = heartRatePointCount;
            this.acknowledgementPending = acknowledgementPending;
        }

        public String getStorageId() {
            return storageId;
        }

        public String getName() {
            return name;
        }

        public Long getStartTimeUtcMillis() {
            return startTimeUtcMillis;
        }

        public Long getEndTimeUtcMillis() {
            return endTimeUtcMillis;
        }

        public Double getDurationSeconds() {
            return durationSeconds;
        }

        public Double getDistanceMeters() {
            return distanceMeters;
        }

        public int getTrackPointCount() {
            return trackPointCount;
        }

        public int getRoutePointCount() {
            return routePointCount;
        }

        public int getHeartRatePointCount() {
            return heartRatePointCount;
        }

        public boolean isAcknowledgementPending() {
            return acknowledgementPending;
        }
    }

    final class ActivityRecord {
        private final ActivityListItem summary;
        private final String description;
        private final TcxExportSource exportSource;

        public ActivityRecord(
                ActivityListItem summary,
                String description,
                TcxExportSource exportSource) {
            this.summary = summary;
            this.description = description;
            this.exportSource = exportSource;
        }

        public ActivityListItem getSummary() {
            return summary;
        }

        public String getDescription() {
            return description;
        }

        public TcxExportSource getExportSource() {
            return exportSource;
        }
    }
}
