package com.long2know.sportlogger.healthconnect

import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.PermissionController

object HealthConnectPermissions {
    @JvmStatic
    fun requestContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    @JvmStatic
    fun status(
        availability: HealthConnectAvailability,
        required: Set<HealthConnectWritePermission>,
        grantedAndroidPermissions: Set<String>,
    ): HealthConnectPermissionStatus {
        if (!availability.isAvailable) {
            return HealthConnectPermissionStatus(
                grant = HealthConnectPermissionGrant.UNAVAILABLE,
                required = required.toSet(),
                granted = emptySet(),
                missing = required.toSet(),
            )
        }
        if (required.isEmpty()) {
            return HealthConnectPermissionStatus(
                grant = HealthConnectPermissionGrant.NOT_REQUIRED,
                required = emptySet(),
                granted = emptySet(),
                missing = emptySet(),
            )
        }
        val granted =
            required.filterTo(linkedSetOf()) {
                it.androidPermission in grantedAndroidPermissions
            }
        val missing = required.filterTo(linkedSetOf()) { it !in granted }
        val grant =
            when {
                missing.isEmpty() -> HealthConnectPermissionGrant.GRANTED
                granted.isEmpty() -> HealthConnectPermissionGrant.DENIED
                else -> HealthConnectPermissionGrant.PARTIAL
            }
        return HealthConnectPermissionStatus(
            grant = grant,
            required = required.toSet(),
            granted = granted,
            missing = missing,
        )
    }

    @JvmStatic
    fun readStatus(
        availability: HealthConnectAvailability,
        grantedAndroidPermissions: Set<String>,
    ): HealthConnectReadPermissionStatus {
        val required = HealthConnectReadPermission.entries.toSet()
        if (!availability.isAvailable) {
            return HealthConnectReadPermissionStatus(
                grant = HealthConnectPermissionGrant.UNAVAILABLE,
                required = required,
                granted = emptySet(),
                missing = required,
            )
        }
        val granted =
            required.filterTo(linkedSetOf()) {
                it.androidPermission in grantedAndroidPermissions
            }
        val missing = required.filterTo(linkedSetOf()) { it !in granted }
        return HealthConnectReadPermissionStatus(
            grant =
                when {
                    missing.isEmpty() -> HealthConnectPermissionGrant.GRANTED
                    granted.isEmpty() -> HealthConnectPermissionGrant.DENIED
                    else -> HealthConnectPermissionGrant.PARTIAL
                },
            required = required,
            granted = granted,
            missing = missing,
        )
    }

    @JvmStatic
    fun interpretRequestResult(
        requestedAndroidPermissions: Set<String>,
        grantedAndroidPermissions: Set<String>,
    ): HealthConnectPermissionRequestResult {
        val requested =
            requestedAndroidPermissions.mapNotNullTo(linkedSetOf()) {
                HealthConnectWritePermission.fromAndroidPermission(it)
            }
        val granted =
            requested.filterTo(linkedSetOf()) {
                it.androidPermission in grantedAndroidPermissions
            }
        val denied = requested.filterTo(linkedSetOf()) { it !in granted }
        val readRequested =
            requestedAndroidPermissions.mapNotNullTo(linkedSetOf()) {
                HealthConnectReadPermission.fromAndroidPermission(it)
            }
        val readGranted =
            readRequested.filterTo(linkedSetOf()) {
                it.androidPermission in grantedAndroidPermissions
            }
        val readDenied = readRequested.filterTo(linkedSetOf()) { it !in readGranted }
        val grant =
            when {
                requested.isEmpty() && readRequested.isEmpty() ->
                    HealthConnectPermissionGrant.NOT_REQUIRED
                denied.isEmpty() && readDenied.isEmpty() ->
                    HealthConnectPermissionGrant.GRANTED
                granted.isEmpty() && readGranted.isEmpty() ->
                    HealthConnectPermissionGrant.DENIED
                else -> HealthConnectPermissionGrant.PARTIAL
            }
        return HealthConnectPermissionRequestResult(
            requested = requested,
            granted = granted,
            denied = denied,
            readRequested = readRequested,
            readGranted = readGranted,
            readDenied = readDenied,
            grant = grant,
        )
    }
}
