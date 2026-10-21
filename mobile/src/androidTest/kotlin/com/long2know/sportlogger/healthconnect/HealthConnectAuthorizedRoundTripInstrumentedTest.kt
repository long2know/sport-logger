package com.long2know.sportlogger.healthconnect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.long2know.sportlogger.healthconnect.testing.AuthorizedHealthConnectTestContract
import com.long2know.sportlogger.healthconnect.testing.HealthConnectConsentTestActivity
import com.long2know.sportlogger.persistence.phone.HealthConnectLedgerRepository
import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository
import com.long2know.utilities.contract.CanonicalPayload
import com.long2know.utilities.contract.CanonicalTransfers
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HealthConnectAuthorizedRoundTripInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun normalConsentPublishesUpdatesQueriesAndDeletesOnlyOwnedSyntheticRecords() =
        runBlocking {
            assertEquals(
                HealthConnectClient.SDK_AVAILABLE,
                HealthConnectClient.getSdkStatus(context),
            )
            val sessionId = UUID.randomUUID().toString()
            val runPrefix = "sl:$sessionId:"
            val start = Instant.now().minusSeconds(7_200)
            val end = start.plusSeconds(1_800)
            val activities = PhoneActivityRepository.open(context)
            val ledger = HealthConnectLedgerRepository.open(context)
            val provider = HealthConnectProviderFactory.create(context)
            val client = HealthConnectClient.getOrCreate(context)
            var highestRevision = 1L
            try {
                accept(
                    activities,
                    AuthorizedHealthConnectTestData.activity(
                        sessionId = sessionId,
                        revision = 1,
                        startTimeUtcMs = start.toEpochMilli(),
                        endTimeUtcMs = end.toEpochMilli(),
                        sampleCount = 1_001,
                        routePointCount = 2,
                    ),
                )
                val initialStatus = provider.refreshStatus(sessionId, 1)
                val grantedBefore =
                    client.permissionController.getGrantedPermissions()
                val permissionInput =
                    AuthorizedHealthConnectTestContract.permissionRequestInput(
                        initialStatus,
                        grantedBefore,
                    )
                val granted =
                    if (permissionInput.isEmpty()) {
                        grantedBefore
                    } else {
                        grantThroughNormalSystemUi(permissionInput)
                    }
                val requiredWrite =
                    initialStatus.permissions.required
                        .mapTo(linkedSetOf()) { it.androidPermission }
                val requiredRead =
                    AuthorizedHealthConnectTestContract.requiredReadPermissions()
                assertTrue(
                    "Normal consent did not grant every required write permission",
                    granted.containsAll(requiredWrite),
                )
                assertTrue(
                    "Normal consent did not grant every debug-only verification read permission",
                    granted.containsAll(requiredRead),
                )

                val published = provider.publish(sessionId, 1)
                assertEquals(HealthConnectOperationState.SUCCEEDED, published.state)
                assertEquals(
                    1,
                    readOwned<ExerciseSessionRecord>(client, start, end, runPrefix).size,
                )
                assertEquals(
                    2,
                    readOwned<HeartRateRecord>(client, start, end, runPrefix).size,
                )
                assertEquals(
                    2,
                    readOwned<SpeedRecord>(client, start, end, runPrefix).size,
                )
                assertSingleSummaryRecords(client, start, end, runPrefix)
                val publishedSession =
                    readOwned<ExerciseSessionRecord>(client, start, end, runPrefix)
                        .single()
                assertEquals(
                    2,
                    (publishedSession.exerciseRouteResult as ExerciseRouteResult.Data)
                        .exerciseRoute.route.size,
                )
                val publishedVersion =
                    publishedSession.metadata.clientRecordVersion

                highestRevision = 2
                accept(
                    activities,
                    AuthorizedHealthConnectTestData.activity(
                        sessionId = sessionId,
                        revision = 2,
                        startTimeUtcMs = start.toEpochMilli(),
                        endTimeUtcMs = end.toEpochMilli(),
                        sampleCount = 3,
                        routePointCount = 3,
                    ),
                )
                val updated = provider.update(sessionId, 2)
                assertEquals(HealthConnectOperationState.SUCCEEDED, updated.state)
                assertEquals(
                    1,
                    readOwned<HeartRateRecord>(client, start, end, runPrefix).size,
                )
                assertEquals(
                    1,
                    readOwned<SpeedRecord>(client, start, end, runPrefix).size,
                )
                assertSingleSummaryRecords(client, start, end, runPrefix)
                val updatedSession =
                    readOwned<ExerciseSessionRecord>(client, start, end, runPrefix)
                        .single()
                assertTrue(
                    updatedSession.metadata.clientRecordVersion > publishedVersion
                )
                assertEquals(
                    3,
                    (updatedSession.exerciseRouteResult as ExerciseRouteResult.Data)
                        .exerciseRoute.route.size,
                )

                val deleted = provider.delete(sessionId, 2)
                assertEquals(HealthConnectOperationState.SUCCEEDED, deleted.state)
                assertAllOwnedAbsent(client, start, end, runPrefix)
            } finally {
                runCatching { provider.delete(sessionId, highestRevision) }
                deleteExactOwnedProviderRecords(client, ledger, sessionId)
                runCatching {
                    activities.deleteLocal(
                        sessionId,
                        highestRevision,
                        System.currentTimeMillis(),
                    )
                }
                provider.close()
                ledger.close()
                activities.close()
            }
        }

    private suspend fun assertSingleSummaryRecords(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
        runPrefix: String,
    ) {
        assertEquals(1, readOwned<StepsRecord>(client, start, end, runPrefix).size)
        assertEquals(1, readOwned<DistanceRecord>(client, start, end, runPrefix).size)
        assertEquals(
            1,
            readOwned<TotalCaloriesBurnedRecord>(client, start, end, runPrefix).size,
        )
        assertEquals(
            1,
            readOwned<ElevationGainedRecord>(client, start, end, runPrefix).size,
        )
    }

    private suspend fun assertAllOwnedAbsent(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
        runPrefix: String,
    ) {
        assertTrue(readOwned<ExerciseSessionRecord>(client, start, end, runPrefix).isEmpty())
        assertTrue(readOwned<HeartRateRecord>(client, start, end, runPrefix).isEmpty())
        assertTrue(readOwned<SpeedRecord>(client, start, end, runPrefix).isEmpty())
        assertTrue(readOwned<StepsRecord>(client, start, end, runPrefix).isEmpty())
        assertTrue(readOwned<DistanceRecord>(client, start, end, runPrefix).isEmpty())
        assertTrue(
            readOwned<TotalCaloriesBurnedRecord>(client, start, end, runPrefix)
                .isEmpty()
        )
        assertTrue(
            readOwned<ElevationGainedRecord>(client, start, end, runPrefix)
                .isEmpty()
        )
    }

    private suspend inline fun <reified T : Record> readOwned(
        client: HealthConnectClient,
        start: Instant,
        end: Instant,
        runPrefix: String,
    ): List<T> {
        val result = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response =
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = T::class,
                        timeRangeFilter =
                            TimeRangeFilter.between(
                                start.minusSeconds(1),
                                end.plusSeconds(1),
                            ),
                        dataOriginFilter = setOf(DataOrigin(context.packageName)),
                        pageToken = pageToken,
                    )
                )
            result +=
                response.records.filter {
                    it.metadata.clientRecordId?.startsWith(runPrefix) == true
                }
            pageToken = response.pageToken
        } while (pageToken != null)
        return result
    }

    private fun grantThroughNormalSystemUi(
        requested: Set<String>,
    ): Set<String> {
        val latch = CountDownLatch(1)
        var result: Set<String>? = null
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    result =
                        intent.getStringArrayListExtra(
                            HealthConnectConsentTestActivity.EXTRA_GRANTED_PERMISSIONS
                        )?.toSet().orEmpty()
                    latch.countDown()
                }
            }
        val filter =
            IntentFilter(HealthConnectConsentTestActivity.ACTION_PERMISSION_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        try {
            context.startActivity(
                Intent(context, HealthConnectConsentTestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putStringArrayListExtra(
                        HealthConnectConsentTestActivity.EXTRA_REQUESTED_PERMISSIONS,
                        ArrayList(requested.sorted()),
                    )
            )
            driveConsentUi(latch)
            assertTrue(
                "Health Connect permission Activity returned no result",
                latch.await(1, TimeUnit.SECONDS),
            )
            return requireNotNull(result)
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private fun driveConsentUi(latch: CountDownLatch) {
        val device =
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val deadline = SystemClock.uptimeMillis() + CONSENT_TIMEOUT_MS
        var allowAllClicked = false
        while (latch.count > 0 && SystemClock.uptimeMillis() < deadline) {
            device.waitForIdle()
            val target =
                if (!allowAllClicked) {
                    device.findObject(
                        By.text(Pattern.compile("(?i)^allow all$"))
                    )?.also { allowAllClicked = true }
                } else {
                    null
                }
                    ?: device.findObject(
                        By.text(
                            Pattern.compile(
                                "(?i)^(allow|continue|next|done|get started)$"
                            )
                        )
                    )
            if (target != null && target.isEnabled) {
                target.click()
                SystemClock.sleep(400)
            } else {
                SystemClock.sleep(200)
            }
        }
        if (latch.count > 0) {
            val hierarchy = ByteArrayOutputStream()
            device.dumpWindowHierarchy(hierarchy)
            fail(
                "Normal Health Connect consent did not complete. " +
                    hierarchy.toString(Charsets.UTF_8.name())
            )
        }
    }

    private fun accept(
        repository: PhoneActivityRepository,
        activity: com.long2know.utilities.contract.proto.ActivityRecord,
    ) {
        repository.acceptActivityTransfer(
            CanonicalTransfers.activityTransfer(
                UUID.randomUUID().toString(),
                CanonicalPayload.create(activity),
            ).toByteArray(),
            System.currentTimeMillis(),
        )
    }

    private suspend fun deleteExactOwnedProviderRecords(
        client: HealthConnectClient,
        ledger: HealthConnectLedgerRepository,
        sessionId: String,
    ) {
        val byType =
            ledger.ownedRecordsForSession(sessionId).groupBy { it.key.recordType }
        for ((recordType, records) in byType) {
            val providerIds =
                records
                    .flatMap {
                        it.ownedProviderRecordIds +
                            listOfNotNull(it.providerRecordId)
                    }
                    .distinct()
            val clientIds = records.map { it.clientRecordId }.distinct()
            runCatching {
                client.deleteRecords(
                    recordType = providerClass(recordType),
                    recordIdsList = providerIds,
                    clientRecordIdsList = clientIds,
                )
            }
        }
    }

    private fun providerClass(
        recordType: String,
    ): kotlin.reflect.KClass<out Record> =
        when (recordType) {
            "ExerciseSessionRecord" -> ExerciseSessionRecord::class
            "HeartRateRecord" -> HeartRateRecord::class
            "SpeedRecord" -> SpeedRecord::class
            "StepsRecord" -> StepsRecord::class
            "DistanceRecord" -> DistanceRecord::class
            "TotalCaloriesBurnedRecord" -> TotalCaloriesBurnedRecord::class
            "ElevationGainedRecord" -> ElevationGainedRecord::class
            else -> throw AssertionError("Unknown owned Health Connect type: $recordType")
        }

    companion object {
        private const val CONSENT_TIMEOUT_MS = 30_000L
    }
}
