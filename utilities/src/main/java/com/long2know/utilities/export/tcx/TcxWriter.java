package com.long2know.utilities.export.tcx;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;

public final class TcxWriter {
    public static final String MEDIA_TYPE = "application/vnd.garmin.tcx+xml";
    public static final int MAX_TRACK_POINTS = 250_000;

    private static final String TCX_NAMESPACE =
            "http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2";
    private static final String XSI_NAMESPACE =
            "http://www.w3.org/2001/XMLSchema-instance";
    private static final String SCHEMA_LOCATION =
            TCX_NAMESPACE + " http://www.garmin.com/xmlschemas/TrainingCenterDatabasev2.xsd";
    private static final String SPORT_LOGGER_NAMESPACE =
            "urn:com.long2know.sportlogger:tcx:export:v1";

    public static final class Assessment {
        private final List<String> placeholderFields;

        private Assessment(List<String> placeholderFields) {
            this.placeholderFields =
                    Collections.unmodifiableList(new ArrayList<>(placeholderFields));
        }

        public boolean hasFormatPlaceholders() {
            return !placeholderFields.isEmpty();
        }

        public List<String> getPlaceholderFields() {
            return placeholderFields;
        }
    }

    public void validate(TcxExportSource source) throws TcxExportException {
        readHeader(source);
    }

    public Assessment assess(TcxExportSource source) throws TcxExportException {
        return readHeader(source).assessment;
    }

    public void write(TcxExportSource source, OutputStream output)
            throws TcxExportException {
        write(source, output, TcxCancellation.NONE);
    }

    public void write(
            TcxExportSource source,
            OutputStream output,
            TcxCancellation cancellation) throws TcxExportException {
        Header header = readHeader(source);
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(cancellation, "cancellation");
        checkCancelled(cancellation);

        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(output, StandardCharsets.UTF_8),
                16 * 1024);
        SimpleDateFormat dateFormat =
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

        writeRaw(writer, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        writeRaw(writer, "<TrainingCenterDatabase xmlns=\"");
        writeRaw(writer, TCX_NAMESPACE);
        writeRaw(writer, "\" xmlns:xsi=\"");
        writeRaw(writer, XSI_NAMESPACE);
        if (header.assessment.hasFormatPlaceholders()) {
            writeRaw(writer, "\" xmlns:sl=\"");
            writeRaw(writer, SPORT_LOGGER_NAMESPACE);
        }
        writeRaw(writer, "\" xsi:schemaLocation=\"");
        writeRaw(writer, SCHEMA_LOCATION);
        writeRaw(writer, "\"><Activities><Activity Sport=\"");
        writeRaw(writer, header.sport.getXmlValue());
        writeRaw(writer, "\"><Id>");
        writeRaw(writer, formatTime(header.startTimeUtcMillis, dateFormat));
        writeRaw(writer, "</Id><Lap StartTime=\"");
        writeRaw(writer, formatTime(header.startTimeUtcMillis, dateFormat));
        writeRaw(writer, "\"><TotalTimeSeconds>");
        writeRaw(writer, formatNumber(header.durationSeconds));
        writeRaw(writer, "</TotalTimeSeconds><DistanceMeters>");
        writeRaw(writer, formatNumber(header.distanceMeters));
        writeRaw(writer, "</DistanceMeters><Calories>");
        writeRaw(writer, Integer.toString(header.calories));
        writeRaw(writer, "</Calories>");
        if (header.averageHeartRateBpm != null) {
            writeHeartRate(writer, "AverageHeartRateBpm", header.averageHeartRateBpm);
        }
        if (header.maximumHeartRateBpm != null) {
            writeHeartRate(writer, "MaximumHeartRateBpm", header.maximumHeartRateBpm);
        }
        writeRaw(writer, "<Intensity>");
        writeRaw(writer, header.intensity.getXmlValue());
        writeRaw(writer, "</Intensity><TriggerMethod>");
        writeRaw(writer, header.triggerMethod.getXmlValue());
        writeRaw(writer, "</TriggerMethod>");

        writeTrack(source, writer, dateFormat, cancellation);

        writePlaceholderExtension(writer, header.assessment);
        writeRaw(writer, "</Lap>");
        if (header.notes != null && !header.notes.trim().isEmpty()) {
            writeRaw(writer, "<Notes>");
            writeText(writer, header.notes);
            writeRaw(writer, "</Notes>");
        }
        writeRaw(writer, "</Activity></Activities></TrainingCenterDatabase>");
        flush(writer);
    }

    private static Header readHeader(TcxExportSource source) throws TcxExportException {
        Objects.requireNonNull(source, "source");
        Long startTime = source.getStartTimeUtcMillis();
        if (startTime == null) {
            throw missing(
                    TcxExportException.Code.MISSING_START_TIME,
                    "startTimeUtcMillis",
                    "TCX export requires a UTC start time");
        }
        Double duration = source.getDurationSeconds();
        if (duration == null) {
            throw missing(
                    TcxExportException.Code.MISSING_DURATION,
                    "durationSeconds",
                    "TCX export requires a duration");
        }
        requireFiniteNonNegative(duration, "durationSeconds");

        Double distance = source.getDistanceMeters();
        List<String> placeholders = new ArrayList<>();
        if (distance == null) {
            distance = 0D;
            placeholders.add("DistanceMeters");
        } else {
            requireFiniteNonNegative(distance, "distanceMeters");
        }

        Integer calories = source.getCalories();
        if (calories == null) {
            calories = 0;
            placeholders.add("Calories");
        }
        if (calories < 0 || calories > 65_535) {
            throw invalid("calories", "Calories must be in range 0..65535");
        }

        TcxExportSource.Intensity intensity = source.getIntensity();
        if (intensity == null) {
            intensity = TcxExportSource.Intensity.ACTIVE;
            placeholders.add("Intensity");
        }
        TcxExportSource.TriggerMethod triggerMethod = source.getTriggerMethod();
        if (triggerMethod == null) {
            triggerMethod = TcxExportSource.TriggerMethod.MANUAL;
            placeholders.add("TriggerMethod");
        }
        TcxExportSource.Sport sport = source.getSport();
        if (sport == null) {
            sport = TcxExportSource.Sport.OTHER;
            placeholders.add("Sport");
        }

        Integer averageHeartRate = source.getAverageHeartRateBpm();
        Integer maximumHeartRate = source.getMaximumHeartRateBpm();
        validateHeartRate(averageHeartRate, "averageHeartRateBpm");
        validateHeartRate(maximumHeartRate, "maximumHeartRateBpm");
        if (averageHeartRate != null
                && maximumHeartRate != null
                && averageHeartRate > maximumHeartRate) {
            throw invalid(
                    "heartRateBpm",
                    "Average heart rate cannot exceed maximum heart rate");
        }
        Assessment assessment = new Assessment(placeholders);
        return new Header(
                startTime,
                duration,
                distance,
                calories,
                intensity,
                triggerMethod,
                sport,
                averageHeartRate,
                maximumHeartRate,
                mergeNotes(source.getNotes(), assessment),
                assessment);
    }

    private static void writeTrack(
            TcxExportSource source,
            BufferedWriter writer,
            SimpleDateFormat dateFormat,
            TcxCancellation cancellation) throws TcxExportException {
        TcxExportSource.TrackPointCursor cursor;
        try {
            cursor = source.openTrackPoints();
        } catch (IOException | RuntimeException error) {
            throw sourceFailure("Could not open TCX track points", error);
        }
        if (cursor == null) {
            throw sourceFailure(
                    "TCX source returned no track point cursor",
                    new NullPointerException("trackPointCursor"));
        }

        TcxExportException primaryFailure = null;
        try {
            if (!moveNext(cursor)) {
                return;
            }
            writeRaw(writer, "<Track>");
            long previousTime = Long.MIN_VALUE;
            Double previousDistance = null;
            int pointCount = 0;
            do {
                checkCancelled(cancellation);
                pointCount++;
                if (pointCount > MAX_TRACK_POINTS) {
                    throw new TcxExportException(
                            TcxExportException.Code.TOO_MANY_TRACK_POINTS,
                            "trackPoints",
                            "TCX export exceeds the track point limit");
                }

                Long time = read(cursor::getTimeUtcMillis, "trackPoint.timeUtcMillis");
                if (time == null) {
                    throw new TcxExportException(
                            TcxExportException.Code.INVALID_TRACK_POINT,
                            "trackPoint.timeUtcMillis",
                            "Track point time is required");
                }
                if (time < previousTime) {
                    throw new TcxExportException(
                            TcxExportException.Code.OUT_OF_ORDER_TRACK_POINT,
                            "trackPoint.timeUtcMillis",
                            "Track point times must be ordered");
                }
                previousTime = time;

                writeRaw(writer, "<Trackpoint><Time>");
                writeRaw(writer, formatTime(time, dateFormat));
                writeRaw(writer, "</Time>");

                Boolean hasPosition = read(cursor::hasPosition, "trackPoint.position");
                if (hasPosition) {
                    double latitude = read(cursor::getLatitudeDegrees, "trackPoint.latitude");
                    double longitude = read(cursor::getLongitudeDegrees, "trackPoint.longitude");
                    validatePosition(latitude, longitude);
                    writeRaw(writer, "<Position><LatitudeDegrees>");
                    writeRaw(writer, formatNumber(latitude));
                    writeRaw(writer, "</LatitudeDegrees><LongitudeDegrees>");
                    writeRaw(writer, formatNumber(longitude));
                    writeRaw(writer, "</LongitudeDegrees></Position>");
                }

                Double altitude = read(cursor::getAltitudeMeters, "trackPoint.altitudeMeters");
                if (altitude != null) {
                    requireFinite(altitude, "trackPoint.altitudeMeters");
                    writeElement(writer, "AltitudeMeters", formatNumber(altitude));
                }

                Double distance = read(cursor::getDistanceMeters, "trackPoint.distanceMeters");
                if (distance != null) {
                    requireFiniteNonNegative(distance, "trackPoint.distanceMeters");
                    if (previousDistance != null && distance < previousDistance) {
                        throw new TcxExportException(
                                TcxExportException.Code.INVALID_TRACK_POINT,
                                "trackPoint.distanceMeters",
                                "Track point distance must not decrease");
                    }
                    previousDistance = distance;
                    writeElement(writer, "DistanceMeters", formatNumber(distance));
                }

                Integer heartRate = read(cursor::getHeartRateBpm, "trackPoint.heartRateBpm");
                if (heartRate != null) {
                    validateHeartRate(heartRate, "trackPoint.heartRateBpm");
                    writeHeartRate(writer, "HeartRateBpm", heartRate);
                }
                writeRaw(writer, "</Trackpoint>");
            } while (moveNext(cursor));
            writeRaw(writer, "</Track>");
        } catch (TcxExportException error) {
            primaryFailure = error;
            throw error;
        } finally {
            try {
                cursor.close();
            } catch (IOException | RuntimeException error) {
                TcxExportException closeFailure =
                        sourceFailure("Could not close TCX track point source", error);
                if (primaryFailure == null) {
                    throw closeFailure;
                }
                primaryFailure.addSuppressed(closeFailure);
            }
        }
    }

    private static boolean moveNext(TcxExportSource.TrackPointCursor cursor)
            throws TcxExportException {
        return read(cursor::moveNext, "trackPoints");
    }

    private static <T> T read(SourceReader<T> reader, String field)
            throws TcxExportException {
        try {
            return reader.read();
        } catch (IOException | RuntimeException error) {
            throw new TcxExportException(
                    TcxExportException.Code.SOURCE_READ_FAILED,
                    field,
                    "Could not read TCX source data",
                    error);
        }
    }

    private static void writeHeartRate(
            BufferedWriter writer,
            String element,
            int beatsPerMinute) throws TcxExportException {
        writeRaw(writer, "<" + element + "><Value>");
        writeRaw(writer, Integer.toString(beatsPerMinute));
        writeRaw(writer, "</Value></" + element + ">");
    }

    private static void writePlaceholderExtension(
            BufferedWriter writer,
            Assessment assessment) throws TcxExportException {
        if (!assessment.hasFormatPlaceholders()) {
            return;
        }
        writeRaw(writer, "<Extensions><sl:ExportMetadata>");
        for (String field : assessment.getPlaceholderFields()) {
            writeRaw(writer, "<sl:Placeholder Field=\"");
            writeRaw(writer, field);
            writeRaw(writer, "\" Reason=\"not-recorded\"/>");
        }
        writeRaw(writer, "</sl:ExportMetadata></Extensions>");
    }

    private static String mergeNotes(String sourceNotes, Assessment assessment) {
        if (!assessment.hasFormatPlaceholders()) {
            return sourceNotes;
        }
        StringBuilder warning = new StringBuilder(
                "SportLogger format limitation: TCX requires values that were not recorded. "
                        + "Schema placeholders are marked in the SportLogger extension: ");
        for (int index = 0; index < assessment.placeholderFields.size(); index++) {
            if (index > 0) {
                warning.append(", ");
            }
            String field = assessment.placeholderFields.get(index);
            warning.append(field).append('=').append(placeholderValue(field));
        }
        warning.append(". These placeholders are not measured workout values.");
        if (sourceNotes == null || sourceNotes.trim().isEmpty()) {
            return warning.toString();
        }
        return sourceNotes + "\n\n" + warning;
    }

    private static String placeholderValue(String field) {
        switch (field) {
            case "DistanceMeters":
            case "Calories":
                return "0 (not recorded)";
            case "Intensity":
                return "Active (not recorded)";
            case "TriggerMethod":
                return "Manual (not recorded)";
            case "Sport":
                return "Other (not recorded)";
            default:
                throw new IllegalArgumentException("Unknown TCX placeholder field");
        }
    }

    private static void writeElement(
            BufferedWriter writer,
            String element,
            String value) throws TcxExportException {
        writeRaw(writer, "<" + element + ">");
        writeRaw(writer, value);
        writeRaw(writer, "</" + element + ">");
    }

    private static void writeText(BufferedWriter writer, String value)
            throws TcxExportException {
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            switch (codePoint) {
                case '&':
                    writeRaw(writer, "&amp;");
                    break;
                case '<':
                    writeRaw(writer, "&lt;");
                    break;
                case '>':
                    writeRaw(writer, "&gt;");
                    break;
                default:
                    if (!isXmlCharacter(codePoint)) {
                        writeRaw(writer, "\uFFFD");
                    } else {
                        writeRaw(writer, new String(Character.toChars(codePoint)));
                    }
                    break;
            }
        }
    }

    private static boolean isXmlCharacter(int codePoint) {
        return codePoint == 0x9
                || codePoint == 0xA
                || codePoint == 0xD
                || (codePoint >= 0x20 && codePoint <= 0xD7FF)
                || (codePoint >= 0xE000 && codePoint <= 0xFFFD)
                || (codePoint >= 0x10000 && codePoint <= 0x10FFFF);
    }

    private static void validatePosition(double latitude, double longitude)
            throws TcxExportException {
        requireFinite(latitude, "trackPoint.latitude");
        requireFinite(longitude, "trackPoint.longitude");
        if (latitude < -90D || latitude > 90D) {
            throw invalid(
                    "trackPoint.latitude",
                    "Latitude must be in range -90..90");
        }
        if (longitude < -180D || longitude >= 180D) {
            throw invalid(
                    "trackPoint.longitude",
                    "Longitude must be in range -180 inclusive to 180 exclusive");
        }
    }

    private static void validateHeartRate(Integer value, String field)
            throws TcxExportException {
        if (value != null && (value < 1 || value > 255)) {
            throw invalid(field, "Heart rate must be in range 1..255");
        }
    }

    private static void requireFiniteNonNegative(double value, String field)
            throws TcxExportException {
        requireFinite(value, field);
        if (value < 0D) {
            throw invalid(field, "Value must not be negative");
        }
    }

    private static void requireFinite(double value, String field)
            throws TcxExportException {
        if (!Double.isFinite(value)) {
            throw invalid(field, "Value must be finite");
        }
    }

    private static String formatTime(long epochMillis, SimpleDateFormat dateFormat) {
        return dateFormat.format(new Date(epochMillis));
    }

    private static String formatNumber(double value) {
        double normalized = value == 0D ? 0D : value;
        return BigDecimal.valueOf(normalized).stripTrailingZeros().toPlainString();
    }

    private static void checkCancelled(TcxCancellation cancellation)
            throws TcxExportException {
        if (cancellation.isCancellationRequested()) {
            throw new TcxExportException(
                    TcxExportException.Code.CANCELLED,
                    "export",
                    "TCX export was cancelled");
        }
    }

    private static TcxExportException missing(
            TcxExportException.Code code,
            String field,
            String message) {
        return new TcxExportException(code, field, message);
    }

    private static TcxExportException invalid(String field, String message) {
        return new TcxExportException(
                TcxExportException.Code.INVALID_VALUE,
                field,
                message);
    }

    private static TcxExportException sourceFailure(String message, Throwable cause) {
        return new TcxExportException(
                TcxExportException.Code.SOURCE_READ_FAILED,
                "source",
                message,
                cause);
    }

    private static void writeRaw(BufferedWriter writer, String value)
            throws TcxExportException {
        try {
            writer.write(value);
        } catch (IOException error) {
            throw new TcxExportException(
                    TcxExportException.Code.OUTPUT_WRITE_FAILED,
                    "output",
                    "Could not write TCX output",
                    error);
        }
    }

    private static void flush(BufferedWriter writer) throws TcxExportException {
        try {
            writer.flush();
        } catch (IOException error) {
            throw new TcxExportException(
                    TcxExportException.Code.OUTPUT_WRITE_FAILED,
                    "output",
                    "Could not flush TCX output",
                    error);
        }
    }

    private interface SourceReader<T> {
        T read() throws IOException;
    }

    private static final class Header {
        private final long startTimeUtcMillis;
        private final double durationSeconds;
        private final double distanceMeters;
        private final int calories;
        private final TcxExportSource.Intensity intensity;
        private final TcxExportSource.TriggerMethod triggerMethod;
        private final TcxExportSource.Sport sport;
        private final Integer averageHeartRateBpm;
        private final Integer maximumHeartRateBpm;
        private final String notes;
        private final Assessment assessment;

        private Header(
                long startTimeUtcMillis,
                double durationSeconds,
                double distanceMeters,
                int calories,
                TcxExportSource.Intensity intensity,
                TcxExportSource.TriggerMethod triggerMethod,
                TcxExportSource.Sport sport,
                Integer averageHeartRateBpm,
                Integer maximumHeartRateBpm,
                String notes,
                Assessment assessment) {
            this.startTimeUtcMillis = startTimeUtcMillis;
            this.durationSeconds = durationSeconds;
            this.distanceMeters = distanceMeters;
            this.calories = calories;
            this.intensity = intensity;
            this.triggerMethod = triggerMethod;
            this.sport = sport;
            this.averageHeartRateBpm = averageHeartRateBpm;
            this.maximumHeartRateBpm = maximumHeartRateBpm;
            this.notes = notes;
            this.assessment = assessment;
        }
    }
}
