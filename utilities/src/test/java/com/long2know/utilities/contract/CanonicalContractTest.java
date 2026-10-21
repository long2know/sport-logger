package com.long2know.utilities.contract;

import com.long2know.utilities.contract.proto.ActivityRecord;
import com.long2know.utilities.contract.proto.ActivityType;
import com.long2know.utilities.contract.proto.AggregationSemantics;
import com.long2know.utilities.contract.proto.CapabilityState;
import com.long2know.utilities.contract.proto.LiveAvailability;
import com.long2know.utilities.contract.proto.MeasurementSample;
import com.long2know.utilities.contract.proto.MeasurementStatus;
import com.long2know.utilities.contract.proto.PermissionState;
import com.long2know.utilities.contract.proto.Position;
import com.long2know.utilities.contract.proto.PositionMeasurement;
import com.long2know.utilities.contract.proto.Quality;
import com.long2know.utilities.contract.proto.SourceKind;
import com.long2know.utilities.contract.proto.SourceMetadata;

import org.junit.Test;
import sportlogger.compatibility.FutureActivityFile;
import sportlogger.compatibility.OldActivityFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class CanonicalContractTest {
    private static final String SESSION_ID = "3a4c9e29-e2f0-5ba2-b3d6-9471d1846556";

    @Test
    public void oldWriterParsesWithNewReader() throws Exception {
        byte[] bytes = fixture("old-writer.bin");

        CanonicalPayload payload = CanonicalPayload.parse(bytes);

        assertEquals(SESSION_ID, payload.getActivity().getSessionId());
        assertEquals(7L, payload.getActivity().getWatchRevision());
        assertEquals(ActivityType.ACTIVITY_TYPE_RUNNING, payload.getActivity().getActivityType());
    }

    @Test
    public void newWriterUsingOldFieldsParsesWithOldReader() throws Exception {
        OldActivityFile old =
                OldActivityFile.parseFrom(fixture("new-writer-old-fields.bin"));

        assertEquals(SESSION_ID, old.getActivity().getSessionId());
        assertEquals(7L, old.getActivity().getWatchRevision());
        assertEquals(ActivityType.ACTIVITY_TYPE_RUNNING_VALUE, old.getActivity().getActivityType());
    }

    @Test
    public void futureUnknownFieldSurvivesOldParseAndBinaryReserialize() throws Exception {
        CanonicalPayload parsed = CanonicalPayload.parse(fixture("future-writer.bin"));
        FutureActivityFile reparsed =
                FutureActivityFile.parseFrom(parsed.reserializePreservingUnknownFields());

        assertEquals("preserve me", reparsed.getFutureNote());
        assertEquals(SESSION_ID, reparsed.getActivity().getSessionId());
    }

    @Test
    public void exactBytesAndHashRemainStableWhileUnknownSemanticFieldsSurvive()
            throws Exception {
        byte[] bytes = fixture("future-writer.bin");

        CanonicalPayload payload = CanonicalPayload.parse(bytes);

        assertArrayEquals(bytes, payload.getExactBytes());
        assertEquals(64, payload.sha256Hex().length());
        assertEquals(
                "preserve me",
                FutureActivityFile.parseFrom(payload.reserializePreservingUnknownFields())
                        .getFutureNote());
    }

    @Test
    public void zeroCoordinatesArePresentWhileOptionalPositionValuesRemainAbsent() {
        MeasurementSample sample = MeasurementSample.newBuilder()
                .setSequence(1L)
                .setTimestampUtcMs(1_500L)
                .setLocation(PositionMeasurement.newBuilder()
                        .setValue(Position.newBuilder()
                                .setLatitudeDegrees(0D)
                                .setLongitudeDegrees(0D))
                        .setStatus(status("LOCATION", "degrees")))
                .build();
        CanonicalPayload payload = CanonicalPayload.create(
                baseActivity().addSamples(sample).build());

        Position position = payload.getActivity().getSamples(0).getLocation().getValue();
        assertEquals(0D, position.getLatitudeDegrees(), 0D);
        assertEquals(0D, position.getLongitudeDegrees(), 0D);
        assertFalse(position.hasAltitudeM());
        assertFalse(position.hasAccuracyM());
        assertFalse(position.hasBearingDegrees());
    }

    @Test
    public void deterministicLegacyIdsAreStableAndSourceScoped() {
        String first = CanonicalIds.deterministicLegacySessionId("watch-db-a", 42L);
        String repeat = CanonicalIds.deterministicLegacySessionId("watch-db-a", 42L);
        String other = CanonicalIds.deterministicLegacySessionId("watch-db-b", 42L);
        String transfer = CanonicalIds.deterministicLegacyTransferId(
                "watch-db-a",
                42L,
                1L);

        assertEquals(first, repeat);
        assertNotEquals(first, other);
        assertEquals(5, java.util.UUID.fromString(first).version());
        assertEquals(5, java.util.UUID.fromString(transfer).version());
    }

    @Test
    public void transferValidatesExactLengthHashAndIdentity() {
        CanonicalPayload payload = CanonicalPayload.create(baseActivity().build());
        String transferId = "c9eca2d7-5eca-4cce-b962-14ee13c6110d";
        byte[] bytes = CanonicalTransfers.activityTransfer(transferId, payload).toByteArray();

        CanonicalTransfers.VerifiedActivityTransfer verified =
                CanonicalTransfers.parseActivityTransfer(bytes);

        assertArrayEquals(bytes, verified.getExactBytes());
        assertEquals(transferId, verified.getTransfer().getTransferId());
        assertEquals(SESSION_ID, verified.getPayload().getActivity().getSessionId());
    }

    @Test
    public void reservedFieldDisciplineIsCheckedInSchemaSource() throws Exception {
        Path schema = Paths.get("src/main/proto/sportlogger/core/v1/activity.proto");
        String source = new String(Files.readAllBytes(schema), StandardCharsets.UTF_8);

        assertTrue(source.contains("reserved 3 to 9;"));
        assertTrue(source.contains("reserved \"json_payload\", \"serialized_activity\";"));
        assertTrue(source.contains("reserved \"pace\", \"phone_title\", \"phone_notes\";"));
        assertTrue(source.contains(
                "reserved \"oxygen_saturation_percent\", \"skin_temperature_celsius\";"));
    }

    @Test
    public void checkedInBinaryFixtureHashesArePinned() throws Exception {
        assertEquals(
                "b075616d48688ab87aa0a1ca4a8f8f1527b7dd4c95944a571cda90ccd1040230",
                CanonicalPayload.hex(
                        java.security.MessageDigest.getInstance("SHA-256")
                                .digest(fixture("old-writer.bin"))));
        assertEquals(
                "b075616d48688ab87aa0a1ca4a8f8f1527b7dd4c95944a571cda90ccd1040230",
                CanonicalPayload.hex(
                        java.security.MessageDigest.getInstance("SHA-256")
                                .digest(fixture("new-writer-old-fields.bin"))));
        assertEquals(
                "fd660c4cf591b9ff32cf4d74ede99ee969d8f2c249993ee21d48c0f979dae2fd",
                CanonicalPayload.hex(
                        java.security.MessageDigest.getInstance("SHA-256")
                                .digest(fixture("future-writer.bin"))));
    }

    @Test
    public void boundedMetricCatalogBuildsExactPublicIdentityAndUnit() {
        MeasurementStatus status = CanonicalMeasurements.status(
                CanonicalMeasurements.Metric.PACE,
                CanonicalMeasurements.Dimensions.builder(
                                SourceMetadata.newBuilder()
                                        .setKind(SourceKind.SOURCE_KIND_HEALTH_SERVICES)
                                        .setAppContractVersion(1)
                                        .build())
                        .liveAvailability(LiveAvailability.LIVE_AVAILABILITY_ACQUIRING)
                        .quality(Quality.QUALITY_ESTIMATED)
                        .build());

        assertEquals("PACE", status.getMetricIdentifier());
        assertEquals("milliseconds/kilometre", status.getPublicUnit());
        assertEquals(
                AggregationSemantics.AGGREGATION_SEMANTICS_INSTANTANEOUS,
                status.getAggregation());
        assertEquals(
                LiveAvailability.LIVE_AVAILABILITY_ACQUIRING,
                status.getLiveAvailability());
    }

    private static ActivityRecord.Builder baseActivity() {
        return ActivityRecord.newBuilder()
                .setSessionId(SESSION_ID)
                .setWatchRevision(7L)
                .setActivityType(ActivityType.ACTIVITY_TYPE_RUNNING)
                .setStartTimeUtcMs(1_000L)
                .setEndTimeUtcMs(2_000L)
                .setElapsedDurationMs(1_000L)
                .setSource(SourceMetadata.newBuilder()
                        .setKind(SourceKind.SOURCE_KIND_RAW_PLATFORM)
                        .setAppContractVersion(1));
    }

    private static MeasurementStatus status(String identifier, String unit) {
        return MeasurementStatus.newBuilder()
                .setMetricIdentifier(identifier)
                .setPublicUnit(unit)
                .setAggregation(
                        AggregationSemantics.AGGREGATION_SEMANTICS_INSTANTANEOUS)
                .setExerciseCapability(CapabilityState.CAPABILITY_STATE_UNKNOWN)
                .setRuntimePermission(PermissionState.PERMISSION_STATE_UNKNOWN)
                .setRawHardware(CapabilityState.CAPABILITY_STATE_UNKNOWN)
                .setVendorCapability(CapabilityState.CAPABILITY_STATE_UNKNOWN)
                .setLiveAvailability(LiveAvailability.LIVE_AVAILABILITY_AVAILABLE)
                .setSource(SourceMetadata.newBuilder()
                        .setKind(SourceKind.SOURCE_KIND_RAW_PLATFORM)
                        .setAppContractVersion(1))
                .setQuality(Quality.QUALITY_VALID)
                .build();
    }

    private static byte[] fixture(String name) throws Exception {
        return Files.readAllBytes(
                Paths.get("src/test/resources/protobuf/core-v1", name));
    }
}
