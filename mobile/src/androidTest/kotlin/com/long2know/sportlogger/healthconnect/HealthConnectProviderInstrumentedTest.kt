package com.long2know.sportlogger.healthconnect

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Length
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HealthConnectProviderInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun realProviderPermissionProjectionAndRequestContract() = runBlocking {
        val sdkStatus = HealthConnectClient.getSdkStatus(context)
        println(
            "health_connect_device api=${Build.VERSION.SDK_INT} sdkStatus=$sdkStatus"
        )
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
            assertTrue(
                sdkStatus == HealthConnectClient.SDK_UNAVAILABLE ||
                    sdkStatus ==
                    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
            )
            return@runBlocking
        }

        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        val required =
            setOf(
                HealthConnectWritePermission.EXERCISE_SESSION,
                HealthConnectWritePermission.PHONE_EXERCISE_ROUTE,
            )
        val projection =
            HealthConnectPermissions.status(
                availability =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        HealthConnectAvailability.AVAILABLE_FRAMEWORK
                    } else {
                        HealthConnectAvailability.AVAILABLE_APK
                    },
                required = required,
                grantedAndroidPermissions = granted,
            )
        val readProjection =
            HealthConnectPermissions.readStatus(
                availability =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        HealthConnectAvailability.AVAILABLE_FRAMEWORK
                    } else {
                        HealthConnectAvailability.AVAILABLE_APK
                    },
                grantedAndroidPermissions = granted,
            )

        assertEquals(
            HealthConnectWritePermission.EXERCISE_SESSION.androidPermission in granted,
            projection.hasExerciseSessionWrite,
        )
        assertEquals(
            HealthConnectWritePermission.PHONE_EXERCISE_ROUTE.androidPermission in granted,
            projection.hasPhoneRouteWrite,
        )
        assertEquals(required - projection.granted, projection.missing)
        println(
            "health_connect_permissions exerciseWrite=${projection.hasExerciseSessionWrite} " +
                "routeWrite=${projection.hasPhoneRouteWrite}"
        )
        println(
            "health_connect_read_permissions " +
                "exerciseRead=${readProjection.hasExerciseSessionRead}"
        )

        val contract = PermissionController.createRequestPermissionResultContract()
        val intent =
            contract.createIntent(
                context,
                (projection.requestInput + readProjection.requestInput)
                    .ifEmpty {
                        required.mapTo(linkedSetOf()) { it.androidPermission } +
                            HealthConnectReadPermission.EXERCISE_SESSION.androidPermission
                    },
            )
        assertNotNull(intent)
    }

    @Test
    fun realProviderEnforcesPermissionOrCompletesOwnedLifecycle() = runBlocking {
        val sdkStatus = HealthConnectClient.getSdkStatus(context)
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
            println("health_connect_provider_gate sdkStatus=$sdkStatus")
            return@runBlocking
        }

        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        val exercisePermission =
            HealthPermission.getWritePermission(ExerciseSessionRecord::class)
        val routePermission = HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE
        val readPermission =
            HealthPermission.getReadPermission(ExerciseSessionRecord::class)
        val clientRecordId = "sportlogger-instrumented-${UUID.randomUUID()}"
        val start = Instant.now().minusSeconds(300)
        val end = Instant.now().minusSeconds(60)
        val route =
            if (routePermission in granted) {
                ExerciseRoute(
                    listOf(
                        ExerciseRoute.Location(
                            time = start.plusSeconds(30),
                            latitude = 47.6062,
                            longitude = -122.3321,
                            horizontalAccuracy = Length.meters(3.0),
                        ),
                        ExerciseRoute.Location(
                            time = start.plusSeconds(90),
                            latitude = 47.6070,
                            longitude = -122.3310,
                            horizontalAccuracy = Length.meters(2.0),
                        ),
                    )
                )
            } else {
                null
            }
        val versionOne =
            exerciseRecord(
                clientRecordId = clientRecordId,
                clientRecordVersion = 1,
                start = start,
                end = end,
                route = route,
                title = "SportLogger synthetic Health Connect test",
            )

        if (exercisePermission !in granted) {
            val rejection =
                try {
                    client.insertRecords(listOf(versionOne))
                    null
                } catch (error: Throwable) {
                    error
                }
            assertNotNull("Write without Health Connect permission must fail", rejection)
            assertTrue(
                "Expected a real platform permission rejection, got ${rejection?.javaClass}",
                rejection is SecurityException,
            )
            println(
                "health_connect_permission_gate exerciseWrite=false " +
                    "rejection=${rejection?.javaClass?.simpleName}"
            )
            assertFalse(clientRecordId.isEmpty())
            return@runBlocking
        }

        val insertedIds = mutableListOf<String>()
        try {
            val first = client.insertRecords(listOf(versionOne))
            assertEquals(1, first.recordIdsList.size)
            assertTrue(first.recordIdsList.single().isNotEmpty())
            insertedIds += first.recordIdsList

            val versionTwo =
                exerciseRecord(
                    clientRecordId = clientRecordId,
                    clientRecordVersion = 2,
                    start = start,
                    end = end,
                    route = route,
                    title = "SportLogger synthetic Health Connect test updated",
                )
            val updated = client.insertRecords(listOf(versionTwo))
            assertEquals(1, updated.recordIdsList.size)
            assertTrue(updated.recordIdsList.single().isNotEmpty())
            insertedIds += updated.recordIdsList

            if (readPermission in granted) {
                val readBack =
                    client.readRecord(
                        ExerciseSessionRecord::class,
                        updated.recordIdsList.single(),
                    ).record
                assertEquals(clientRecordId, readBack.metadata.clientRecordId)
                assertEquals(2L, readBack.metadata.clientRecordVersion)
                assertEquals(
                    "SportLogger synthetic Health Connect test updated",
                    readBack.title,
                )
                if (route != null) {
                    assertTrue(readBack.exerciseRouteResult is ExerciseRouteResult.Data)
                }
            } else {
                println("health_connect_query_gate exerciseRead=false")
            }

            client.deleteRecords(
                recordType = ExerciseSessionRecord::class,
                recordIdsList = insertedIds.distinct(),
                clientRecordIdsList = listOf(clientRecordId),
            )
            if (readPermission in granted) {
                val remaining =
                    client.readRecords(
                        ReadRecordsRequest(
                            recordType = ExerciseSessionRecord::class,
                            timeRangeFilter =
                                TimeRangeFilter.between(
                                    start.minusSeconds(1),
                                    end.plusSeconds(1),
                                ),
                            dataOriginFilter = setOf(DataOrigin(context.packageName)),
                        )
                    ).records
                assertTrue(
                    remaining.none { it.metadata.clientRecordId == clientRecordId }
                )
            }
            println(
                "health_connect_owned_lifecycle inserted=${insertedIds.size} " +
                    "routeIncluded=${route != null} " +
                    "queryVerified=${readPermission in granted} deleted=true"
            )
        } finally {
            runCatching {
                client.deleteRecords(
                    recordType = ExerciseSessionRecord::class,
                    recordIdsList = insertedIds.distinct(),
                    clientRecordIdsList = listOf(clientRecordId),
                )
            }
        }
    }

    private fun exerciseRecord(
        clientRecordId: String,
        clientRecordVersion: Long,
        start: Instant,
        end: Instant,
        route: ExerciseRoute?,
        title: String,
    ): ExerciseSessionRecord =
        ExerciseSessionRecord(
            startTime = start,
            startZoneOffset = ZoneOffset.UTC,
            endTime = end,
            endZoneOffset = ZoneOffset.UTC,
            metadata =
                Metadata.activelyRecorded(
                    device = Device(type = Device.TYPE_PHONE),
                    clientRecordId = clientRecordId,
                    clientRecordVersion = clientRecordVersion,
                ),
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            title = title,
            notes = "Synthetic instrumentation data; safe to delete",
            exerciseRoute = route,
        )
}
