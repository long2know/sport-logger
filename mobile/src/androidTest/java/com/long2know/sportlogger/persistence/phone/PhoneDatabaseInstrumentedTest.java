package com.long2know.sportlogger.persistence.phone;

import android.content.Context;

import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.long2know.utilities.contract.CanonicalPayload;
import com.long2know.utilities.contract.CanonicalTransfers;
import com.long2know.utilities.contract.proto.AckResult;
import com.long2know.utilities.contract.proto.ActivityRecord;
import com.long2know.utilities.contract.proto.ActivityType;
import com.long2know.utilities.contract.proto.SourceKind;
import com.long2know.utilities.contract.proto.SourceMetadata;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(AndroidJUnit4.class)
public class PhoneDatabaseInstrumentedTest {
    private static final String DATABASE = "phone-canonical-instrumented.db";
    private static final String SESSION_ID = "d972537d-df23-5bfe-93d4-40ddf0fef286";
    private static final String TRANSFER_ID = "573cda14-bf2c-45ba-881f-e552a8e6a74a";

    private Context context;
    private PhoneDatabase database;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.deleteDatabase(DATABASE);
        database = Room.databaseBuilder(context, PhoneDatabase.class, DATABASE)
                .allowMainThreadQueries()
                .build();
    }

    @After
    public void tearDown() {
        database.close();
        context.deleteDatabase(DATABASE);
    }

    @Test
    public void versionOneCommitsRevisionAndExactAcknowledgement() {
        PhoneActivityRepository repository = new PhoneActivityRepository(database);
        ActivityRecord activity = ActivityRecord.newBuilder()
                .setSessionId(SESSION_ID)
                .setWatchRevision(1L)
                .setActivityType(ActivityType.ACTIVITY_TYPE_RUNNING)
                .setStartTimeUtcMs(1_000L)
                .setEndTimeUtcMs(2_000L)
                .setElapsedDurationMs(1_000L)
                .setSource(SourceMetadata.newBuilder()
                        .setKind(SourceKind.SOURCE_KIND_RAW_PLATFORM)
                        .setAppContractVersion(1))
                .build();
        byte[] transfer = CanonicalTransfers.activityTransfer(
                        TRANSFER_ID,
                        CanonicalPayload.create(activity))
                .toByteArray();

        PhoneActivityRepository.AcceptResult result =
                repository.acceptActivityTransfer(transfer, 3_000L);

        assertEquals(AckResult.ACK_RESULT_ACCEPTED, result.getAcknowledgement().getResult());
        assertNotNull(repository.detail(SESSION_ID, 1L));
        assertEquals(1, repository.summaries(0, 10).size());
    }
}
