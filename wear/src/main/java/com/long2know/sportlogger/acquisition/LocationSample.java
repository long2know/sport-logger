package com.long2know.sportlogger.acquisition;

import java.util.Objects;

public final class LocationSample {
    private final double latitude;
    private final double longitude;
    private final Double altitudeMeters;
    private final Double bearingDegrees;
    private final Double horizontalAccuracyMeters;

    public LocationSample(
            double latitude,
            double longitude,
            Double altitudeMeters,
            Double bearingDegrees,
            Double horizontalAccuracyMeters) {
        if (!Double.isFinite(latitude) || latitude < -90D || latitude > 90D) {
            throw new IllegalArgumentException("Latitude is outside [-90, 90]");
        }
        if (!Double.isFinite(longitude) || longitude < -180D || longitude > 180D) {
            throw new IllegalArgumentException("Longitude is outside [-180, 180]");
        }
        requireFinite("altitude", altitudeMeters);
        requireFinite("bearing", bearingDegrees);
        requireFinite("horizontal accuracy", horizontalAccuracyMeters);
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitudeMeters = altitudeMeters;
        this.bearingDegrees = bearingDegrees;
        this.horizontalAccuracyMeters = horizontalAccuracyMeters;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public Double getAltitudeMeters() {
        return altitudeMeters;
    }

    public Double getBearingDegrees() {
        return bearingDegrees;
    }

    public Double getHorizontalAccuracyMeters() {
        return horizontalAccuracyMeters;
    }

    private static void requireFinite(String name, Double value) {
        if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite when present");
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LocationSample)) {
            return false;
        }
        LocationSample that = (LocationSample) other;
        return Double.compare(latitude, that.latitude) == 0
                && Double.compare(longitude, that.longitude) == 0
                && Objects.equals(altitudeMeters, that.altitudeMeters)
                && Objects.equals(bearingDegrees, that.bearingDegrees)
                && Objects.equals(horizontalAccuracyMeters, that.horizontalAccuracyMeters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                latitude,
                longitude,
                altitudeMeters,
                bearingDegrees,
                horizontalAccuracyMeters);
    }
}
