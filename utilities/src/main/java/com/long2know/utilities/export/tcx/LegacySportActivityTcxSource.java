package com.long2know.utilities.export.tcx;

import com.long2know.utilities.models.SportActivity;
import com.long2know.utilities.models.SportTrackPoint;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Conservative adapter for the legacy serialized activity model.
 *
 * <p>The legacy primitive fields do not carry presence. Zero coordinates are therefore treated as
 * an absent legacy position, while the generic writer continues to preserve explicitly present
 * zero coordinates from future sources.
 */
public final class LegacySportActivityTcxSource implements TcxExportSource {
    private static final double EARTH_RADIUS_METERS = 6_371_000D;

    private final SportActivity activity;
    private final List<SportTrackPoint> trackPoints;
    private final Double durationSeconds;
    private final Double distanceMeters;
    private final Integer averageHeartRate;
    private final Integer maximumHeartRate;
    private final int routePointCount;
    private final int heartRatePointCount;

    public LegacySportActivityTcxSource(SportActivity activity) {
        this.activity = Objects.requireNonNull(activity, "activity");
        this.trackPoints = activity.SportTrackPoints == null
                ? Collections.emptyList()
                : activity.SportTrackPoints;
        this.durationSeconds = duration(activity);

        Scan scan = scan(trackPoints);
        this.routePointCount = scan.routePointCount;
        this.heartRatePointCount = scan.heartRatePointCount;
        this.averageHeartRate = scan.averageHeartRate;
        this.maximumHeartRate = scan.maximumHeartRate;
        if (activity.Distance != 0D) {
            this.distanceMeters = activity.Distance;
        } else {
            this.distanceMeters = scan.routePointCount == 0 ? null : scan.distanceMeters;
        }
    }

    @Override
    public Long getStartTimeUtcMillis() {
        return activity.StartTimeUTC == null ? null : activity.StartTimeUTC.getTime();
    }

    @Override
    public Double getDurationSeconds() {
        return durationSeconds;
    }

    @Override
    public Double getDistanceMeters() {
        return distanceMeters;
    }

    @Override
    public Integer getCalories() {
        return null;
    }

    @Override
    public Intensity getIntensity() {
        return null;
    }

    @Override
    public TriggerMethod getTriggerMethod() {
        return null;
    }

    @Override
    public Sport getSport() {
        return null;
    }

    @Override
    public Integer getAverageHeartRateBpm() {
        return averageHeartRate;
    }

    @Override
    public Integer getMaximumHeartRateBpm() {
        return maximumHeartRate;
    }

    @Override
    public String getNotes() {
        String name = normalized(activity.Name);
        String description = normalized(activity.Description);
        if (name == null) {
            return description;
        }
        if (description == null) {
            return name;
        }
        return name + "\n" + description;
    }

    @Override
    public TrackPointCursor openTrackPoints() {
        return new LegacyCursor(trackPoints);
    }

    public Long getEndTimeUtcMillis() {
        return activity.EndTimeUTC == null ? null : activity.EndTimeUTC.getTime();
    }

    public int getTrackPointCount() {
        return trackPoints.size();
    }

    public int getRoutePointCount() {
        return routePointCount;
    }

    public int getHeartRatePointCount() {
        return heartRatePointCount;
    }

    private static Double duration(SportActivity activity) {
        if (activity.StartTimeUTC == null || activity.EndTimeUTC == null) {
            return null;
        }
        return (activity.EndTimeUTC.getTime() - activity.StartTimeUTC.getTime()) / 1_000D;
    }

    private static Scan scan(List<SportTrackPoint> points) {
        double distance = 0D;
        SportTrackPoint previous = null;
        int routePoints = 0;
        int heartRatePoints = 0;
        long heartRateTotal = 0L;
        int maximumHeartRate = 0;
        boolean invalidRoute = false;

        for (SportTrackPoint point : points) {
            if (point == null) {
                previous = null;
                continue;
            }
            if (isLegacyPositionAbsent(point)) {
                previous = null;
            } else if (isValidPosition(point.Latitude, point.Longitude)) {
                routePoints++;
                if (previous != null) {
                    distance += haversineMeters(
                            previous.Latitude,
                            previous.Longitude,
                            point.Latitude,
                            point.Longitude);
                }
                previous = point;
            } else {
                invalidRoute = true;
                previous = null;
            }

            Integer heartRate = legacyHeartRate(point.HeartRate);
            if (heartRate != null) {
                heartRatePoints++;
                heartRateTotal += heartRate;
                maximumHeartRate = Math.max(maximumHeartRate, heartRate);
            }
        }

        Double scannedDistance = invalidRoute ? Double.NaN : distance;
        Integer average = heartRatePoints == 0
                ? null
                : (int) Math.round((double) heartRateTotal / heartRatePoints);
        Integer maximum = heartRatePoints == 0 ? null : maximumHeartRate;
        return new Scan(
                scannedDistance,
                routePoints,
                heartRatePoints,
                average,
                maximum);
    }

    private static boolean isLegacyPositionAbsent(SportTrackPoint point) {
        return point.Latitude == 0D && point.Longitude == 0D;
    }

    private static boolean isValidPosition(double latitude, double longitude) {
        return Double.isFinite(latitude)
                && Double.isFinite(longitude)
                && latitude >= -90D
                && latitude <= 90D
                && longitude >= -180D
                && longitude < 180D;
    }

    private static double haversineMeters(
            double startLatitude,
            double startLongitude,
            double endLatitude,
            double endLongitude) {
        double latitudeDelta = Math.toRadians(endLatitude - startLatitude);
        double longitudeDelta = Math.toRadians(endLongitude - startLongitude);
        double startLatitudeRadians = Math.toRadians(startLatitude);
        double endLatitudeRadians = Math.toRadians(endLatitude);
        double sinLatitude = Math.sin(latitudeDelta / 2D);
        double sinLongitude = Math.sin(longitudeDelta / 2D);
        double a = sinLatitude * sinLatitude
                + Math.cos(startLatitudeRadians)
                * Math.cos(endLatitudeRadians)
                * sinLongitude
                * sinLongitude;
        return EARTH_RADIUS_METERS * 2D * Math.atan2(Math.sqrt(a), Math.sqrt(1D - a));
    }

    private static String normalized(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Integer legacyHeartRate(double value) {
        if (value <= 0D) {
            return null;
        }
        if (!Double.isFinite(value)) {
            return 256;
        }
        return (int) Math.round(value);
    }

    private static final class Scan {
        private final double distanceMeters;
        private final int routePointCount;
        private final int heartRatePointCount;
        private final Integer averageHeartRate;
        private final Integer maximumHeartRate;

        private Scan(
                double distanceMeters,
                int routePointCount,
                int heartRatePointCount,
                Integer averageHeartRate,
                Integer maximumHeartRate) {
            this.distanceMeters = distanceMeters;
            this.routePointCount = routePointCount;
            this.heartRatePointCount = heartRatePointCount;
            this.averageHeartRate = averageHeartRate;
            this.maximumHeartRate = maximumHeartRate;
        }
    }

    private static final class LegacyCursor implements TrackPointCursor {
        private final List<SportTrackPoint> points;
        private int index = -1;
        private SportTrackPoint current;

        private LegacyCursor(List<SportTrackPoint> points) {
            this.points = points;
        }

        @Override
        public boolean moveNext() {
            index++;
            if (index >= points.size()) {
                current = null;
                return false;
            }
            current = points.get(index);
            return true;
        }

        @Override
        public Long getTimeUtcMillis() {
            return current == null || current.TimeStampUTC == null
                    ? null
                    : current.TimeStampUTC.getTime();
        }

        @Override
        public boolean hasPosition() {
            return current != null && !isLegacyPositionAbsent(current);
        }

        @Override
        public double getLatitudeDegrees() {
            return current.Latitude;
        }

        @Override
        public double getLongitudeDegrees() {
            return current.Longitude;
        }

        @Override
        public Double getAltitudeMeters() {
            if (current == null || current.Altitude == 0D) {
                return null;
            }
            return current.Altitude;
        }

        @Override
        public Double getDistanceMeters() {
            return null;
        }

        @Override
        public Integer getHeartRateBpm() {
            return current == null ? null : legacyHeartRate(current.HeartRate);
        }

        @Override
        public void close() throws IOException {
        }
    }
}
