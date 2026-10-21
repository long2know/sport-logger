package com.long2know.utilities.export.tcx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.long2know.utilities.models.SportActivity;
import com.long2know.utilities.models.SportTrackPoint;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

public final class TcxWriterTest {
    private static final String NAMESPACE =
            "http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2";
    private static final String SPORT_LOGGER_NAMESPACE =
            "urn:com.long2know.sportlogger:tcx:export:v1";
    private static final long START = 1_704_067_200_123L;

    @Test
    public void writesSchemaValidatedUtcActivityAndEscapesUserText() throws Exception {
        SyntheticSource source = completeSource();
        source.notes = "Synthetic & <test>\u0001 \uD83D\uDE00";
        source.points = Arrays.asList(
                new Point(START, true, 0D, 0D, 0D, 0D, 80),
                new Point(START + 1_000L, true, 0.1D, 0.2D, 5.25D, 12.345678D, 90));
        TrackingOutputStream output = new TrackingOutputStream();

        new TcxWriter().write(source, output);

        assertFalse(output.closed);
        byte[] xml = output.bytes.toByteArray();
        validateProfile(xml);
        Document document = parse(xml);
        assertEquals(
                "2024-01-01T00:00:00.123Z",
                text(document, "Id"));
        assertEquals(
                "Synthetic & <test>\uFFFD \uD83D\uDE00",
                text(document, "Notes"));
        Element firstPosition = (Element) document
                .getElementsByTagNameNS(NAMESPACE, "Position")
                .item(0);
        assertNotNull(firstPosition);
        assertEquals(
                "0",
                firstPosition
                        .getElementsByTagNameNS(NAMESPACE, "LatitudeDegrees")
                        .item(0)
                        .getTextContent());
        assertEquals("12.345678", text(document, "DistanceMeters", 2));
    }

    @Test
    public void supportsIndoorActivityWithoutRouteOrOptionalMetrics() throws Exception {
        SyntheticSource source = completeSource();
        source.distanceMeters = 0D;
        source.points = Arrays.asList(
                new Point(START, false, 0D, 0D, null, null, null));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        new TcxWriter().write(source, output);

        byte[] xml = output.toByteArray();
        validateProfile(xml);
        Document document = parse(xml);
        assertEquals(0, document.getElementsByTagNameNS(NAMESPACE, "Position").getLength());
        assertEquals(0, document.getElementsByTagNameNS(NAMESPACE, "AltitudeMeters").getLength());
        assertEquals(0, document.getElementsByTagNameNS(NAMESPACE, "HeartRateBpm").getLength());
    }

    @Test
    public void supportsEmptyTrackWhenRequiredSummaryIsPresent() throws Exception {
        SyntheticSource source = completeSource();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        new TcxWriter().write(source, output);

        validateProfile(output.toByteArray());
        Document document = parse(output.toByteArray());
        assertEquals(0, document.getElementsByTagNameNS(NAMESPACE, "Track").getLength());
        assertEquals("90.5", text(document, "TotalTimeSeconds"));
    }

    @Test
    public void legacyAdapterMarksSchemaPlaceholdersWithoutChangingPresence() throws Exception {
        SportActivity activity = new SportActivity();
        activity.StartTimeUTC = new Date(START);
        activity.EndTimeUTC = new Date(START + 90_500L);
        activity.Time = 123_456D;
        activity.SportTrackPoints.add(point(START, 47.6205D, -122.3493D, 80D));
        activity.SportTrackPoints.add(point(START + 1_000L, 47.6206D, -122.3492D, 90D));
        LegacySportActivityTcxSource source =
                new LegacySportActivityTcxSource(activity);

        assertEquals(90.5D, source.getDurationSeconds(), 0D);
        assertTrue(source.getDistanceMeters() > 0D);
        assertNull(source.getCalories());
        assertNull(source.getIntensity());
        assertNull(source.getTriggerMethod());
        assertNull(source.getSport());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        TcxWriter writer = new TcxWriter();
        TcxWriter.Assessment assessment = writer.assess(source);
        assertTrue(assessment.hasFormatPlaceholders());
        assertTrue(assessment.getPlaceholderFields().contains("Calories"));
        assertTrue(assessment.getPlaceholderFields().contains("Intensity"));
        assertTrue(assessment.getPlaceholderFields().contains("TriggerMethod"));
        assertTrue(assessment.getPlaceholderFields().contains("Sport"));

        writer.write(source, output);

        byte[] xml = output.toByteArray();
        validateProfile(xml);
        Document document = parse(xml);
        assertEquals("0", text(document, "Calories"));
        assertTrue(text(document, "Notes").contains(
                "These placeholders are not measured workout values."));
        NodeList placeholders = document.getElementsByTagNameNS(
                SPORT_LOGGER_NAMESPACE,
                "Placeholder");
        assertEquals(4, placeholders.getLength());
    }

    @Test
    public void zeroZeroLegacyRouteUsesMarkedDistancePlaceholder() throws Exception {
        SportActivity activity = new SportActivity();
        activity.StartTimeUTC = new Date(START);
        activity.EndTimeUTC = new Date(START + 1_000L);
        activity.SportTrackPoints.add(point(START, 0D, 0D, 0D));
        LegacySportActivityTcxSource source =
                new LegacySportActivityTcxSource(activity);

        assertEquals(0, source.getRoutePointCount());
        assertNull(source.getDistanceMeters());
        TcxWriter writer = new TcxWriter();
        assertTrue(writer.assess(source)
                .getPlaceholderFields()
                .contains("DistanceMeters"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writer.write(source, output);
        validateProfile(output.toByteArray());
        Document document = parse(output.toByteArray());
        assertEquals("0", text(document, "DistanceMeters"));
        assertEquals(0, document.getElementsByTagNameNS(NAMESPACE, "Position").getLength());
    }

    @Test
    public void legacyElapsedDurationPreservesPauseGapWithoutInventingSamples()
            throws Exception {
        SportActivity activity = new SportActivity();
        activity.StartTimeUTC = new Date(START);
        activity.EndTimeUTC = new Date(START + 120_000L);
        activity.SportTrackPoints.add(point(START, 47.6205D, -122.3493D, 100D));
        activity.SportTrackPoints.add(point(
                START + 120_000L,
                47.6206D,
                -122.3492D,
                100D));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        new TcxWriter().write(
                new LegacySportActivityTcxSource(activity),
                output);

        Document document = parse(output.toByteArray());
        assertEquals("120", text(document, "TotalTimeSeconds"));
        NodeList times = document.getElementsByTagNameNS(NAMESPACE, "Time");
        assertEquals(2, times.getLength());
        assertEquals(
                "2024-01-01T00:02:00.123Z",
                times.item(1).getTextContent());
    }

    @Test
    public void rejectsOutOfOrderPointsWithoutClosingCallerStream() throws Exception {
        SyntheticSource source = completeSource();
        source.points = Arrays.asList(
                new Point(START + 1_000L, false, 0D, 0D, null, null, null),
                new Point(START, false, 0D, 0D, null, null, null));
        TrackingOutputStream output = new TrackingOutputStream();

        try {
            new TcxWriter().write(source, output);
            fail("Expected point ordering failure");
        } catch (TcxExportException error) {
            assertEquals(
                    TcxExportException.Code.OUT_OF_ORDER_TRACK_POINT,
                    error.getCode());
            assertFalse(output.closed);
        }
    }

    @Test
    public void cancellationStopsStreamingWithoutClosingCallerStream() throws Exception {
        SyntheticSource source = generatedSource(1_000);
        TrackingOutputStream output = new TrackingOutputStream();
        AtomicInteger checks = new AtomicInteger();

        try {
            new TcxWriter().write(
                    source,
                    output,
                    () -> checks.incrementAndGet() > 10);
            fail("Expected cancellation");
        } catch (TcxExportException error) {
            assertEquals(TcxExportException.Code.CANCELLED, error.getCode());
            assertFalse(output.closed);
        }
    }

    @Test
    public void streamsOneHundredThousandPoints() throws Exception {
        SyntheticSource source = generatedSource(100_000);
        CountingOutputStream output = new CountingOutputStream();

        new TcxWriter().write(source, output);

        assertFalse(output.closed);
        assertTrue(output.byteCount > 10_000_000L);
    }

    private static SyntheticSource completeSource() {
        SyntheticSource source = new SyntheticSource();
        source.startTimeUtcMillis = START;
        source.durationSeconds = 90.5D;
        source.distanceMeters = 12.345678D;
        source.calories = 0;
        source.intensity = TcxExportSource.Intensity.ACTIVE;
        source.triggerMethod = TcxExportSource.TriggerMethod.MANUAL;
        source.sport = TcxExportSource.Sport.OTHER;
        source.averageHeartRate = 85;
        source.maximumHeartRate = 90;
        source.points = new ArrayList<>();
        return source;
    }

    private static SyntheticSource generatedSource(int count) {
        SyntheticSource source = completeSource();
        source.generatedPointCount = count;
        source.distanceMeters = (double) count;
        source.points = null;
        return source;
    }

    private static SportTrackPoint point(
            long time,
            double latitude,
            double longitude,
            double heartRate) {
        SportTrackPoint point = new SportTrackPoint();
        point.TimeStampUTC = new Date(time);
        point.Latitude = latitude;
        point.Longitude = longitude;
        point.HeartRate = heartRate;
        return point;
    }

    private static void validateProfile(byte[] xml) throws Exception {
        SchemaFactory factory =
                SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        Schema schema = factory.newSchema(
                TcxWriterTest.class.getResource("/tcx-export-profile.xsd"));
        schema.newValidator().validate(
                new StreamSource(new ByteArrayInputStream(xml)));
    }

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl",
                true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static String text(Document document, String element) {
        return text(document, element, 0);
    }

    private static String text(Document document, String element, int index) {
        NodeList values = document.getElementsByTagNameNS(NAMESPACE, element);
        return values.item(index).getTextContent();
    }

    private static final class SyntheticSource implements TcxExportSource {
        private Long startTimeUtcMillis;
        private Double durationSeconds;
        private Double distanceMeters;
        private Integer calories;
        private Intensity intensity;
        private TriggerMethod triggerMethod;
        private Sport sport;
        private Integer averageHeartRate;
        private Integer maximumHeartRate;
        private String notes;
        private List<Point> points;
        private int generatedPointCount;

        @Override
        public Long getStartTimeUtcMillis() {
            return startTimeUtcMillis;
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
            return calories;
        }

        @Override
        public Intensity getIntensity() {
            return intensity;
        }

        @Override
        public TriggerMethod getTriggerMethod() {
            return triggerMethod;
        }

        @Override
        public Sport getSport() {
            return sport;
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
            return notes;
        }

        @Override
        public TrackPointCursor openTrackPoints() {
            return new SyntheticCursor(this);
        }
    }

    private static final class SyntheticCursor implements TcxExportSource.TrackPointCursor {
        private final SyntheticSource source;
        private int index = -1;

        private SyntheticCursor(SyntheticSource source) {
            this.source = source;
        }

        @Override
        public boolean moveNext() {
            index++;
            return source.points == null
                    ? index < source.generatedPointCount
                    : index < source.points.size();
        }

        @Override
        public Long getTimeUtcMillis() {
            return point().time;
        }

        @Override
        public boolean hasPosition() {
            return point().hasPosition;
        }

        @Override
        public double getLatitudeDegrees() {
            return point().latitude;
        }

        @Override
        public double getLongitudeDegrees() {
            return point().longitude;
        }

        @Override
        public Double getAltitudeMeters() {
            return point().altitude;
        }

        @Override
        public Double getDistanceMeters() {
            return point().distance;
        }

        @Override
        public Integer getHeartRateBpm() {
            return point().heartRate;
        }

        @Override
        public void close() {
        }

        private Point point() {
            if (source.points != null) {
                return source.points.get(index);
            }
            return new Point(
                    source.startTimeUtcMillis + index * 1_000L,
                    true,
                    47D + index * 0.000001D,
                    -122D,
                    null,
                    (double) index,
                    120);
        }
    }

    private static final class Point {
        private final long time;
        private final boolean hasPosition;
        private final double latitude;
        private final double longitude;
        private final Double altitude;
        private final Double distance;
        private final Integer heartRate;

        private Point(
                long time,
                boolean hasPosition,
                double latitude,
                double longitude,
                Double altitude,
                Double distance,
                Integer heartRate) {
            this.time = time;
            this.hasPosition = hasPosition;
            this.latitude = latitude;
            this.longitude = longitude;
            this.altitude = altitude;
            this.distance = distance;
            this.heartRate = heartRate;
        }
    }

    private static final class TrackingOutputStream extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean closed;

        @Override
        public void write(int value) {
            bytes.write(value);
        }

        @Override
        public void write(byte[] value, int offset, int length) {
            bytes.write(value, offset, length);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static final class CountingOutputStream extends OutputStream {
        private long byteCount;
        private boolean closed;

        @Override
        public void write(int value) {
            byteCount++;
        }

        @Override
        public void write(byte[] value, int offset, int length) {
            byteCount += length;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
