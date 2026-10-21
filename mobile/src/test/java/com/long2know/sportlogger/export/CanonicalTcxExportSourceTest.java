package com.long2know.sportlogger.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository;
import com.long2know.sportlogger.persistence.phone.PhoneDatabase;
import com.long2know.utilities.contract.CanonicalPayload;
import com.long2know.utilities.contract.CanonicalTransfers;
import com.long2know.utilities.contract.proto.ActivityRecord;
import com.long2know.utilities.contract.proto.ActivitySummary;
import com.long2know.utilities.contract.proto.ActivityType;
import com.long2know.utilities.contract.proto.AggregationSemantics;
import com.long2know.utilities.contract.proto.CapabilityState;
import com.long2know.utilities.contract.proto.DoubleMeasurement;
import com.long2know.utilities.contract.proto.Lap;
import com.long2know.utilities.contract.proto.LapIntensity;
import com.long2know.utilities.contract.proto.LapTrigger;
import com.long2know.utilities.contract.proto.LiveAvailability;
import com.long2know.utilities.contract.proto.MeasurementSample;
import com.long2know.utilities.contract.proto.MeasurementStatus;
import com.long2know.utilities.contract.proto.PermissionState;
import com.long2know.utilities.contract.proto.Position;
import com.long2know.utilities.contract.proto.PositionMeasurement;
import com.long2know.utilities.contract.proto.Quality;
import com.long2know.utilities.contract.proto.SourceKind;
import com.long2know.utilities.contract.proto.SourceMetadata;
import com.long2know.utilities.export.tcx.TcxExportSource;
import com.long2know.utilities.export.tcx.TcxWriter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.w3c.dom.Document;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 26)
public final class CanonicalTcxExportSourceTest {
    private static final String SESSION_ID = "9acfd923-cf13-58a8-a8a5-6eaa1128f7e7";
    private static final String TRANSFER_ID = "3cd7c9ed-c702-486d-b87a-681b218641c8";
    private static final long START = 1_700_000_000_000L;
    private static final String TCX_NAMESPACE =
            "http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2";
    private static final String SPORT_LOGGER_NAMESPACE =
            "urn:com.long2know.sportlogger:tcx:export:v1";

    private PhoneDatabase database;
    private PhoneActivityRepository repository;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        database = Room.inMemoryDatabaseBuilder(context, PhoneDatabase.class)
                .allowMainThreadQueries()
                .build();
        repository = new PhoneActivityRepository(database);
    }

    @After
    public void tearDown() {
        database.close();
    }

    @Test
    public void canonicalPresenceAndMetadataStreamToTcx() throws Exception {
        accept(activity(2));
        repository.setMetadata(
                SESSION_ID,
                "Phone title",
                "Phone notes",
                List.of("synthetic"),
                START + 10_000L);
        PhoneActivityRepository.ActivityDetail detail =
                repository.detail(SESSION_ID, 1L);
        CanonicalTcxExportSource source =
                new CanonicalTcxExportSource(repository, detail);
        TcxWriter writer = new TcxWriter();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertFalse(writer.assess(source).hasFormatPlaceholders());
        assertEquals(2, source.getFormatWarnings().size());
        writer.write(source, output);

        Document document = parse(output.toByteArray());
        assertEquals("Running", document
                .getElementsByTagNameNS(TCX_NAMESPACE, "Activity")
                .item(0)
                .getAttributes()
                .getNamedItem("Sport")
                .getNodeValue());
        assertEquals(
                "0",
                document.getElementsByTagNameNS(TCX_NAMESPACE, "LatitudeDegrees")
                        .item(0)
                        .getTextContent());
        assertEquals(
                "0",
                document.getElementsByTagNameNS(TCX_NAMESPACE, "LongitudeDegrees")
                        .item(0)
                        .getTextContent());
        assertEquals(
                "0",
                document.getElementsByTagNameNS(TCX_NAMESPACE, "AltitudeMeters")
                        .item(0)
                        .getTextContent());
        assertEquals(
                "123",
                document.getElementsByTagNameNS(TCX_NAMESPACE, "Calories")
                        .item(0)
                        .getTextContent());
        String notes = document.getElementsByTagNameNS(TCX_NAMESPACE, "Notes")
                .item(0)
                .getTextContent();
        assertTrue(notes.contains("Phone title"));
        assertTrue(notes.contains("Fractional calories were rounded"));
        assertEquals(
                0,
                document.getElementsByTagNameNS(
                        SPORT_LOGGER_NAMESPACE,
                        "Placeholder").getLength());
    }

    @Test
    public void cursorPagesAllCanonicalSamplesInOrder() throws Exception {
        accept(activity(2_500));
        CanonicalTcxExportSource source = new CanonicalTcxExportSource(
                repository,
                repository.detail(SESSION_ID, 1L));
        TcxExportSource.TrackPointCursor cursor = source.openTrackPoints();
        int count = 0;
        long previous = Long.MIN_VALUE;
        try {
            while (cursor.moveNext()) {
                long timestamp = cursor.getTimeUtcMillis();
                assertTrue(timestamp >= previous);
                previous = timestamp;
                count++;
            }
        } finally {
            cursor.close();
        }
        assertEquals(2_500, count);
    }

    @Test
    public void absentCanonicalFieldsRemainAbsentBeforeMarkedPlaceholders() throws Exception {
        ActivityRecord activity = ActivityRecord.newBuilder()
                .setSessionId(SESSION_ID)
                .setWatchRevision(1L)
                .setActivityType(ActivityType.ACTIVITY_TYPE_UNSPECIFIED)
                .setStartTimeUtcMs(START)
                .setEndTimeUtcMs(START + 60_000L)
                .setElapsedDurationMs(60_000L)
                .setSource(source())
                .build();
        accept(activity);
        CanonicalTcxExportSource source = new CanonicalTcxExportSource(
                repository,
                repository.detail(SESSION_ID, 1L));

        List<String> placeholders = new TcxWriter()
                .assess(source)
                .getPlaceholderFields();

        assertTrue(placeholders.contains("DistanceMeters"));
        assertTrue(placeholders.contains("Calories"));
        assertTrue(placeholders.contains("Intensity"));
        assertTrue(placeholders.contains("TriggerMethod"));
        assertTrue(placeholders.contains("Sport"));
    }

    @Test
    public void frozenMetadataReconstructsIdenticalArtifactAfterPhoneEdit() throws Exception {
        accept(activity(3));
        repository.setMetadata(
                SESSION_ID,
                "Frozen title",
                "Frozen notes",
                List.of("easy", "synthetic"),
                START + 10_000L);
        CanonicalTcxExportSource.MetadataSnapshot frozen =
                CanonicalTcxExportSource.MetadataSnapshot.from(
                        repository.metadata(SESSION_ID));
        byte[] first = write(CanonicalTcxExportSource.fromRevision(
                repository,
                SESSION_ID,
                1L,
                frozen));

        repository.setMetadata(
                SESSION_ID,
                "Later title",
                "Later notes",
                List.of("changed"),
                START + 20_000L);
        byte[] reconstructed = write(CanonicalTcxExportSource.fromRevision(
                repository,
                SESSION_ID,
                1L,
                frozen));
        byte[] current = write(CanonicalTcxExportSource.fromCurrentMetadata(
                repository,
                SESSION_ID,
                1L));

        assertArrayEquals(first, reconstructed);
        assertFalse(java.util.Arrays.equals(first, current));
    }

    private void accept(ActivityRecord activity) {
        repository.acceptActivityTransfer(
                CanonicalTransfers.activityTransfer(
                                TRANSFER_ID,
                                CanonicalPayload.create(activity))
                        .toByteArray(),
                START + 120_000L);
    }

    private static byte[] write(CanonicalTcxExportSource source) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new TcxWriter().write(source, output);
        return output.toByteArray();
    }

    private static ActivityRecord activity(int sampleCount) {
        ActivitySummary summary = ActivitySummary.newBuilder()
                .setDistanceM(measurement(1_000.25D, "DISTANCE_TOTAL", "metres"))
                .setCaloriesKcal(measurement(123.4D, "CALORIES_TOTAL", "kilocalories"))
                .setAverageHeartRateBpm(
                        measurement(100.4D, "HEART_RATE_AVERAGE", "beats/minute"))
                .setMaximumHeartRateBpm(
                        measurement(155D, "HEART_RATE_MAXIMUM", "beats/minute"))
                .setSampleCount(sampleCount)
                .setRoutePointCount(sampleCount)
                .setHeartRateSampleCount(sampleCount)
                .build();
        Lap lap = Lap.newBuilder()
                .setLapId("lap-1")
                .setIndex(0)
                .setStartTimeUtcMs(START)
                .setEndTimeUtcMs(START + 60_000L)
                .setElapsedDurationMs(60_000L)
                .setActiveDurationMs(55_000L)
                .setSummary(summary)
                .setIntensity(LapIntensity.LAP_INTENSITY_ACTIVE)
                .setTrigger(LapTrigger.LAP_TRIGGER_MANUAL)
                .build();
        ActivityRecord.Builder activity = ActivityRecord.newBuilder()
                .setSessionId(SESSION_ID)
                .setWatchRevision(1L)
                .setActivityType(ActivityType.ACTIVITY_TYPE_RUNNING)
                .setStartTimeUtcMs(START)
                .setEndTimeUtcMs(START + 60_000L)
                .setElapsedDurationMs(60_000L)
                .setActiveDurationMs(55_000L)
                .setSummary(summary)
                .addLaps(lap)
                .setSource(source())
                .setRecordedName("Recorded run")
                .setRecordedDescription("Clearly synthetic canonical fixture");
        for (int index = 0; index < sampleCount; index++) {
            activity.addSamples(sample(index));
        }
        return activity.build();
    }

    private static MeasurementSample sample(int index) {
        Position position = Position.newBuilder()
                .setLatitudeDegrees(index == 0 ? 0D : 47D + index * 0.000001D)
                .setLongitudeDegrees(index == 0 ? 0D : -122D)
                .setAltitudeM(index == 0 ? 0D : 10D)
                .build();
        return MeasurementSample.newBuilder()
                .setSequence(index)
                .setTimestampUtcMs(START + index * 1_000L)
                .setLocation(PositionMeasurement.newBuilder()
                        .setValue(position)
                        .setStatus(status("LOCATION", "degrees")))
                .setHeartRateBpm(
                        measurement(100.4D, "HEART_RATE_BPM", "beats/minute"))
                .setDistanceTotalM(
                        measurement(index, "DISTANCE_TOTAL", "metres"))
                .build();
    }

    private static DoubleMeasurement measurement(
            double value,
            String identifier,
            String unit) {
        return DoubleMeasurement.newBuilder()
                .setValue(value)
                .setStatus(status(identifier, unit))
                .build();
    }

    private static MeasurementStatus status(String identifier, String unit) {
        return MeasurementStatus.newBuilder()
                .setMetricIdentifier(identifier)
                .setPublicUnit(unit)
                .setAggregation(AggregationSemantics.AGGREGATION_SEMANTICS_CUMULATIVE)
                .setExerciseCapability(CapabilityState.CAPABILITY_STATE_SUPPORTED)
                .setRuntimePermission(PermissionState.PERMISSION_STATE_GRANTED)
                .setRawHardware(CapabilityState.CAPABILITY_STATE_SUPPORTED)
                .setVendorCapability(CapabilityState.CAPABILITY_STATE_UNKNOWN)
                .setLiveAvailability(LiveAvailability.LIVE_AVAILABILITY_AVAILABLE)
                .setSource(source())
                .setQuality(Quality.QUALITY_VALID)
                .build();
    }

    private static SourceMetadata source() {
        return SourceMetadata.newBuilder()
                .setKind(SourceKind.SOURCE_KIND_HEALTH_SERVICES)
                .setProvider("synthetic-test")
                .setAppContractVersion(1)
                .build();
    }

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl",
                true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }
}
