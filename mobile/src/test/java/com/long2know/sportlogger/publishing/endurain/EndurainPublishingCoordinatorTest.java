package com.long2know.sportlogger.publishing.endurain;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.long2know.sportlogger.connector.endurain.EndurainConnectionConfig;
import com.long2know.sportlogger.connector.endurain.TcxUploadArtifact;
import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository;
import com.long2know.sportlogger.persistence.phone.PhoneDatabase;
import com.long2know.sportlogger.publishing.endurain.artifact.PreparedTcxArtifactStore;
import com.long2know.sportlogger.publishing.endurain.artifact.PreparedTcxArtifactStore.ArtifactManifest;
import com.long2know.sportlogger.publishing.endurain.factory.EndurainActionResult;
import com.long2know.sportlogger.publishing.endurain.factory.EndurainDeliveryStatus;
import com.long2know.sportlogger.publishing.endurain.factory.EndurainPublishingSnapshot;
import com.long2know.sportlogger.publishing.endurain.profile.EndurainConnectionProfile;
import com.long2know.sportlogger.publishing.endurain.profile.EndurainConnectionProfileStore;
import com.long2know.sportlogger.publishing.endurain.scheduler.EndurainPublishingSchedule;
import com.long2know.sportlogger.security.CredentialStoreException;
import com.long2know.sportlogger.security.EndurainApiKey;
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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 26)
public final class EndurainPublishingCoordinatorTest {
    private static final String SESSION_ID =
            "f37dca54-d36f-4de2-b62e-b36e8d3f32b2";
    private static final long START = 1_700_000_000_000L;

    private Context context;
    private PhoneDatabase database;
    private PhoneActivityRepository repository;
    private EndurainConnectionProfileStore profiles;
    private PreparedTcxArtifactStore artifacts;
    private FakeCredentialGateway credentials;
    private FakeTransport transport;
    private FakeSchedule schedule;
    private FakeChangeSignal changes;
    private AtomicLong clock;
    private AtomicLong leases;
    private EndurainPublishingCoordinator coordinator;
    private EndurainConnectionProfile profile;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        assertTrue(context.getSharedPreferences(
                        "endurain_connection_profiles_v1",
                        Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit());
        deleteRecursively(new File(
                context.getNoBackupFilesDir(),
                "endurain-publishing-v1"));
        database = Room.inMemoryDatabaseBuilder(
                        context,
                        PhoneDatabase.class)
                .allowMainThreadQueries()
                .build();
        repository = new PhoneActivityRepository(database);
        profiles = EndurainConnectionProfileStore.forApplication(context);
        artifacts = PreparedTcxArtifactStore.forApplication(context);
        credentials = new FakeCredentialGateway();
        transport = new FakeTransport();
        schedule = new FakeSchedule();
        changes = new FakeChangeSignal();
        clock = new AtomicLong(START + 120_000L);
        leases = new AtomicLong(10L);
        coordinator = new EndurainPublishingCoordinator(
                repository,
                profiles,
                artifacts,
                credentials,
                transport,
                schedule,
                changes,
                clock::get,
                leases::incrementAndGet);
        acceptActivity(1L);
        repository.setMetadata(
                SESSION_ID,
                "Frozen title",
                "Frozen notes",
                List.of("fixture"),
                clock.get());
        profile = coordinator.saveConnection(
                null,
                "Fixture Endurain",
                "https://endurain.example/base",
                true);
    }

    @After
    public void tearDown() {
        database.close();
        deleteRecursively(new File(
                context.getNoBackupFilesDir(),
                "endurain-publishing-v1"));
        context.getSharedPreferences(
                        "endurain_connection_profiles_v1",
                        Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
    }

    @Test
    public void realCanonicalTcxIsDurableBeforeIoAndDeliveredOnce()
            throws Exception {
        char[] entered = "fixture-api-key".toCharArray();
        coordinator.saveCredential(
                profile.getConnectionIdentity(),
                entered);
        assertArrayEquals(new char[entered.length], entered);
        transport.results.add(
                EndurainTransportGateway.UploadOutcome.delivered(
                        List.of(71L, 72L)));

        EndurainActionResult queued = coordinator.queuePublish(
                profile.getConnectionIdentity(),
                SESSION_ID,
                1L);

        assertEquals(EndurainActionResult.Code.QUEUED, queued.getCode());
        assertEquals(0, transport.callCount);
        PhoneActivityRepository.ConnectorDelivery pending =
                repository.connectorDelivery(
                        queued.getDeliveryKey().toRepositoryKey());
        assertEquals(
                PhoneActivityRepository.ConnectorState.PENDING,
                pending.getState());
        assertTrue(pending.getArtifactLength() > 0L);
        ArtifactManifest manifest = artifacts.listManifests().get(0);
        assertTrue(artifacts.verify(manifest).isReady());
        byte[] original = Files.readAllBytes(
                artifacts.verify(manifest)
                        .getUploadArtifact()
                        .file()
                        .toPath());

        repository.setMetadata(
                SESSION_ID,
                "Later title",
                "Later notes",
                List.of("changed"),
                clock.incrementAndGet());
        EndurainPublishingCoordinator restarted =
                new EndurainPublishingCoordinator(
                        repository,
                        EndurainConnectionProfileStore.forApplication(context),
                        PreparedTcxArtifactStore.forApplication(context),
                        credentials,
                        transport,
                        schedule,
                        changes,
                        clock::get,
                        leases::incrementAndGet);
        restarted.runDue(true, 25);

        assertEquals(1, transport.callCount);
        assertArrayEquals(
                original,
                Files.readAllBytes(
                        artifacts.verify(manifest)
                                .getUploadArtifact()
                                .file()
                                .toPath()));
        PhoneActivityRepository.ConnectorDelivery delivered =
                repository.connectorDelivery(
                        queued.getDeliveryKey().toRepositoryKey());
        assertEquals(
                PhoneActivityRepository.ConnectorState.DELIVERED,
                delivered.getState());
        assertEquals(List.of(71L, 72L), delivered.getRemoteIds());

        restarted.runDue(true, 25);
        assertEquals(1, transport.callCount);
        EndurainDeliveryStatus status =
                restarted.snapshot().getDeliveries().get(0);
        assertEquals(
                EndurainDeliveryStatus.State.UPLOADED,
                status.getState());
        assertEquals(List.of(71L, 72L), status.getRemoteIds());
    }

    @Test
    public void expiredClaimBecomesUnknownWithoutBlindRetry()
            throws Exception {
        coordinator.saveCredential(
                profile.getConnectionIdentity(),
                "fixture-api-key".toCharArray());
        EndurainDeliveryKey key = coordinator.queuePublish(
                        profile.getConnectionIdentity(),
                        SESSION_ID,
                        1L)
                .getDeliveryKey();
        long leaseToken = 900L;
        assertTrue(repository.claimConnectorDelivery(
                key.toRepositoryKey(),
                PhoneActivityRepository.ConnectorState.PENDING,
                leaseToken,
                "crashed-process",
                clock.get() + 1_000L,
                clock.get()));
        clock.addAndGet(2_000L);
        transport.results.add(
                EndurainTransportGateway.UploadOutcome.delivered(
                        List.of(81L)));

        coordinator.runDue(true, 25);

        assertEquals(0, transport.callCount);
        PhoneActivityRepository.ConnectorDelivery unknown =
                repository.connectorDelivery(key.toRepositoryKey());
        assertEquals(
                PhoneActivityRepository.ConnectorState.UNKNOWN_DELIVERY,
                unknown.getState());
        assertEquals(
                "PROCESS_RESTART_AMBIGUOUS",
                unknown.getLastFailureCode());
        EndurainDeliveryStatus status =
                coordinator.snapshot().getDeliveries().get(0);
        assertTrue(status.requiresReconciliation());
        assertTrue(status.canConfirmDuplicateRiskRetry());

        coordinator.reconcileRemoteIds(key, List.of(81L));
        assertEquals(
                PhoneActivityRepository.ConnectorState.DELIVERED,
                repository.connectorDelivery(key.toRepositoryKey())
                        .getState());
        assertEquals(
                EndurainCredentialGateway.State.NOT_CONFIGURED,
                coordinator.resetCredential(
                        profile.getConnectionIdentity()));
        assertEquals(
                EndurainCredentialGateway.State.NOT_CONFIGURED,
                coordinator.snapshot()
                        .getConnections()
                        .stream()
                        .filter(connection -> connection.getProfile()
                                .getConnectionIdentity()
                                .equals(profile.getConnectionIdentity()))
                        .findFirst()
                        .orElseThrow()
                        .getCredentialState());
        assertEquals(
                List.of(81L),
                repository.connectorDelivery(key.toRepositoryKey())
                        .getRemoteIds());
        assertEquals(0, transport.callCount);
    }

    @Test
    public void credentialAndArtifactFailuresRequireTypedRecovery()
            throws Exception {
        EndurainDeliveryKey key = coordinator.queuePublish(
                        profile.getConnectionIdentity(),
                        SESSION_ID,
                        1L)
                .getDeliveryKey();

        coordinator.runDue(true, 25);

        PhoneActivityRepository.ConnectorDelivery credentialFailure =
                repository.connectorDelivery(key.toRepositoryKey());
        assertEquals(
                PhoneActivityRepository.ConnectorState.FAILED,
                credentialFailure.getState());
        assertEquals(
                "CREDENTIAL_NOT_CONFIGURED",
                credentialFailure.getLastFailureCode());
        assertEquals(0, transport.callCount);

        EndurainConnectionProfile other = coordinator.saveConnection(
                null,
                "Other Endurain",
                "https://other.example",
                true);
        coordinator.saveCredential(
                other.getConnectionIdentity(),
                "other-fixture-key".toCharArray());
        assertEquals(
                EndurainActionResult.Code.CREDENTIAL_REQUIRED,
                coordinator.manualRetry(key).getCode());

        coordinator.saveCredential(
                profile.getConnectionIdentity(),
                "fixture-api-key".toCharArray());
        assertEquals(
                EndurainActionResult.Code.QUEUED,
                coordinator.manualRetry(key).getCode());
        ArtifactManifest manifest = artifacts.listManifests().get(0);
        File artifactFile =
                artifacts.verify(manifest).getUploadArtifact().file();
        assertTrue(artifactFile.delete());

        coordinator.runDue(true, 25);

        EndurainDeliveryStatus missing =
                coordinator.snapshot().getDeliveries().get(0);
        assertEquals(
                EndurainDeliveryStatus.ArtifactState.MISSING,
                missing.getArtifactState());
        assertTrue(missing.canRepairArtifact());
        assertEquals(
                EndurainActionResult.Code.COMPLETED,
                coordinator.repairPreparedArtifact(key).getCode());
        assertEquals(
                EndurainActionResult.Code.QUEUED,
                coordinator.manualRetry(key).getCode());

        artifactFile = artifacts.verify(manifest)
                .getUploadArtifact()
                .file();
        byte[] corrupted = Files.readAllBytes(artifactFile.toPath());
        corrupted[0] ^= 0x01;
        try (FileOutputStream output =
                     new FileOutputStream(artifactFile, false)) {
            output.write(corrupted);
            output.getFD().sync();
        }
        coordinator.runDue(true, 25);
        assertEquals(
                EndurainDeliveryStatus.ArtifactState.SHA256_MISMATCH,
                coordinator.snapshot()
                        .getDeliveries()
                        .get(0)
                        .getArtifactState());

        coordinator.repairPreparedArtifact(key);
        coordinator.manualRetry(key);
        transport.results.add(
                EndurainTransportGateway.UploadOutcome.delivered(
                        List.of(91L)));
        coordinator.runDue(true, 25);
        assertEquals(1, transport.callCount);
        assertEquals(
                PhoneActivityRepository.ConnectorState.DELIVERED,
                repository.connectorDelivery(key.toRepositoryKey())
                        .getState());
    }

    @Test
    public void retryAfterLateRateLimitAndDuplicateRiskStayExplicit()
            throws Exception {
        coordinator.saveCredential(
                profile.getConnectionIdentity(),
                "fixture-api-key".toCharArray());
        EndurainDeliveryKey key = coordinator.queuePublish(
                        profile.getConnectionIdentity(),
                        SESSION_ID,
                        1L)
                .getDeliveryKey();
        transport.results.add(
                EndurainTransportGateway.UploadOutcome.failure(
                        EndurainTransportGateway.UploadOutcome.State.RETRYABLE,
                        "PRE_TRANSMISSION_TIMEOUT",
                        null));
        coordinator.runDue(true, 25);
        long preTransmissionRetryAt = clock.get() + 60_000L;
        assertEquals(
                Long.valueOf(preTransmissionRetryAt),
                repository.connectorDelivery(key.toRepositoryKey())
                        .getRetryNotBeforeUtcMs());
        coordinator.runDue(true, 25);
        assertEquals(1, transport.callCount);
        clock.set(preTransmissionRetryAt);
        transport.results.add(
                EndurainTransportGateway.UploadOutcome.rateLimited(
                        java.time.Duration.ofMinutes(5),
                        429));
        coordinator.runDue(true, 25);
        long retryAt = clock.get() + 5L * 60L * 1_000L;
        assertEquals(
                Long.valueOf(retryAt),
                repository.connectorDelivery(key.toRepositoryKey())
                        .getRetryNotBeforeUtcMs());

        coordinator.runDue(true, 25);
        assertEquals(2, transport.callCount);
        clock.set(retryAt);
        transport.results.add(
                EndurainTransportGateway.UploadOutcome.unknownRateLimited(
                        java.time.Duration.ofMinutes(10),
                        429));
        coordinator.runDue(true, 25);
        assertEquals(
                PhoneActivityRepository.ConnectorState.UNKNOWN_DELIVERY,
                repository.connectorDelivery(key.toRepositoryKey())
                        .getState());
        clock.addAndGet(20L * 60L * 1_000L);
        coordinator.runDue(true, 25);
        assertEquals(3, transport.callCount);

        assertEquals(
                EndurainActionResult.Code.QUEUED,
                coordinator.confirmDuplicateRiskRetry(key).getCode());
        transport.results.add(
                EndurainTransportGateway.UploadOutcome.delivered(
                        List.of(101L)));
        coordinator.runDue(true, 25);
        repository.reconcileRemoteIds(
                key.toRepositoryKey(),
                List.of(101L, 102L),
                clock.get());
        assertEquals(
                PhoneActivityRepository.ConnectorState.DUPLICATE_REVIEW,
                repository.connectorDelivery(key.toRepositoryKey())
                        .getState());
        assertEquals(
                EndurainActionResult.Code.QUEUED,
                coordinator.confirmDuplicateRiskRetry(key).getCode());
        transport.results.add(
                EndurainTransportGateway.UploadOutcome.delivered(
                        List.of(103L)));
        coordinator.runDue(true, 25);
        PhoneActivityRepository.ConnectorDelivery duplicate =
                repository.connectorDelivery(key.toRepositoryKey());
        assertEquals(
                PhoneActivityRepository.ConnectorState.DUPLICATE_REVIEW,
                duplicate.getState());
        assertEquals(List.of(101L, 102L, 103L), duplicate.getRemoteIds());
    }

    @Test
    public void disableAndUnsupportedMutationsPreserveRemoteOwnership()
            throws Exception {
        coordinator.saveCredential(
                profile.getConnectionIdentity(),
                "fixture-api-key".toCharArray());
        coordinator.setConnectionEnabled(
                profile.getConnectionIdentity(),
                false);
        EndurainDeliveryKey key = coordinator.queuePublish(
                        profile.getConnectionIdentity(),
                        SESSION_ID,
                        1L)
                .getDeliveryKey();
        transport.results.add(
                EndurainTransportGateway.UploadOutcome.delivered(
                        List.of(111L)));

        coordinator.runDue(true, 25);
        assertEquals(0, transport.callCount);
        assertEquals(
                PhoneActivityRepository.ConnectorState.PENDING,
                repository.connectorDelivery(key.toRepositoryKey())
                        .getState());

        coordinator.setConnectionEnabled(
                profile.getConnectionIdentity(),
                true);
        coordinator.runDue(true, 25);
        assertEquals(1, transport.callCount);
        assertEquals(
                EndurainActionResult.Code.UNSUPPORTED_REMOTE_EDIT,
                coordinator.remoteEditUnsupported(key).getCode());
        assertEquals(
                EndurainActionResult.Code.UNSUPPORTED_REMOTE_DELETE,
                coordinator.remoteDeleteUnsupported(key).getCode());
        PhoneActivityRepository.ConnectorDelivery retained =
                repository.connectorDelivery(key.toRepositoryKey());
        assertEquals(List.of(111L), retained.getRemoteIds());
        assertTrue(retained.isDeleteUnsupported());
    }

    private void acceptActivity(long revision) {
        ActivitySummary summary = ActivitySummary.newBuilder()
                .setDistanceM(measurement(
                        1_000D,
                        "DISTANCE_TOTAL",
                        "metres"))
                .setCaloriesKcal(measurement(
                        100D,
                        "CALORIES_TOTAL",
                        "kilocalories"))
                .setAverageHeartRateBpm(measurement(
                        120D,
                        "HEART_RATE_AVERAGE",
                        "beats/minute"))
                .setMaximumHeartRateBpm(measurement(
                        150D,
                        "HEART_RATE_MAXIMUM",
                        "beats/minute"))
                .setSampleCount(1)
                .setRoutePointCount(1)
                .setHeartRateSampleCount(1)
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
        Position position = Position.newBuilder()
                .setLatitudeDegrees(0D)
                .setLongitudeDegrees(0D)
                .setAltitudeM(0D)
                .build();
        MeasurementSample sample = MeasurementSample.newBuilder()
                .setSequence(0)
                .setTimestampUtcMs(START)
                .setLocation(PositionMeasurement.newBuilder()
                        .setValue(position)
                        .setStatus(status("LOCATION", "degrees")))
                .setHeartRateBpm(measurement(
                        120D,
                        "HEART_RATE_BPM",
                        "beats/minute"))
                .setDistanceTotalM(measurement(
                        0D,
                        "DISTANCE_TOTAL",
                        "metres"))
                .build();
        ActivityRecord activity = ActivityRecord.newBuilder()
                .setSessionId(SESSION_ID)
                .setWatchRevision(revision)
                .setActivityType(ActivityType.ACTIVITY_TYPE_RUNNING)
                .setStartTimeUtcMs(START)
                .setEndTimeUtcMs(START + 60_000L)
                .setElapsedDurationMs(60_000L)
                .setActiveDurationMs(55_000L)
                .setSummary(summary)
                .addLaps(lap)
                .addSamples(sample)
                .setSource(source())
                .setRecordedName("Fixture run")
                .setRecordedDescription("Synthetic test activity")
                .build();
        repository.acceptActivityTransfer(
                CanonicalTransfers.activityTransfer(
                                UUID.randomUUID().toString(),
                                CanonicalPayload.create(activity))
                        .toByteArray(),
                clock.get());
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

    private static MeasurementStatus status(
            String identifier,
            String unit) {
        return MeasurementStatus.newBuilder()
                .setMetricIdentifier(identifier)
                .setPublicUnit(unit)
                .setAggregation(
                        AggregationSemantics
                                .AGGREGATION_SEMANTICS_CUMULATIVE)
                .setExerciseCapability(
                        CapabilityState.CAPABILITY_STATE_SUPPORTED)
                .setRuntimePermission(
                        PermissionState.PERMISSION_STATE_GRANTED)
                .setRawHardware(
                        CapabilityState.CAPABILITY_STATE_SUPPORTED)
                .setVendorCapability(
                        CapabilityState.CAPABILITY_STATE_UNKNOWN)
                .setLiveAvailability(
                        LiveAvailability.LIVE_AVAILABILITY_AVAILABLE)
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

    private static void deleteRecursively(File file) {
        if (!file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        assertTrue(file.delete());
    }

    private static final class FakeCredentialGateway
            implements EndurainCredentialGateway {
        private final Map<String, State> states = new HashMap<>();

        @Override
        public void store(
                EndurainConnectionProfile profile,
                char[] apiKey) throws CredentialStoreException {
            assertTrue(apiKey.length > 0);
            states.put(scope(profile), State.AVAILABLE);
        }

        @Override
        public Access load(EndurainConnectionProfile profile) {
            State state = states.getOrDefault(
                    scope(profile),
                    State.NOT_CONFIGURED);
            if (state != State.AVAILABLE) {
                return Access.unavailable(state);
            }
            return Access.available(
                    EndurainApiKey.from(
                            "synthetic-fixture-key".toCharArray()));
        }

        @Override
        public void reset(
                EndurainConnectionProfile profile)
                throws CredentialStoreException {
            states.put(scope(profile), State.NOT_CONFIGURED);
        }

        private static String scope(EndurainConnectionProfile profile) {
            return profile.getNormalizedBaseUrl()
                    + "|"
                    + profile.getConnectionIdentity();
        }
    }

    private static final class FakeTransport
            implements EndurainTransportGateway {
        private final Queue<UploadOutcome> results = new ArrayDeque<>();
        private int callCount;

        @Override
        public UploadOutcome upload(
                EndurainConnectionConfig config,
                TcxUploadArtifact artifact,
                EndurainApiKey credential) {
            callCount++;
            assertTrue(config.endpoint().normalizedBaseUrl()
                    .startsWith("https://"));
            assertTrue(artifact.file().isFile());
            assertEquals(artifact.byteLength(), artifact.file().length());
            assertFalse(credential.isClosed());
            assertNotNull(results.peek());
            return results.remove();
        }
    }

    private static final class FakeSchedule
            implements EndurainPublishingSchedule {
        private final List<Long> publishTimes = new ArrayList<>();
        private final List<Long> recoveryTimes = new ArrayList<>();

        @Override
        public void schedulePublishAt(long notBeforeUtcMs) {
            publishTimes.add(notBeforeUtcMs);
        }

        @Override
        public void scheduleRecoveryAt(long notBeforeUtcMs) {
            recoveryTimes.add(notBeforeUtcMs);
        }
    }

    private static final class FakeChangeSignal
            implements EndurainPublishingChangeSignal {
        private int count;

        @Override
        public void signal() {
            count++;
        }

        @Override
        public AutoCloseable observe(
                Executor executor,
                Runnable listener) {
            return () -> {
            };
        }
    }
}
