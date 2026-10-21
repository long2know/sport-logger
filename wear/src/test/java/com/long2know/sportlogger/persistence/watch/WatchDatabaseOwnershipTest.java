package com.long2know.sportlogger.persistence.watch;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

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

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class WatchDatabaseOwnershipTest {
    private static final String SESSION_ID = "4dce8d87-2a55-53b0-bf53-56124f34ba7e";
    private static final String TRANSFER_ID = "c13b8f8c-413e-42ca-b18e-0fd5ba74afeb";

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        WatchDatabase.resetForTests();
        context.deleteDatabase(WatchDatabase.DATABASE_NAME);
    }

    @After
    public void tearDown() {
        WatchDatabase.resetForTests();
        context.deleteDatabase(WatchDatabase.DATABASE_NAME);
    }

    @Test
    public void serviceAndImmediateSyncShareOneOpenDatabaseWithoutLocking() {
        WatchDatabase first = WatchDatabase.open(context);
        WatchDatabase second = WatchDatabase.open(context);
        assertSame(first, second);

        WatchActivityRepository serviceRepository =
                WatchActivityRepository.open(context);
        WatchActivityRepository syncRepository =
                WatchActivityRepository.open(context);
        try {
            serviceRepository.startSession(
                            WatchActivityRepository.StartRequest
                                    .builder(SESSION_ID, 71L, 1_000L)
                                    .activityType(ActivityType.ACTIVITY_TYPE_RUNNING)
                                    .build())
                    .join();
            CompletableFuture<WatchActivityRepository.CompletionResult> completion =
                    serviceRepository.finishSession(
                            SESSION_ID,
                            71L,
                            activity(),
                            TRANSFER_ID,
                            2_100L);
            CompletableFuture<List<WatchActivityRepository.OutboxItem>> immediateSync =
                    syncRepository.reclaimPendingOutbox(10, 2_100L);

            CompletableFuture.allOf(completion, immediateSync).join();
            List<WatchActivityRepository.OutboxItem> pending =
                    syncRepository.pendingOutbox(10).join();
            assertEquals(1, pending.size());
            assertEquals(TRANSFER_ID, pending.get(0).getTransferId());

            syncRepository.close();
            assertEquals(
                    WatchActivityRepository.LifecycleState.COMPLETED,
                    serviceRepository.session(SESSION_ID).join().getState());
            assertSame(first, WatchDatabase.open(context));
        } finally {
            serviceRepository.close();
            syncRepository.close();
        }
    }

    private static ActivityRecord activity() {
        return ActivityRecord.newBuilder()
                .setSessionId(SESSION_ID)
                .setWatchRevision(1L)
                .setActivityType(ActivityType.ACTIVITY_TYPE_RUNNING)
                .setStartTimeUtcMs(1_000L)
                .setEndTimeUtcMs(2_000L)
                .setElapsedDurationMs(1_000L)
                .setActiveDurationMs(900L)
                .setSource(SourceMetadata.newBuilder()
                        .setKind(SourceKind.SOURCE_KIND_RAW_PLATFORM)
                        .setAppContractVersion(1))
                .build();
    }
}
