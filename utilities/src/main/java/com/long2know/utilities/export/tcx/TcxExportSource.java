package com.long2know.utilities.export.tcx;

import java.io.IOException;

/**
 * Narrow, read-only input used by the TCX writer.
 *
 * <p>Nullable boxed values represent unavailable source data. A value of zero remains an explicit
 * value when it is present.
 */
public interface TcxExportSource {
    enum Sport {
        RUNNING("Running"),
        BIKING("Biking"),
        OTHER("Other");

        private final String xmlValue;

        Sport(String xmlValue) {
            this.xmlValue = xmlValue;
        }

        public String getXmlValue() {
            return xmlValue;
        }
    }

    enum Intensity {
        ACTIVE("Active"),
        RESTING("Resting");

        private final String xmlValue;

        Intensity(String xmlValue) {
            this.xmlValue = xmlValue;
        }

        public String getXmlValue() {
            return xmlValue;
        }
    }

    enum TriggerMethod {
        MANUAL("Manual"),
        DISTANCE("Distance"),
        LOCATION("Location"),
        TIME("Time"),
        HEART_RATE("HeartRate");

        private final String xmlValue;

        TriggerMethod(String xmlValue) {
            this.xmlValue = xmlValue;
        }

        public String getXmlValue() {
            return xmlValue;
        }
    }

    Long getStartTimeUtcMillis();

    Double getDurationSeconds();

    Double getDistanceMeters();

    Integer getCalories();

    Intensity getIntensity();

    TriggerMethod getTriggerMethod();

    Sport getSport();

    Integer getAverageHeartRateBpm();

    Integer getMaximumHeartRateBpm();

    String getNotes();

    TrackPointCursor openTrackPoints() throws IOException;

    interface TrackPointCursor extends AutoCloseable {
        boolean moveNext() throws IOException;

        Long getTimeUtcMillis() throws IOException;

        boolean hasPosition() throws IOException;

        double getLatitudeDegrees() throws IOException;

        double getLongitudeDegrees() throws IOException;

        Double getAltitudeMeters() throws IOException;

        Double getDistanceMeters() throws IOException;

        Integer getHeartRateBpm() throws IOException;

        @Override
        void close() throws IOException;
    }
}
