package com.long2know.sportlogger.persistence.phone;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.long2know.utilities.contract.CanonicalIds;
import com.long2know.utilities.contract.CanonicalPayload;
import com.long2know.utilities.models.SportActivity;
import com.long2know.utilities.models.SportTrackPoint;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Date;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 26)
public class LegacyPhoneInboxBackfillTest {
    private File directory;
    private PhoneDatabase database;
    private PhoneActivityRepository repository;

    @Before
    public void setUp() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        database = Room.inMemoryDatabaseBuilder(context, PhoneDatabase.class)
                .allowMainThreadQueries()
                .build();
        repository = new PhoneActivityRepository(database);
        directory = new File("build/test-data/legacy-phone-" + System.nanoTime());
        deleteRecursively(directory);
        assertTrue(directory.mkdirs());
    }

    @After
    public void tearDown() throws Exception {
        database.close();
        deleteRecursively(directory);
    }

    @Test
    public void backfillPreservesFilesMetadataPresenceAndIdempotentReceipts()
            throws Exception {
        File validFile = new File(directory, "watch-a-transfer-1.ser");
        byte[] validBytes = SportActivity.serialize(activity(7, false));
        Files.write(validFile.toPath(), validBytes);
        File partialFile = new File(directory, "watch-a-transfer-2.ser");
        byte[] partialBytes = SportActivity.serialize(activity(8, true));
        Files.write(partialFile.toPath(), partialBytes);
        File corruptFile = new File(directory, "watch-a-transfer-3.ser");
        byte[] corruptBytes = new byte[]{1, 2, 3, 4};
        Files.write(corruptFile.toPath(), corruptBytes);

        String sourceFileKey = sha256Hex(
                validFile.getName().getBytes(StandardCharsets.UTF_8));
        String sessionId = CanonicalIds.deterministicLegacySessionId(
                "phone-serialized:" + sourceFileKey,
                7L);
        repository.setMetadata(
                sessionId,
                "User title",
                "User notes",
                Collections.singletonList("keep"),
                500L);

        LegacyPhoneInboxBackfill backfill = new LegacyPhoneInboxBackfill(repository);
        LegacyPhoneInboxBackfill.BackfillResult first =
                backfill.backfill(directory, 1_000L);

        assertEquals(3, first.getSourceFiles());
        assertEquals(1, first.getImported());
        assertEquals(0, first.getAlreadyProcessed());
        assertEquals(2, first.getPartialOrFailed());
        assertArrayEquals(validBytes, Files.readAllBytes(validFile.toPath()));
        assertArrayEquals(partialBytes, Files.readAllBytes(partialFile.toPath()));
        assertArrayEquals(corruptBytes, Files.readAllBytes(corruptFile.toPath()));
        PhoneActivityRepository.ActivityDetail detail = repository.detail(sessionId, 1L);
        assertNotNull(detail);
        assertEquals("User title", detail.getMetadata().getTitle());
        assertEquals("User notes", detail.getMetadata().getNotes());
        assertEquals(Collections.singletonList("keep"), detail.getMetadata().getTags());
        assertEquals(0D,
                detail.getActivity().getSamples(0)
                        .getLocation().getValue().getLatitudeDegrees(),
                0D);
        assertEquals(0D,
                detail.getActivity().getSamples(0)
                        .getLocation().getValue().getLongitudeDegrees(),
                0D);
        assertFalse(detail.getActivity().getSamples(0)
                .getLocation().getValue().hasAltitudeM());

        LegacyPhoneInboxBackfill.BackfillResult repeat =
                backfill.backfill(directory, 2_000L);
        assertEquals(3, repeat.getSourceFiles());
        assertEquals(0, repeat.getImported());
        assertEquals(3, repeat.getAlreadyProcessed());
        assertEquals(0, repeat.getPartialOrFailed());
        assertEquals(2, repository.summaries(0, 10).size());
    }

    private static SportActivity activity(int id, boolean malformedPoint) {
        SportActivity activity = new SportActivity();
        activity.Id = id;
        activity.StartTimeUTC = new Date(1_000L);
        activity.EndTimeUTC = new Date(2_000L);
        activity.Name = "Legacy title";
        activity.Description = "Legacy notes";
        activity.Distance = 100.25D;
        activity.Time = 1D;
        SportTrackPoint point = new SportTrackPoint();
        point.Id = 1;
        point.SportActivityId = id;
        point.TimeStampUTC = malformedPoint ? null : new Date(1_500L);
        point.Latitude = 0D;
        point.Longitude = 0D;
        point.HeartRate = 140D;
        activity.SportTrackPoints.add(point);
        return activity;
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return CanonicalPayload.hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void deleteRecursively(File file) throws Exception {
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
            throw new IllegalStateException("Could not delete " + file);
        }
    }
}
