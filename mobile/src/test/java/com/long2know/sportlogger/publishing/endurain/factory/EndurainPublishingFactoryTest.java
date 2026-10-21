package com.long2know.sportlogger.publishing.endurain.factory;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository;
import com.long2know.sportlogger.persistence.phone.PhoneDatabase;
import com.long2know.sportlogger.publishing.endurain.EndurainCredentialGateway;
import com.long2know.sportlogger.publishing.endurain.profile.EndurainConnectionProfile;
import com.long2know.utilities.contract.CanonicalPayload;
import com.long2know.utilities.contract.CanonicalTransfers;
import com.long2know.utilities.contract.proto.ActivityRecord;
import com.long2know.utilities.contract.proto.ActivityType;
import com.long2know.utilities.contract.proto.SourceKind;
import com.long2know.utilities.contract.proto.SourceMetadata;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 26)
public final class EndurainPublishingFactoryTest {
    private static final String SESSION_ID =
            "ef40e21d-1289-4ee9-958b-598ee5938f21";
    private static final String TRANSFER_ID =
            "7edf7ed0-4779-4ef1-9dd9-13910fd0105c";

    private Context context;
    private ExecutorService worker;
    private EndurainPublishingController controller;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(PhoneDatabase.DATABASE_NAME);
        deleteRecursively(new File(
                context.getNoBackupFilesDir(),
                "endurain-publishing-v1"));
        assertTrue(context.getSharedPreferences(
                        "endurain_connection_profiles_v1",
                        Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit());
        worker = Executors.newSingleThreadExecutor();
    }

    @After
    public void tearDown() throws Exception {
        if (controller != null) {
            controller.close();
        }
        worker.shutdown();
        assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
        context.deleteDatabase(PhoneDatabase.DATABASE_NAME);
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
    public void productionFactoryComposesRealPersistentController()
            throws Exception {
        controller = EndurainPublishingFactory.create(context, worker)
                .get(10, TimeUnit.SECONDS);
        AtomicInteger callbacks = new AtomicInteger();
        AutoCloseable observation =
                controller.observe(Runnable::run, callbacks::incrementAndGet);
        EndurainConnectionProfile profile = controller.saveConnection(
                        null,
                        "Factory fixture",
                        "https://endurain.example",
                        true)
                .get(10, TimeUnit.SECONDS);
        assertTrue(callbacks.get() > 0);
        observation.close();
        EndurainPublishingSnapshot snapshot = controller.statusList()
                .get(10, TimeUnit.SECONDS);

        assertEquals(1, snapshot.getConnections().size());
        assertEquals(
                profile.getConnectionIdentity(),
                snapshot.getConnections()
                        .get(0)
                        .getProfile()
                        .getConnectionIdentity());
        assertEquals(
                EndurainCredentialGateway.State.NOT_CONFIGURED,
                snapshot.getConnections()
                        .get(0)
                        .getCredentialState());
        assertTrue(snapshot.getDeliveries().isEmpty());
    }

    @Test
    public void productionControllerObservesExternalOwnershipRecoveryAndRemoteIds()
            throws Exception {
        controller = EndurainPublishingFactory.create(context, worker)
                .get(10, TimeUnit.SECONDS);
        PhoneActivityRepository writer =
                PhoneActivityRepository.open(context);
        ExecutorService writerWorker = Executors.newSingleThreadExecutor();
        AutoCloseable observation = null;
        try {
            CompletableFuture.runAsync(
                            () -> acceptActivity(writer),
                            writerWorker)
                    .get(10, TimeUnit.SECONDS);
            EndurainConnectionProfile profile = controller.saveConnection(
                            null,
                            "Observed fixture",
                            "https://endurain.example",
                            true)
                    .get(10, TimeUnit.SECONDS);
            EndurainActionResult queued = controller.queuePublish(
                            profile.getConnectionIdentity(),
                            SESSION_ID,
                            1L)
                    .get(10, TimeUnit.SECONDS);
            PhoneActivityRepository.ConnectorKey key =
                    queued.getDeliveryKey().toRepositoryKey();
            BlockingQueue<Boolean> callbacks = new LinkedBlockingQueue<>();
            observation = controller.observe(
                    Runnable::run,
                    () -> callbacks.offer(Boolean.TRUE));

            assertTrue(CompletableFuture.supplyAsync(
                            () -> writer.claimConnectorDelivery(
                                    key,
                                    PhoneActivityRepository.ConnectorState.PENDING,
                                    41L,
                                    "external-writer",
                                    10_000L,
                                    2_000L),
                            writerWorker)
                    .get(10, TimeUnit.SECONDS));
            awaitCallback(callbacks);
            assertEquals(
                    EndurainDeliveryStatus.State.PENDING,
                    onlyDelivery(controller).getState());

            CompletableFuture.runAsync(
                            () -> writer.transitionConnectorDelivery(
                                    key,
                                    41L,
                                    PhoneActivityRepository.ConnectorState.PENDING,
                                    PhoneActivityRepository.ConnectorState.UNKNOWN_DELIVERY,
                                    null,
                                    "DROPPED_RESPONSE",
                                    "The provider may have accepted the upload",
                                    2_100L),
                            writerWorker)
                    .get(10, TimeUnit.SECONDS);
            awaitCallback(callbacks);
            EndurainDeliveryStatus unknown = onlyDelivery(controller);
            assertEquals(
                    EndurainDeliveryStatus.State.UNKNOWN_DELIVERY,
                    unknown.getState());
            assertEquals(
                    "DROPPED_RESPONSE",
                    unknown.getLastFailureCode());

            CompletableFuture.runAsync(
                            () -> writer.reconcileRemoteIds(
                                    key,
                                    Collections.singletonList(91L),
                                    2_200L),
                            writerWorker)
                    .get(10, TimeUnit.SECONDS);
            awaitCallback(callbacks);
            EndurainDeliveryStatus delivered = onlyDelivery(controller);
            assertEquals(
                    EndurainDeliveryStatus.State.UPLOADED,
                    delivered.getState());
            assertEquals(
                    Collections.singletonList(91L),
                    delivered.getRemoteIds());

            CompletableFuture.runAsync(writer::close, writerWorker)
                    .get(10, TimeUnit.SECONDS);
            EndurainDeliveryStatus afterWriterClose =
                    onlyDelivery(controller);
            assertEquals(
                    EndurainDeliveryStatus.State.UPLOADED,
                    afterWriterClose.getState());
            assertEquals(
                    Collections.singletonList(91L),
                    afterWriterClose.getRemoteIds());
        } finally {
            if (observation != null) {
                observation.close();
            }
            writer.close();
            writerWorker.shutdown();
            assertTrue(writerWorker.awaitTermination(
                    10,
                    TimeUnit.SECONDS));
        }
    }

    private static void acceptActivity(PhoneActivityRepository repository) {
        ActivityRecord activity = ActivityRecord.newBuilder()
                .setSessionId(SESSION_ID)
                .setWatchRevision(1L)
                .setActivityType(ActivityType.ACTIVITY_TYPE_RUNNING)
                .setStartTimeUtcMs(1_000L)
                .setEndTimeUtcMs(2_000L)
                .setElapsedDurationMs(1_000L)
                .setActiveDurationMs(900L)
                .setSource(SourceMetadata.newBuilder()
                        .setKind(SourceKind.SOURCE_KIND_HEALTH_SERVICES)
                        .setProvider("synthetic-test")
                        .setAppContractVersion(1))
                .build();
        repository.acceptActivityTransfer(
                CanonicalTransfers.activityTransfer(
                                TRANSFER_ID,
                                CanonicalPayload.create(activity))
                        .toByteArray(),
                1_500L);
    }

    private static EndurainDeliveryStatus onlyDelivery(
            EndurainPublishingController controller) throws Exception {
        EndurainPublishingSnapshot snapshot =
                controller.statusList().get(10, TimeUnit.SECONDS);
        assertEquals(1, snapshot.getDeliveries().size());
        return snapshot.getDeliveries().get(0);
    }

    private static void awaitCallback(
            BlockingQueue<Boolean> callbacks) throws Exception {
        assertNotNull(callbacks.poll(5, TimeUnit.SECONDS));
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!file.delete()) {
            file.deleteOnExit();
        }
    }
}
