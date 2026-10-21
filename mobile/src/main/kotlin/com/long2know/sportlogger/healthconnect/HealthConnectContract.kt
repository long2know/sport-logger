package com.long2know.sportlogger.healthconnect

import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import java.util.concurrent.Executor

enum class HealthConnectAvailability {
    UNSUPPORTED_ANDROID_VERSION,
    INSTALL_REQUIRED,
    UPDATE_REQUIRED,
    FRAMEWORK_UNAVAILABLE,
    AVAILABLE_APK,
    AVAILABLE_FRAMEWORK;

    val isAvailable: Boolean
        get() = this == AVAILABLE_APK || this == AVAILABLE_FRAMEWORK

    val canResolveWithProviderInstall: Boolean
        get() = this == INSTALL_REQUIRED || this == UPDATE_REQUIRED
}

enum class HealthConnectPermissionGrant {
    UNAVAILABLE,
    NOT_REQUIRED,
    DENIED,
    PARTIAL,
    GRANTED,
}

enum class HealthConnectWritePermission(val androidPermission: String) {
    EXERCISE_SESSION("android.permission.health.WRITE_EXERCISE"),
    PHONE_EXERCISE_ROUTE("android.permission.health.WRITE_EXERCISE_ROUTE"),
    HEART_RATE("android.permission.health.WRITE_HEART_RATE"),
    STEPS("android.permission.health.WRITE_STEPS"),
    DISTANCE("android.permission.health.WRITE_DISTANCE"),
    TOTAL_CALORIES("android.permission.health.WRITE_TOTAL_CALORIES_BURNED"),
    ELEVATION_GAINED("android.permission.health.WRITE_ELEVATION_GAINED"),
    SPEED("android.permission.health.WRITE_SPEED");

    companion object {
        @JvmStatic
        fun fromAndroidPermission(permission: String): HealthConnectWritePermission? =
            entries.firstOrNull { it.androidPermission == permission }
    }
}

data class HealthConnectPermissionStatus(
    val grant: HealthConnectPermissionGrant,
    val required: Set<HealthConnectWritePermission>,
    val granted: Set<HealthConnectWritePermission>,
    val missing: Set<HealthConnectWritePermission>,
) {
    val requestInput: Set<String>
        get() = missing.mapTo(linkedSetOf()) { it.androidPermission }

    val hasExerciseSessionWrite: Boolean
        get() = HealthConnectWritePermission.EXERCISE_SESSION in granted

    val hasPhoneRouteWrite: Boolean
        get() = HealthConnectWritePermission.PHONE_EXERCISE_ROUTE in granted
}

data class HealthConnectPermissionRequestResult(
    val requested: Set<HealthConnectWritePermission>,
    val granted: Set<HealthConnectWritePermission>,
    val denied: Set<HealthConnectWritePermission>,
    val grant: HealthConnectPermissionGrant,
)

enum class HealthConnectRecordType {
    EXERCISE_SESSION,
    HEART_RATE,
    STEPS,
    DISTANCE,
    TOTAL_CALORIES,
    ELEVATION_GAINED,
    SPEED,
}

enum class HealthConnectOperationKind {
    PUBLISH,
    UPDATE,
    DELETE,
}

enum class HealthConnectOperationState {
    NOT_STARTED,
    IN_PROGRESS,
    SUCCEEDED,
    PARTIAL,
    BLOCKED,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
}

enum class HealthConnectRecordState {
    PLANNED,
    WRITTEN,
    DELETE_PENDING,
    DELETED,
    SKIPPED_PERMISSION,
    FAILED_RETRYABLE,
    FAILED_PERMANENT,
}

enum class HealthConnectErrorCode {
    ACTIVITY_NOT_FOUND,
    INVALID_CANONICAL_ACTIVITY,
    INVALID_PROVIDER_RECORD,
    PROVIDER_UNAVAILABLE,
    PERMISSION_DENIED,
    PERMISSION_REVOKED,
    PROVIDER_IO,
    PROVIDER_PROTOCOL,
    PROVIDER_FAILURE,
    LEDGER_CONFLICT,
}

data class HealthConnectFailure(
    val code: HealthConnectErrorCode,
    val message: String,
    val retryable: Boolean,
)

data class HealthConnectRecordReceipt(
    val recordType: HealthConnectRecordType,
    val chunkIndex: Int,
    val clientRecordId: String,
    val clientRecordVersion: Long,
    val providerRecordId: String?,
    val state: HealthConnectRecordState,
    val failure: HealthConnectFailure?,
)

data class HealthConnectOperationResult(
    val operationId: String?,
    val kind: HealthConnectOperationKind,
    val sessionId: String,
    val revision: Long,
    val state: HealthConnectOperationState,
    val receipts: List<HealthConnectRecordReceipt>,
    val missingPermissions: Set<HealthConnectWritePermission>,
    val failure: HealthConnectFailure?,
) {
    val canRetry: Boolean
        get() =
            operationId != null &&
                (state == HealthConnectOperationState.PARTIAL ||
                    state == HealthConnectOperationState.RETRYABLE_FAILURE)
}

data class HealthConnectSessionStatus(
    val sessionId: String,
    val revision: Long,
    val availability: HealthConnectAvailability,
    val permissions: HealthConnectPermissionStatus,
    val presentRecordTypes: Set<HealthConnectRecordType>,
    val routePresent: Boolean,
    val lastOperation: HealthConnectOperationResult?,
) {
    val canResolveProvider: Boolean
        get() = availability.canResolveWithProviderInstall

    val canRequestPermissions: Boolean
        get() = availability.isAvailable && permissions.missing.isNotEmpty()

    val canPublish: Boolean
        get() = availability.isAvailable && permissions.hasExerciseSessionWrite

    val canUpdate: Boolean
        get() = canPublish

    val canDelete: Boolean
        get() =
            availability.isAvailable &&
                lastOperation?.receipts.orEmpty().any {
                    it.state != HealthConnectRecordState.DELETED
                }

    val canRetry: Boolean
        get() = lastOperation?.canRetry == true
}

interface HealthConnectProvider : AutoCloseable {
    fun permissionRequestContract(): ActivityResultContract<Set<String>, Set<String>>

    fun providerResolutionIntent(availability: HealthConnectAvailability): Intent?

    suspend fun refreshStatus(
        sessionId: String,
        revision: Long,
    ): HealthConnectSessionStatus

    suspend fun publish(
        sessionId: String,
        revision: Long,
    ): HealthConnectOperationResult

    suspend fun update(
        sessionId: String,
        revision: Long,
    ): HealthConnectOperationResult

    suspend fun delete(
        sessionId: String,
        revision: Long,
    ): HealthConnectOperationResult

    suspend fun retry(operationId: String): HealthConnectOperationResult

    /**
     * Observes canonical Room changes only. Call [refreshStatus] after this callback, after every
     * permission launcher result, whenever the Activity resumes, and after every provider
     * operation. Health Connect permission changes are not pushed to this callback.
     */
    fun observeCanonicalChanges(
        executor: Executor,
        listener: Runnable,
    ): AutoCloseable
}

