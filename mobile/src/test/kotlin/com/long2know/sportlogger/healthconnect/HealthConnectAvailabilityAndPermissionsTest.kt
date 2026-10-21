package com.long2know.sportlogger.healthconnect

import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthConnectAvailabilityAndPermissionsTest {
    @Test
    fun api26And27AreExplicitlyUnsupported() {
        for (apiLevel in 26..27) {
            assertEquals(
                HealthConnectAvailability.UNSUPPORTED_ANDROID_VERSION,
                HealthConnectAvailabilityDetector.classify(
                    apiLevel,
                    HealthConnectClient.SDK_UNAVAILABLE,
                    providerInstalled = false,
                ),
            )
        }
    }

    @Test
    fun api28Through33DistinguishInstallUpdateAndAvailable() {
        for (apiLevel in Build.VERSION_CODES.P..Build.VERSION_CODES.TIRAMISU) {
            assertEquals(
                HealthConnectAvailability.INSTALL_REQUIRED,
                HealthConnectAvailabilityDetector.classify(
                    apiLevel,
                    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED,
                    providerInstalled = false,
                ),
            )
            assertEquals(
                HealthConnectAvailability.UPDATE_REQUIRED,
                HealthConnectAvailabilityDetector.classify(
                    apiLevel,
                    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED,
                    providerInstalled = true,
                ),
            )
            assertEquals(
                HealthConnectAvailability.AVAILABLE_APK,
                HealthConnectAvailabilityDetector.classify(
                    apiLevel,
                    HealthConnectClient.SDK_AVAILABLE,
                    providerInstalled = true,
                ),
            )
        }
    }

    @Test
    fun api34AndLaterUseFrameworkAvailability() {
        assertEquals(
            HealthConnectAvailability.AVAILABLE_FRAMEWORK,
            HealthConnectAvailabilityDetector.classify(
                Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                HealthConnectClient.SDK_AVAILABLE,
                providerInstalled = false,
            ),
        )
        assertEquals(
            HealthConnectAvailability.FRAMEWORK_UNAVAILABLE,
            HealthConnectAvailabilityDetector.classify(
                Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                HealthConnectClient.SDK_UNAVAILABLE,
                providerInstalled = true,
            ),
        )
    }

    @Test
    fun permissionStringsMatchStableHealthConnectApi() {
        assertEquals(
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            HealthConnectWritePermission.EXERCISE_SESSION.androidPermission,
        )
        assertEquals(
            HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE,
            HealthConnectWritePermission.PHONE_EXERCISE_ROUTE.androidPermission,
        )
        assertEquals(
            HealthPermission.getWritePermission(HeartRateRecord::class),
            HealthConnectWritePermission.HEART_RATE.androidPermission,
        )
        assertEquals(
            HealthPermission.getWritePermission(StepsRecord::class),
            HealthConnectWritePermission.STEPS.androidPermission,
        )
        assertEquals(
            HealthPermission.getWritePermission(DistanceRecord::class),
            HealthConnectWritePermission.DISTANCE.androidPermission,
        )
        assertEquals(
            HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
            HealthConnectWritePermission.TOTAL_CALORIES.androidPermission,
        )
        assertEquals(
            HealthPermission.getWritePermission(ElevationGainedRecord::class),
            HealthConnectWritePermission.ELEVATION_GAINED.androidPermission,
        )
        assertEquals(
            HealthPermission.getWritePermission(SpeedRecord::class),
            HealthConnectWritePermission.SPEED.androidPermission,
        )
    }

    @Test
    fun deniedPartialAndGrantedStatesRemainExact() {
        val required =
            setOf(
                HealthConnectWritePermission.EXERCISE_SESSION,
                HealthConnectWritePermission.PHONE_EXERCISE_ROUTE,
                HealthConnectWritePermission.HEART_RATE,
            )
        val denied =
            HealthConnectPermissions.status(
                HealthConnectAvailability.AVAILABLE_APK,
                required,
                emptySet(),
            )
        assertEquals(HealthConnectPermissionGrant.DENIED, denied.grant)
        assertEquals(required, denied.missing)

        val partial =
            HealthConnectPermissions.status(
                HealthConnectAvailability.AVAILABLE_APK,
                required,
                setOf(HealthConnectWritePermission.EXERCISE_SESSION.androidPermission),
            )
        assertEquals(HealthConnectPermissionGrant.PARTIAL, partial.grant)
        assertTrue(partial.hasExerciseSessionWrite)
        assertFalse(partial.hasPhoneRouteWrite)
        assertEquals(
            setOf(
                HealthConnectWritePermission.PHONE_EXERCISE_ROUTE.androidPermission,
                HealthConnectWritePermission.HEART_RATE.androidPermission,
            ),
            partial.requestInput,
        )

        val granted =
            HealthConnectPermissions.status(
                HealthConnectAvailability.AVAILABLE_FRAMEWORK,
                required,
                required.mapTo(hashSetOf()) { it.androidPermission },
            )
        assertEquals(HealthConnectPermissionGrant.GRANTED, granted.grant)
        assertTrue(granted.missing.isEmpty())
    }

    @Test
    fun launcherResultIsARequestedSubsetNotASuccessBoolean() {
        val request =
            setOf(
                HealthConnectWritePermission.EXERCISE_SESSION.androidPermission,
                HealthConnectWritePermission.PHONE_EXERCISE_ROUTE.androidPermission,
            )
        val result =
            HealthConnectPermissions.interpretRequestResult(
                request,
                setOf(HealthConnectWritePermission.EXERCISE_SESSION.androidPermission),
            )
        assertEquals(HealthConnectPermissionGrant.PARTIAL, result.grant)
        assertEquals(
            setOf(HealthConnectWritePermission.EXERCISE_SESSION),
            result.granted,
        )
        assertEquals(
            setOf(HealthConnectWritePermission.PHONE_EXERCISE_ROUTE),
            result.denied,
        )
    }
}

