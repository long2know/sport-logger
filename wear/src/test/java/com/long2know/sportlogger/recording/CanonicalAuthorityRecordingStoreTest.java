package com.long2know.sportlogger.recording;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.long2know.utilities.data_access.SqlLogger;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class CanonicalAuthorityRecordingStoreTest {
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(
                        "sport_logger_recording_ids",
                        Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        context.deleteDatabase(SqlLogger.DATABASE_NAME);
    }

    @Test
    public void allocatesDurableIdsWithoutCreatingOrMutatingLegacySql() {
        CanonicalAuthorityRecordingStore first =
                new CanonicalAuthorityRecordingStore(context);
        int firstId = first.createActivity(1_000L);
        first.writeSample(firstId);
        first.finishActivity(firstId, 2_000L);

        CanonicalAuthorityRecordingStore replacement =
                new CanonicalAuthorityRecordingStore(context);
        int secondId = replacement.createActivity(3_000L);
        replacement.deleteActivity(secondId);

        assertEquals(1_000_000_001, firstId);
        assertEquals(1_000_000_002, secondId);
        assertFalse(context.getDatabasePath(SqlLogger.DATABASE_NAME).exists());
        assertThrows(
                IllegalStateException.class,
                () -> replacement.loadActivity(secondId));
    }
}
