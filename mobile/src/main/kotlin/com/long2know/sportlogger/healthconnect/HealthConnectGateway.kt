package com.long2know.sportlogger.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import kotlin.reflect.KClass

internal interface HealthConnectGateway {
    suspend fun grantedPermissions(): Set<String>

    suspend fun upsert(record: Record): String

    suspend fun delete(
        providerRecordType: String,
        providerRecordIds: List<String>,
        clientRecordIds: List<String>,
    )
}

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
