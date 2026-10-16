package com.long2know.sportlogger.transfer;

import com.long2know.utilities.models.SportActivity;
import com.long2know.utilities.transfer.ActivityTransferProtocol;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ActivityTransferReceiverTest {
    private File storeDirectory;

    @Before
    public void setUp() throws Exception {
        storeDirectory = new File(
                "build/test-data/activity-transfer-" + System.nanoTime());
        deleteRecursively(storeDirectory);
    }

    @After
    public void tearDown() throws Exception {
        deleteRecursively(storeDirectory);
    }

    @Test
    public void twoOfflineActivitiesPersistSeparatelyAndDuplicatesAreIdempotent()
            throws Exception {
        PhoneActivityStore store = new PhoneActivityStore(storeDirectory);
        List<String> acknowledgements = new ArrayList<>();
        ActivityTransferReceiver receiver = new ActivityTransferReceiver(
                "/sportactivity",
                store,
                (path, transferId, sourceUri) ->
                        acknowledgements.add(path + "|" + transferId + "|" + sourceUri),
                () -> {
                });
        SportActivity first = activity(1, 1_000L);
        SportActivity second = activity(2, 2_000L);
        byte[] firstBytes = SportActivity.serialize(first);
        byte[] secondBytes = SportActivity.serialize(second);

        receive(receiver, "watch-node", first, firstBytes);
        receive(receiver, "watch-node", second, secondBytes);
        receive(receiver, "watch-node", first, firstBytes);

        assertEquals(2, store.storedActivityCount());
        assertEquals(3, acknowledgements.size());
        assertFalse(acknowledgements.get(0).equals(acknowledgements.get(1)));

        PhoneActivityStore restartedStore = new PhoneActivityStore(storeDirectory);
        assertEquals(2, restartedStore.storedActivityCount());
    }

    @Test
    public void legacyFixedPathAndAssetPayloadRemainAccepted() throws Exception {
        PhoneActivityStore store = new PhoneActivityStore(storeDirectory);
        List<String> acknowledgements = new ArrayList<>();
        ActivityTransferReceiver receiver = new ActivityTransferReceiver(
                "/sportactivity",
                store,
                (path, transferId, sourceUri) -> acknowledgements.add(path),
                () -> {
                });
        SportActivity activity = activity(7, 7_000L);
        byte[] payload = SportActivity.serialize(activity);

        SportActivity received = receiver.receive(
                "/sportactivity",
                "legacy-watch",
                "wear://legacy-watch/sportactivity",
                null,
                null,
                null,
                payload);

        assertEquals(activity.Id, received.Id);
        assertEquals(1, store.storedActivityCount());
        assertEquals(1, acknowledgements.size());
        assertTrue(acknowledgements.get(0).startsWith("/sportactivity-ack/"));
    }

    @Test
    public void conflictingPayloadNeverOverwritesDurableReceipt() throws Exception {
        PhoneActivityStore store = new PhoneActivityStore(storeDirectory);
        byte[] first = new byte[]{1, 2, 3};
        byte[] conflicting = new byte[]{9, 8, 7};

        assertTrue(store.persist("watch-node", "transfer-1", first));
        try {
            store.persist("watch-node", "transfer-1", conflicting);
            fail("Expected conflicting transfer to fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("different activity data"));
        }
        assertEquals(1, store.storedActivityCount());
        assertFalse(store.persist("watch-node", "transfer-1", first));
    }

    @Test
    public void restartJobEntryRetriesFailedDataLayerQueueUntilMarkerClears()
            throws Exception {
        PhoneActivityStore store = new PhoneActivityStore(storeDirectory);
        RecordingDataLayer dataLayer = new RecordingDataLayer(2);
        AtomicInteger scheduled = new AtomicInteger();
        ActivityTransferReceiver receiver = new ActivityTransferReceiver(
                "/sportactivity",
                store,
                new WearDataLayerAcknowledger(dataLayer),
                () -> {
                    assertEquals(1, store.pendingAcknowledgements().size());
                    scheduled.incrementAndGet();
                });
        SportActivity activity = activity(8, 8_000L);
        byte[] payload = SportActivity.serialize(activity);
        String transferId = ActivityTransferProtocol.transferId(activity, payload);
        String sourceUri = "wear://watch-node"
                + ActivityTransferProtocol.activityPath("/sportactivity", transferId);
        String acknowledgementPath =
                ActivityTransferProtocol.acknowledgementPath(
                        "/sportactivity",
                        "watch-node",
                        transferId);

        try {
            receive(receiver, "watch-node", activity, payload);
            fail("Expected acknowledgement failure");
        } catch (IOException expected) {
            assertEquals("data layer unavailable 1", expected.getMessage());
        }
        assertEquals(1, scheduled.get());
        assertEquals(1, store.storedActivityCount());
        assertEquals(1, store.pendingAcknowledgements().size());
        assertEquals(1, dataLayer.attempts.size());

        PhoneActivityStore restartedStore = new PhoneActivityStore(storeDirectory);
        PendingAcknowledgementRetry.Result failedRetry =
                new PendingAcknowledgementRetry(
                        restartedStore,
                        new WearDataLayerAcknowledger(dataLayer))
                        .run();
        assertTrue(failedRetry.needsRetry());
        assertEquals(1, failedRetry.getPendingCount());
        assertEquals(0, failedRetry.getQueuedCount());
        assertEquals(1, restartedStore.pendingAcknowledgements().size());
        assertEquals(2, dataLayer.attempts.size());

        PendingAcknowledgementRetry.Result successfulRetry =
                new PendingAcknowledgementRetry(
                        restartedStore,
                        new WearDataLayerAcknowledger(dataLayer))
                        .run();
        assertFalse(successfulRetry.needsRetry());
        assertEquals(1, successfulRetry.getPendingCount());
        assertEquals(1, successfulRetry.getQueuedCount());
        assertTrue(restartedStore.pendingAcknowledgements().isEmpty());
        assertEquals(1, restartedStore.storedActivityCount());
        assertEquals(3, dataLayer.attempts.size());
        for (DataLayerAttempt attempt : dataLayer.attempts) {
            assertEquals(acknowledgementPath, attempt.acknowledgementPath);
            assertEquals(transferId, attempt.transferId);
            assertEquals(sourceUri, attempt.sourceUri);
            assertTrue(attempt.attempt != 0L);
        }
    }

    private static void receive(
            ActivityTransferReceiver receiver,
            String sourceNode,
            SportActivity activity,
            byte[] payload) throws Exception {
        String transferId = ActivityTransferProtocol.transferId(activity, payload);
        receiver.receive(
                ActivityTransferProtocol.activityPath("/sportactivity", transferId),
                sourceNode,
                "wear://" + sourceNode
                        + ActivityTransferProtocol.activityPath(
                        "/sportactivity",
                        transferId),
                transferId,
                (long) activity.Id,
                ActivityTransferProtocol.payloadSha256(payload),
                payload);
    }

    private static SportActivity activity(int id, long startedAt) {
        SportActivity activity = new SportActivity();
        activity.Id = id;
        activity.StartTimeUTC = new Date(startedAt);
        return activity;
    }

    private static final class RecordingDataLayer
            implements WearDataLayerAcknowledger.DataLayer {
        private final List<DataLayerAttempt> attempts = new ArrayList<>();
        private int failuresRemaining;

        private RecordingDataLayer(int failuresRemaining) {
            this.failuresRemaining = failuresRemaining;
        }

        @Override
        public void queue(
                String acknowledgementPath,
                String transferId,
                String sourceUri,
                long attempt) throws IOException {
            attempts.add(new DataLayerAttempt(
                    acknowledgementPath,
                    transferId,
                    sourceUri,
                    attempt));
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw new IOException("data layer unavailable " + attempts.size());
            }
        }
    }

    private static final class DataLayerAttempt {
        private final String acknowledgementPath;
        private final String transferId;
        private final String sourceUri;
        private final long attempt;

        private DataLayerAttempt(
                String acknowledgementPath,
                String transferId,
                String sourceUri,
                long attempt) {
            this.acknowledgementPath = acknowledgementPath;
            this.transferId = transferId;
            this.sourceUri = sourceUri;
            this.attempt = attempt;
        }
    }

    private static void deleteRecursively(File file) throws IOException {
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
            throw new IOException("Could not delete test file " + file);
        }
    }
}
