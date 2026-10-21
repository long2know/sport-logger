package com.long2know.sportlogger;

import android.content.Context;

import com.long2know.sportlogger.history.PhoneActivityDataSource.ActivityListItem;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

final class ActivityTextFormatter {
    private ActivityTextFormatter() {
    }

    static String title(Context context, ActivityListItem item) {
        String name = item.getName();
        return name == null ? context.getString(R.string.activity_untitled) : name;
    }

    static String dateTime(Context context, ActivityListItem item) {
        Long start = item.getStartTimeUtcMillis();
        if (start == null) {
            return context.getString(R.string.value_unavailable);
        }
        return DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM,
                DateFormat.SHORT,
                Locale.getDefault()).format(new Date(start));
    }

    static String dateTime(Context context, Long value) {
        if (value == null) {
            return context.getString(R.string.value_unavailable);
        }
        return DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM,
                DateFormat.MEDIUM,
                Locale.getDefault()).format(new Date(value));
    }

    static String metrics(Context context, ActivityListItem item) {
        return context.getString(
                R.string.activity_metric_summary,
                duration(context, item.getDurationSeconds()),
                distance(context, item.getDistanceMeters()),
                context.getResources().getQuantityString(
                        R.plurals.track_point_count,
                        item.getTrackPointCount(),
                        item.getTrackPointCount()));
    }

    static String duration(Context context, Double seconds) {
        if (seconds == null || !Double.isFinite(seconds) || seconds < 0D) {
            return context.getString(R.string.value_unavailable);
        }
        long totalSeconds = Math.round(seconds);
        long hours = totalSeconds / 3_600L;
        long minutes = (totalSeconds / 60L) % 60L;
        long remainder = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(
                    Locale.getDefault(),
                    "%d:%02d:%02d",
                    hours,
                    minutes,
                    remainder);
        }
        return String.format(
                Locale.getDefault(),
                "%d:%02d",
                minutes,
                remainder);
    }

    static String distance(Context context, Double meters) {
        if (meters == null || !Double.isFinite(meters) || meters < 0D) {
            return context.getString(R.string.value_unavailable);
        }
        if (meters < 1_000D) {
            return context.getString(R.string.distance_meters, Math.round(meters));
        }
        return context.getString(R.string.distance_kilometers, meters / 1_000D);
    }

    static String transferState(Context context, ActivityListItem item) {
        return context.getString(
                item.isAcknowledgementPending()
                        ? R.string.transfer_state_ack_pending
                        : R.string.transfer_state_received);
    }
}
