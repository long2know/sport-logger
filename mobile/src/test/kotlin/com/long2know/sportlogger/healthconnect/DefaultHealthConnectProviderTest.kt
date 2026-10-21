package com.long2know.sportlogger.healthconnect

import android.content.Context
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.long2know.sportlogger.persistence.phone.HealthConnectLedgerRepository
import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository
import com.long2know.sportlogger.persistence.phone.PhoneDatabase
import com.long2know.utilities.contract.CanonicalMeasurements
import com.long2know.utilities.contract.CanonicalPayload
import com.long2know.utilities.contract.CanonicalTransfers
import com.long2know.utilities.contract.proto.ActivityRecord
import com.long2know.utilities.contract.proto.MeasurementSample
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DefaultHealthConnectProviderTest {
    private lateinit var database: PhoneDatabase
    private lateinit var activities: PhoneActivityRepository
    private lateinit var ledger: HealthConnectLedgerRepository
    private lateinit var gateway: FakeHealthConnectGateway
    private lateinit var provider: DefaultHealthConnectProvider
    private lateinit var clock: AtomicLong

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database =
            Room.inMemoryDatabaseBuilder(context, PhoneDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        activities = PhoneActivityRepository(database)
        ledger = HealthConnectLedgerRepository(database)
        gateway = FakeHealthConnectGateway()
        clock = AtomicLong(10_000)
        provider =
            DefaultHealthConnectProvider(
                activities = activities,
                ledger = ledger,
                availability = { HealthConnectAvailability.AVAILABLE_APK },
                resolutionIntent = { null },
                gatewayFactory = { gateway },
                mapper = HealthConnectRecordMapper(),
                nowUtcMs = { clock.incrementAndGet() },
            )
    }

    @After
    fun tearDown() {
        provider.close()
        database.close()
    }

    @Test
    fun partialGrantWritesRealSessionAndReportsSkippedRouteAndHeartRate() = runBlocking {
        accept(
            HealthConnectTestData.activity(
                revision = 1,
                samples =
                    listOf(
                        HealthConnectTestData.sample(
                            1,
                            HealthConnectTestData.START_MS,
                            location = HealthConnectTestData.location(47.60, -122.33),
                            heartRate =
                                HealthConnectTestData.doubleMeasurement(
                                    CanonicalMeasurements.Metric.HEART_RATE_BPM,
                                    120.0,
                                ),
                        )
                    ),
            )
        )
        gateway.granted += HealthConnectWritePermission.EXERCISE_SESSION.androidPermission

        val result = provider.publish(HealthConnectTestData.SESSION_ID, 1)

        assertEquals(HealthConnectOperationState.PARTIAL, result.state)
        assertEquals(
            setOf(
                HealthConnectWritePermission.PHONE_EXERCISE_ROUTE,
                HealthConnectWritePermission.HEART_RATE,
            ),
            result.missingPermissions,
        )
        val inserted = gateway.upsertedRecords.single() as ExerciseSessionRecord
        assertTrue(inserted.exerciseRouteResult is ExerciseRouteResult.NoData)
        assertEquals(
            HealthConnectLedgerRepository.RecordState.UPSERTED,
            ledger.records(HealthConnectTestData.SESSION_ID, 1).single().state,
        )
    }

    @Test
    fun deniedExercisePermissionIsTypedAndMakesNoProviderCall() = runBlocking {
        accept(HealthConnectTestData.activity(revision = 1))

        val result = provider.publish(HealthConnectTestData.SESSION_ID, 1)

        assertEquals(HealthConnectOperationState.BLOCKED, result.state)
        assertEquals(HealthConnectErrorCode.PERMISSION_DENIED, result.failure?.code)
        assertTrue(gateway.upsertedRecords.isEmpty())
        assertTrue(ledger.records(HealthConnectTestData.SESSION_ID, 1).isEmpty())
    }

    @Test
    fun updateWithoutPhoneRoutePermissionPreservesPriorRouteAndStaysPartial() = runBlocking {
        gateway.granted += allPermissions()
        accept(routeActivity(revision = 1))
        assertEquals(
            HealthConnectOperationState.SUCCEEDED,
            provider.publish(HealthConnectTestData.SESSION_ID, 1).state,
        )

        accept(HealthConnectTestData.activity(revision = 2))
        gateway.granted -= HealthConnectWritePermission.PHONE_EXERCISE_ROUTE.androidPermission
        val result = provider.update(HealthConnectTestData.SESSION_ID, 2)

        assertEquals(HealthConnectOperationState.PARTIAL, result.state)
        assertTrue(
            HealthConnectWritePermission.PHONE_EXERCISE_ROUTE in result.missingPermissions
        )
        assertTrue(gateway.preservedRouteUpdates.isNotEmpty())
        assertTrue(
            ledger.records(HealthConnectTestData.SESSION_ID, 1).any {
                it.state == HealthConnectLedgerRepository.RecordState.UPSERTED
            }
        )
    }

    @Test
    fun changedChunkCountAdvancesVersionsAndDeletesEverySupersededRecord() = runBlocking {
        gateway.granted += allPermissions()
        accept(heartRateActivity(revision = 1, sampleCount = 1_001))
        assertEquals(
            HealthConnectOperationState.SUCCEEDED,
            provider.publish(HealthConnectTestData.SESSION_ID, 1).state,
        )
        assertEquals(3, ledger.records(HealthConnectTestData.SESSION_ID, 1).size)

        accept(heartRateActivity(revision = 2, sampleCount = 1))
        val result = provider.update(HealthConnectTestData.SESSION_ID, 2)

        assertEquals(HealthConnectOperationState.SUCCEEDED, result.state)
        assertTrue(
            ledger.records(HealthConnectTestData.SESSION_ID, 1).all {
                it.state == HealthConnectLedgerRepository.RecordState.DELETED
            }
        )
        val revisionTwo = ledger.records(HealthConnectTestData.SESSION_ID, 2)
        assertEquals(2, revisionTwo.size)
        assertTrue(
            revisionTwo.all {
                it.state == HealthConnectLedgerRepository.RecordState.UPSERTED
            }
        )
        assertEquals(
            2L,
            revisionTwo.single {
                it.key.recordType == HealthConnectRecordMapper.PROVIDER_EXERCISE_SESSION
            }.clientRecordVersion,
        )
        assertEquals(
            2L,
            revisionTwo.single {
                it.key.recordType == HealthConnectRecordMapper.PROVIDER_HEART_RATE
            }.clientRecordVersion,
        )
        val removedChunkId =
            HealthConnectLedgerRepository.deterministicClientRecordId(
                HealthConnectTestData.SESSION_ID,
                HealthConnectRecordMapper.PROVIDER_HEART_RATE,
                1,
            )
        assertTrue(
            gateway.deleteRequests.any {
                removedChunkId in it.clientRecordIds ||
                    "provider/$removedChunkId" in it.providerRecordIds
            }
        )
    }

    @Test
    fun retryResumesOnlyDurableFailedRecords() = runBlocking {
        gateway.granted += allPermissions()
        accept(heartRateActivity(revision = 1, sampleCount = 2))
        gateway.failNextUpsertType = HealthConnectRecordMapper.PROVIDER_HEART_RATE

        val first = provider.publish(HealthConnectTestData.SESSION_ID, 1)
        assertEquals(HealthConnectOperationState.PARTIAL, first.state)
        assertEquals(HealthConnectErrorCode.PROVIDER_IO, first.failure?.code)
        assertNotNull(first.operationId)

        val retried = provider.retry(first.operationId!!)
        assertEquals(HealthConnectOperationState.SUCCEEDED, retried.state)
        val heart =
            ledger.records(HealthConnectTestData.SESSION_ID, 1).single {
                it.key.recordType == HealthConnectRecordMapper.PROVIDER_HEART_RATE
            }
        assertEquals(2, heart.attemptCount)
        assertEquals(HealthConnectLedgerRepository.RecordState.UPSERTED, heart.state)
        assertEquals(4, ledger.operationReceipts(HealthConnectTestData.SESSION_ID, 1)
            .count { it.key.recordType == HealthConnectRecordMapper.PROVIDER_HEART_RATE })
    }

    @Test
    fun interruptedInFlightReceiptRecoversAfterProcessDeathWithoutDuplicate() = runBlocking {
        gateway.granted += HealthConnectWritePermission.EXERCISE_SESSION.androidPermission
        val activity = HealthConnectTestData.activity(revision = 1)
        accept(activity)
        val draft =
            HealthConnectRecordMapper()
                .plan(activity, null, null, includeRoute = false)
                .drafts
                .single()
        val clientId =
            HealthConnectLedgerRepository.deterministicClientRecordId(
                HealthConnectTestData.SESSION_ID,
                draft.providerRecordType,
                draft.chunkIndex,
            )
        val version =
            ledger.allocateNextClientRecordVersion(
                HealthConnectTestData.SESSION_ID,
                clientId,
                clock.incrementAndGet(),
            )
        val record =
            ledger.prepareRecords(
                HealthConnectTestData.SESSION_ID,
                1,
                listOf(
                    HealthConnectLedgerRepository.RecordPlan(
                        draft.providerRecordType,
                        draft.chunkIndex,
                        clientId,
                        version,
                        draft.mappingSha256,
                    )
                ),
                clock.incrementAndGet(),
            ).single()
        ledger.beginOperation(
            record.key,
            version,
            HealthConnectLedgerRepository.Operation.PUBLISH,
            clock.incrementAndGet(),
        )

        val recovered = provider.publish(HealthConnectTestData.SESSION_ID, 1)

        assertEquals(HealthConnectOperationState.SUCCEEDED, recovered.state)
        val stored = ledger.records(HealthConnectTestData.SESSION_ID, 1).single()
        assertEquals(2, stored.attemptCount)
        assertEquals(HealthConnectLedgerRepository.RecordState.UPSERTED, stored.state)
        assertEquals(4, ledger.operationReceipts(HealthConnectTestData.SESSION_ID, 1).size)
        assertEquals(1, gateway.recordsByClientId.size)
    }

    @Test
    fun explicitDeleteUsesOnlyRetainedOwnedIdsAndDoesNotRequireRoutePermission() = runBlocking {
        gateway.granted += allPermissions()
        accept(activityWithEveryRecord(revision = 1))
        assertEquals(
            HealthConnectOperationState.SUCCEEDED,
            provider.publish(HealthConnectTestData.SESSION_ID, 1).state,
        )
        gateway.granted -= HealthConnectWritePermission.PHONE_EXERCISE_ROUTE.androidPermission

        val deleted = provider.delete(HealthConnectTestData.SESSION_ID, 1)

        assertEquals(HealthConnectOperationState.SUCCEEDED, deleted.state)
        assertTrue(
            ledger.ownedRecordsForSession(HealthConnectTestData.SESSION_ID).all {
                it.state == HealthConnectLedgerRepository.RecordState.DELETED
            }
        )
        assertEquals(
            HealthConnectRecordMapper.PROVIDER_EXERCISE_SESSION,
            gateway.deleteRequests.last().providerRecordType,
        )
        assertTrue(
            gateway.deleteRequests
                .flatMap { it.clientRecordIds }
                .all { it.startsWith("sl:${HealthConnectTestData.SESSION_ID}:") }
        )
        assertTrue(gateway.recordsByClientId.isEmpty())
    }

    @Test
    fun partialDeleteRemainsDurableAndRetryCompletesIt() = runBlocking {
        gateway.granted += allPermissions()
        accept(heartRateActivity(revision = 1, sampleCount = 2))
        provider.publish(HealthConnectTestData.SESSION_ID, 1)
        gateway.failNextDeleteType = HealthConnectRecordMapper.PROVIDER_HEART_RATE

        val partial = provider.delete(HealthConnectTestData.SESSION_ID, 1)
        assertEquals(HealthConnectOperationState.PARTIAL, partial.state)
        assertTrue(
            ledger.pendingDeletes(HealthConnectTestData.SESSION_ID, 1).any {
                it.key.recordType == HealthConnectRecordMapper.PROVIDER_HEART_RATE
            }
        )
        assertTrue(
            ledger.deletionReceipts(HealthConnectTestData.SESSION_ID, 1).any {
                it.result == HealthConnectLedgerRepository.DeletionResult.FAILED
            }
        )

        val completed = provider.retry(partial.operationId!!)
        assertEquals(HealthConnectOperationState.SUCCEEDED, completed.state)
        assertTrue(
            ledger.ownedRecordsForSession(HealthConnectTestData.SESSION_ID).all {
                it.state == HealthConnectLedgerRepository.RecordState.DELETED
            }
        )
    }

    private fun accept(activity: ActivityRecord) {
        val transferId =
            UUID.nameUUIDFromBytes(
                "transfer-${activity.watchRevision}".toByteArray(StandardCharsets.UTF_8)
            ).toString()
        activities.acceptActivityTransfer(
            CanonicalTransfers.activityTransfer(
                transferId,
                CanonicalPayload.create(activity),
            ).toByteArray(),
            clock.incrementAndGet(),
        )
    }

    private fun routeActivity(revision: Long): ActivityRecord =
        HealthConnectTestData.activity(
            revision = revision,
            samples =
                listOf(
                    HealthConnectTestData.sample(
                        1,
                        HealthConnectTestData.START_MS,
                        location = HealthConnectTestData.location(47.60, -122.33),
                    ),
                    HealthConnectTestData.sample(
                        2,
                        HealthConnectTestData.START_MS + 1_000,
                        location = HealthConnectTestData.location(47.61, -122.34),
                    ),
                ),
        )

    private fun heartRateActivity(
        revision: Long,
        sampleCount: Int,
    ): ActivityRecord {
        val samples =
            (0 until sampleCount).map { index ->
                HealthConnectTestData.sample(
                    sequence = index.toLong(),
                    timestampMs = HealthConnectTestData.START_MS + index,
                    heartRate =
                        HealthConnectTestData.doubleMeasurement(
                            CanonicalMeasurements.Metric.HEART_RATE_BPM,
                            100.0 + index % 20,
                        ),
                )
            }
        return HealthConnectTestData.activity(revision, samples)
    }

    private fun activityWithEveryRecord(revision: Long): ActivityRecord {
        val summary =
            HealthConnectTestData.summary(
                distanceM =
                    HealthConnectTestData.doubleMeasurement(
                        CanonicalMeasurements.Metric.DISTANCE,
                        5_000.0,
                    ),
                caloriesKcal =
                    HealthConnectTestData.doubleMeasurement(
                        CanonicalMeasurements.Metric.CALORIES,
                        500.0,
                    ),
                steps =
                    HealthConnectTestData.longMeasurement(
                        CanonicalMeasurements.Metric.STEPS,
                        6_000,
                    ),
                elevationGainM =
                    HealthConnectTestData.doubleMeasurement(
                        CanonicalMeasurements.Metric.ELEVATION_GAIN,
                        80.0,
                    ),
            )
        val samples =
            listOf(
                HealthConnectTestData.sample(
                    1,
                    HealthConnectTestData.START_MS,
                    location = HealthConnectTestData.location(47.60, -122.33),
                    heartRate =
                        HealthConnectTestData.doubleMeasurement(
                            CanonicalMeasurements.Metric.HEART_RATE_BPM,
                            120.0,
                        ),
                    speed =
                        HealthConnectTestData.doubleMeasurement(
                            CanonicalMeasurements.Metric.SPEED,
                            4.2,
                        ),
                ),
                HealthConnectTestData.sample(
                    2,
                    HealthConnectTestData.START_MS + 1_000,
                    location = HealthConnectTestData.location(47.61, -122.34),
                    heartRate =
                        HealthConnectTestData.doubleMeasurement(
                            CanonicalMeasurements.Metric.HEART_RATE_BPM,
                            121.0,
                        ),
                    speed =
                        HealthConnectTestData.doubleMeasurement(
                            CanonicalMeasurements.Metric.SPEED,
                            4.3,
                        ),
                ),
            )
        return HealthConnectTestData.activity(revision, samples, summary)
    }

    private fun allPermissions(): Set<String> =
        HealthConnectWritePermission.entries.mapTo(linkedSetOf()) { it.androidPermission }

    private class FakeHealthConnectGateway : HealthConnectGateway {
        val granted = linkedSetOf<String>()
        val recordsByClientId = linkedMapOf<String, Record>()
        val providerIdByClientId = linkedMapOf<String, String>()
        val upsertedRecords = mutableListOf<Record>()
        val deleteRequests = mutableListOf<DeleteRequest>()
        val preservedRouteUpdates = mutableListOf<String>()
        var failNextUpsertType: String? = null
        var failNextDeleteType: String? = null

        override suspend fun grantedPermissions(): Set<String> = granted.toSet()

        override suspend fun upsert(record: Record): String {
            val recordType = record::class.simpleName.orEmpty()
            if (failNextUpsertType == recordType) {
                failNextUpsertType = null
                throw IOException("simulated insert failure")
            }
            val clientId = requireNotNull(record.metadata.clientRecordId)
            val existing = recordsByClientId[clientId]
            if (
                existing is ExerciseSessionRecord &&
                    existing.exerciseRouteResult is ExerciseRouteResult.Data &&
                    record is ExerciseSessionRecord &&
                    record.exerciseRouteResult is ExerciseRouteResult.NoData &&
                    HealthConnectWritePermission.PHONE_EXERCISE_ROUTE.androidPermission !in granted
            ) {
                preservedRouteUpdates += clientId
            }
            if (
                existing == null ||
                    record.metadata.clientRecordVersion >=
                    existing.metadata.clientRecordVersion
            ) {
                recordsByClientId[clientId] = record
            }
            upsertedRecords += record
            return providerIdByClientId.getOrPut(clientId) { "provider/$clientId" }
        }

        override suspend fun delete(
            providerRecordType: String,
            providerRecordIds: List<String>,
            clientRecordIds: List<String>,
        ) {
            deleteRequests +=
                DeleteRequest(providerRecordType, providerRecordIds, clientRecordIds)
            if (failNextDeleteType == providerRecordType) {
                failNextDeleteType = null
                throw IOException("simulated delete failure")
            }
            val clientsForProviderIds =
                providerIdByClientId
                    .filterValues { it in providerRecordIds }
                    .keys
            (clientRecordIds + clientsForProviderIds).distinct().forEach { clientId ->
                recordsByClientId.remove(clientId)
                providerIdByClientId.remove(clientId)
            }
        }
    }

    private data class DeleteRequest(
        val providerRecordType: String,
        val providerRecordIds: List<String>,
        val clientRecordIds: List<String>,
    )
}
