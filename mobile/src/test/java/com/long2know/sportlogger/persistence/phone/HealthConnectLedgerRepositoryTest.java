package com.long2know.sportlogger.persistence.phone;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 26)
public class HealthConnectLedgerRepositoryTest {
    private static final String SESSION_ID = "d59d512b-5e88-58f7-976f-99c6d771c527";
    private static final String EXERCISE_TYPE = "ExerciseSessionRecord";
    private static final String HEART_RATE_TYPE = "HeartRateRecord";

    private PhoneDatabase database;
    private PhoneActivityRepository activities;
    private HealthConnectLedgerRepository ledger;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        database = Room.inMemoryDatabaseBuilder(context, PhoneDatabase.class)
                .allowMainThreadQueries()
                .build();
        activities = new PhoneActivityRepository(database);
        ledger = new HealthConnectLedgerRepository(database);
        acceptRevision(1L, "942adf8c-40ed-44d0-b9a0-88520dd81dbe");
        acceptRevision(2L, "ef45b823-ed9f-4ef6-ae66-58ae528d00ac");
    }

    @After
    public void tearDown() {
        database.close();
    }

    @Test
    public void opaqueIdsVersionsOperationsAndPartialDeletionSurviveAcrossRevisions() {
        String exerciseClientId =
                HealthConnectLedgerRepository.deterministicClientRecordId(
                        SESSION_ID,
                        EXERCISE_TYPE,
                        0);
        long versionOne = ledger.allocateNextClientRecordVersion(
                SESSION_ID,
                exerciseClientId,
                1_000L);
        assertEquals(1L, versionOne);
        ledger.prepareRecords(
                SESSION_ID,
                1L,
                Collections.singletonList(new HealthConnectLedgerRepository.RecordPlan(
                        EXERCISE_TYPE,
                        0,
                        exerciseClientId,
                        versionOne,
                        repeat("a1", 32))),
                1_100L);
        HealthConnectLedgerRepository.RecordKey revisionOneKey =
                new HealthConnectLedgerRepository.RecordKey(
                        SESSION_ID,
                        1L,
                        EXERCISE_TYPE,
                        0);
        assertEquals(
                HealthConnectLedgerRepository.Operation.PUBLISH,
                ledger.beginOperation(
                                revisionOneKey,
                                versionOne,
                                HealthConnectLedgerRepository.Operation.PUBLISH,
                                1_200L)
                        .getOperation());
        String opaqueFirstId = "provider/record:alpha";
        HealthConnectLedgerRepository.LedgerRecord first = ledger.markUpserted(
                revisionOneKey,
                versionOne,
                opaqueFirstId,
                1_300L);
        assertEquals(opaqueFirstId, first.getProviderRecordId());
        assertEquals(Collections.singletonList(opaqueFirstId),
                first.getOwnedProviderRecordIds());
        assertEquals(2, ledger.operationReceipts(SESSION_ID, 1L).size());

        long versionTwo = ledger.allocateNextClientRecordVersion(
                SESSION_ID,
                exerciseClientId,
                2_000L);
        assertEquals(2L, versionTwo);
        String heartRateClientId =
                HealthConnectLedgerRepository.deterministicClientRecordId(
                        SESSION_ID,
                        HEART_RATE_TYPE,
                        0);
        long heartRateVersion = ledger.allocateNextClientRecordVersion(
                SESSION_ID,
                heartRateClientId,
                2_100L);
        List<HealthConnectLedgerRepository.LedgerRecord> prepared =
                ledger.prepareRecords(
                        SESSION_ID,
                        2L,
                        Arrays.asList(
                                new HealthConnectLedgerRepository.RecordPlan(
                                        EXERCISE_TYPE,
                                        0,
                                        exerciseClientId,
                                        versionTwo,
                                        repeat("b2", 32)),
                                new HealthConnectLedgerRepository.RecordPlan(
                                        HEART_RATE_TYPE,
                                        0,
                                        heartRateClientId,
                                        heartRateVersion,
                                        repeat("c3", 32))),
                        2_200L);
        assertEquals(
                HealthConnectLedgerRepository.Operation.UPDATE,
                prepared.get(0).getOperation());
        assertEquals(opaqueFirstId, prepared.get(0).getProviderRecordId());
        assertEquals(
                HealthConnectLedgerRepository.Operation.PUBLISH,
                prepared.get(1).getOperation());

        HealthConnectLedgerRepository.RecordKey revisionTwoExercise =
                new HealthConnectLedgerRepository.RecordKey(
                        SESSION_ID,
                        2L,
                        EXERCISE_TYPE,
                        0);
        ledger.beginOperation(
                revisionTwoExercise,
                versionTwo,
                HealthConnectLedgerRepository.Operation.UPDATE,
                2_300L);
        String opaqueSecondId = "opaque-string-id/β";
        ledger.markUpserted(
                revisionTwoExercise,
                versionTwo,
                opaqueSecondId,
                2_400L);
        assertEquals(3, ledger.ownedRecordsForSession(SESSION_ID).size());

        ledger.markAllOwnedDeletePending(SESSION_ID, 3_000L);
        ledger.beginOperation(
                revisionOneKey,
                versionOne,
                HealthConnectLedgerRepository.Operation.DELETE,
                3_100L);
        HealthConnectLedgerRepository.DeletionReceipt deleted =
                ledger.markDeleted(
                        revisionOneKey,
                        versionOne,
                        HealthConnectLedgerRepository.DeletionResult.DELETED,
                        opaqueFirstId,
                        null,
                        3_200L);
        assertEquals(
                HealthConnectLedgerRepository.DeletionResult.DELETED,
                deleted.getResult());
        assertEquals(1, ledger.deletionReceipts(SESSION_ID, 1L).size());

        ledger.beginOperation(
                revisionTwoExercise,
                versionTwo,
                HealthConnectLedgerRepository.Operation.DELETE,
                3_300L);
        ledger.markFailure(
                revisionTwoExercise,
                versionTwo,
                "PROVIDER_UNAVAILABLE",
                "retry later",
                3_400L);
        HealthConnectLedgerRepository.Progress progress =
                ledger.progress(SESSION_ID, 2L);
        assertEquals(2, progress.getDeletePending());
        assertEquals(0, progress.getDeleted());
        assertTrue(ledger.pendingDeletes(SESSION_ID, 2L).stream()
                .anyMatch(record -> record.getKey().getRecordType().equals(EXERCISE_TYPE)));
    }

    private void acceptRevision(long revision, String transferId) {
        ActivityRecord activity = ActivityRecord.newBuilder()
                .setSessionId(SESSION_ID)
                .setWatchRevision(revision)
                .setActivityType(ActivityType.ACTIVITY_TYPE_RUNNING)
                .setStartTimeUtcMs(1_000L)
                .setEndTimeUtcMs(2_000L + revision)
                .setElapsedDurationMs(1_000L + revision)
                .setSource(SourceMetadata.newBuilder()
                        .setKind(SourceKind.SOURCE_KIND_RAW_PLATFORM)
                        .setAppContractVersion(1))
                .build();
        activities.acceptActivityTransfer(
                CanonicalTransfers.activityTransfer(
                                transferId,
                                CanonicalPayload.create(activity))
                        .toByteArray(),
                5_000L + revision);
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
