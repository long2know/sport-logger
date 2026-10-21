package com.long2know.sportlogger.healthconnect

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
import java.time.Instant
import kotlin.reflect.KClass

internal interface HealthConnectGateway {
    suspend fun grantedPermissions(): Set<String>

    suspend fun upsert(record: Record): String

    suspend fun delete(
        providerRecordType: String,
        providerRecordIds: List<String>,
        clientRecordIds: List<String>,
    )

    suspend fun readExerciseSession(providerRecordId: String): HealthConnectExerciseSnapshot

    suspend fun readOwnedExerciseSessions(
        startTime: Instant,
        endTime: Instant,
        packageName: String,
    ): List<HealthConnectExerciseSnapshot>
}

internal data class HealthConnectExerciseSnapshot(
    val providerRecordId: String,
    val clientRecordId: String?,
    val clientRecordVersion: Long,
    val dataOriginPackageName: String,
    val routePointCount: Int?,
)

internal class AndroidHealthConnectGateway(
    private val client: HealthConnectClient,
) : HealthConnectGateway {
    override suspend fun grantedPermissions(): Set<String> =
        client.permissionController.getGrantedPermissions()

    override suspend fun upsert(record: Record): String {
        val response = client.insertRecords(listOf(record))
        val id = response.recordIdsList.singleOrNull()
        if (id.isNullOrEmpty()) {
            throw HealthConnectProviderProtocolException(
                "Health Connect returned no opaque record ID for one inserted record"
            )
        }
        return id
    }

    override suspend fun delete(
        providerRecordType: String,
        providerRecordIds: List<String>,
        clientRecordIds: List<String>,
    ) {
        client.deleteRecords(
            recordType = recordClass(providerRecordType),
            recordIdsList = providerRecordIds.filter(String::isNotEmpty).distinct(),
            clientRecordIdsList = clientRecordIds.filter(String::isNotEmpty).distinct(),
        )
    }

    override suspend fun readExerciseSession(
        providerRecordId: String,
    ): HealthConnectExerciseSnapshot =
        client.readRecord(ExerciseSessionRecord::class, providerRecordId).record.toSnapshot()

    override suspend fun readOwnedExerciseSessions(
        startTime: Instant,
        endTime: Instant,
        packageName: String,
    ): List<HealthConnectExerciseSnapshot> {
        val records = mutableListOf<HealthConnectExerciseSnapshot>()
        var pageToken: String? = null
        do {
            val response =
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = ExerciseSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                        dataOriginFilter = setOf(DataOrigin(packageName)),
                        pageToken = pageToken,
                    )
                )
            records += response.records.map { it.toSnapshot() }
            pageToken = response.pageToken
        } while (pageToken != null)
        return records
    }

    private fun ExerciseSessionRecord.toSnapshot(): HealthConnectExerciseSnapshot =
        HealthConnectExerciseSnapshot(
            providerRecordId = metadata.id,
            clientRecordId = metadata.clientRecordId,
            clientRecordVersion = metadata.clientRecordVersion,
            dataOriginPackageName = metadata.dataOrigin.packageName,
            routePointCount =
                when (val result = exerciseRouteResult) {
                    is ExerciseRouteResult.Data -> result.exerciseRoute.route.size
                    is ExerciseRouteResult.NoData -> 0
                    is ExerciseRouteResult.ConsentRequired -> null
                    else -> null
                },
        )

    private fun recordClass(providerRecordType: String): KClass<out Record> =
        when (providerRecordType) {
            HealthConnectRecordMapper.PROVIDER_EXERCISE_SESSION -> ExerciseSessionRecord::class
            HealthConnectRecordMapper.PROVIDER_HEART_RATE -> HeartRateRecord::class
            HealthConnectRecordMapper.PROVIDER_STEPS -> StepsRecord::class
            HealthConnectRecordMapper.PROVIDER_DISTANCE -> DistanceRecord::class
            HealthConnectRecordMapper.PROVIDER_TOTAL_CALORIES ->
                TotalCaloriesBurnedRecord::class
            HealthConnectRecordMapper.PROVIDER_ELEVATION_GAINED ->
                ElevationGainedRecord::class
            HealthConnectRecordMapper.PROVIDER_SPEED -> SpeedRecord::class
            else ->
                throw HealthConnectProviderProtocolException(
                    "Unknown owned Health Connect record type: $providerRecordType"
                )
        }
}

internal class HealthConnectProviderProtocolException(
    message: String,
) : IllegalStateException(message)
