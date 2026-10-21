package com.long2know.sportlogger.transfer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.long2know.utilities.models.SportActivity;
import com.long2know.utilities.models.SportTrackPoint;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.Date;
import java.util.UUID;

public final class PhoneActivityStoreReadTest {
    private File directory;

    @Before
    public void setUp() throws Exception {
        directory = new File(
                "build/test-data/phone-store-" + UUID.randomUUID());
        assertTrue(directory.mkdirs());
    }

    @After
    public void tearDown() {
        deleteRecursively(directory);
    }

    @Test
    public void reloadsImmutablePayloadAfterStoreRestart() throws Exception {
        PhoneActivityStore first = new PhoneActivityStore(directory);
        SportActivity expected = activity(7, 1_000L, "Synthetic walk");
        byte[] payload = SportActivity.serialize(expected);
        assertTrue(first.persist("watch-node", "transfer-7", payload));
        byte[] storedBefore = Files.readAllBytes(onlyPayload().toPath());

        PhoneActivityStore restarted = new PhoneActivityStore(directory);
        PhoneActivityStore.StoredActivityListing listing =
                restarted.listStoredActivities(20);
        assertEquals(1, listing.getTotalCount());
        assertFalse(listing.isTruncated());
        PhoneActivityStore.StoredActivityFile file =
                listing.getActivities().get(0);
        SportActivity actual =
                restarted.loadStoredActivity(file.getStorageId());

        assertEquals(expected.Id, actual.Id);
        assertEquals(expected.Name, actual.Name);
        assertEquals(expected.StartTimeUTC, actual.StartTimeUTC);
        assertEquals(1, actual.SportTrackPoints.size());
        assertArrayEquals(storedBefore, Files.readAllBytes(onlyPayload().toPath()));
    }

    @Test
    public void listingReportsPendingAcknowledgementState() throws Exception {
        PhoneActivityStore store = new PhoneActivityStore(directory);
        SportActivity activity = activity(8, 2_000L, "Synthetic run");
        byte[] payload = SportActivity.serialize(activity);
        store.persist("watch-node", "transfer-8", payload);
        PhoneActivityStore.PendingAcknowledgement pending =
                store.prepareAcknowledgement(
                        "watch-node",
                        "transfer-8",
                        "/sportactivity-ack/watch-node/transfer-8",
                        "wear://watch-node/sportactivity/transfer-8");

        PhoneActivityStore.StoredActivityFile file =
                store.listStoredActivities(20).getActivities().get(0);
        assertTrue(file.isAcknowledgementPending());

        store.acknowledgementQueued(pending);
        assertFalse(store.isAcknowledgementPending(file.getStorageId()));
    }

    @Test
    public void corruptPayloadDoesNotHideReadableStoredActivity() throws Exception {
        PhoneActivityStore store = new PhoneActivityStore(directory);
        SportActivity activity = activity(9, 3_000L, "Synthetic ride");
        store.persist(
                "watch-node",
                "transfer-9",
                SportActivity.serialize(activity));
        try (FileOutputStream output =
                     new FileOutputStream(new File(directory, "corrupt.ser"))) {
            output.write(new byte[]{0x01, 0x02, 0x03});
        }

        int readable = 0;
        int corrupt = 0;
        for (PhoneActivityStore.StoredActivityFile file :
                store.listStoredActivities(20).getActivities()) {
            try {
                SportActivity loaded =
                        store.loadStoredActivity(file.getStorageId());
                assertEquals("Synthetic ride", loaded.Name);
                readable++;
            } catch (PhoneActivityStore.ActivityReadException error) {
                assertEquals(
                        PhoneActivityStore.ReadFailure.CORRUPT,
                        error.getFailure());
                corrupt++;
            }
        }
        assertEquals(1, readable);
        assertEquals(1, corrupt);
    }

    @Test
    public void rejectsOversizedPayloadBeforeDeserialization() throws Exception {
        File oversized = new File(directory, "oversized.ser");
        try (RandomAccessFile file = new RandomAccessFile(oversized, "rw")) {
            file.setLength(64L * 1024L * 1024L + 1L);
        }

        try {
            new PhoneActivityStore(directory).loadStoredActivity("oversized");
            fail("Expected oversized payload failure");
        } catch (PhoneActivityStore.ActivityReadException error) {
            assertEquals(
                    PhoneActivityStore.ReadFailure.TOO_LARGE,
                    error.getFailure());
        }
    }

    private File onlyPayload() {
        File[] files = directory.listFiles(
                file -> file.getName().endsWith(".ser"));
        assertNotNull(files);
        assertEquals(1, files.length);
        return files[0];
    }

    private static SportActivity activity(int id, long startedAt, String name) {
        SportActivity activity = new SportActivity();
        activity.Id = id;
        activity.Name = name;
        activity.StartTimeUTC = new Date(startedAt);
        activity.EndTimeUTC = new Date(startedAt + 60_000L);
        SportTrackPoint point = new SportTrackPoint();
        point.TimeStampUTC = new Date(startedAt);
        point.Latitude = 47.6205D;
        point.Longitude = -122.3493D;
        point.HeartRate = 100D;
        activity.SportTrackPoints.add(point);
        return activity;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
