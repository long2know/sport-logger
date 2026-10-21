package com.long2know.sportlogger.healthconnect.testing

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import com.long2know.sportlogger.healthconnect.HealthConnectSessionStatus

enum class AuthorizedHealthConnectReadPermission(val androidPermission: String) {
    EXERCISE(HealthPermission.getReadPermission(ExerciseSessionRecord::class)),
    HEART_RATE(HealthPermission.getReadPermission(HeartRateRecord::class)),
    STEPS(HealthPermission.getReadPermission(StepsRecord::class)),
    DISTANCE(HealthPermission.getReadPermission(DistanceRecord::class)),
    TOTAL_CALORIES(
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)
    ),
    ELEVATION_GAINED(
        HealthPermission.getReadPermission(ElevationGainedRecord::class)
    ),
    SPEED(HealthPermission.getReadPermission(SpeedRecord::class)),
}

object AuthorizedHealthConnectTestContract {
    @JvmStatic
    fun permissionRequestInput(
        status: HealthConnectSessionStatus,
        grantedAndroidPermissions: Set<String>,
    ): Set<String> =
        status.permissions.requestInput +
            AuthorizedHealthConnectReadPermission.entries
                .map { it.androidPermission }
                .filterNotTo(linkedSetOf()) { it in grantedAndroidPermissions }

    @JvmStatic
    fun requiredReadPermissions(): Set<String> =
        AuthorizedHealthConnectReadPermission.entries
            .mapTo(linkedSetOf()) { it.androidPermission }
}
