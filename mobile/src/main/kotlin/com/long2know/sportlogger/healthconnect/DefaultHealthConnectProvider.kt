package com.long2know.sportlogger.healthconnect

import android.content.Context
import android.content.Intent
import android.os.RemoteException
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import com.long2know.sportlogger.persistence.phone.HealthConnectLedgerRepository
import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository
import com.long2know.utilities.contract.CanonicalContractException
import com.long2know.utilities.contract.proto.Quality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.util.concurrent.Executor

object HealthConnectProviderFactory {
    @JvmStatic
    fun create(context: Context): HealthConnectProvider {
        val applicationContext = context.applicationContext
        val availabilityDetector = HealthConnectAvailabilityDetector(applicationContext)
        return DefaultHealthConnectProvider(
            activities = PhoneActivityRepository.open(applicationContext),
            ledger = HealthConnectLedgerRepository.open(applicationContext),
            availability = availabilityDetector::availability,
            resolutionIntent = availabilityDetector::providerResolutionIntent,
            gatewayFactory = {
                AndroidHealthConnectGateway(
                    HealthConnectClient.getOrCreate(applicationContext)
                )
            },
            mapper = HealthConnectRecordMapper(),
            nowUtcMs = System::currentTimeMillis,
            packageName = applicationContext.packageName,
        )
    }
}

internal class DefaultHealthConnectProvider(
    private val activities: PhoneActivityRepository,
    private val ledger: HealthConnectLedgerRepository,
    private val availability: () -> HealthConnectAvailability,
    private val resolutionIntent: (HealthConnectAvailability) -> Intent?,
    private val gatewayFactory: () -> HealthConnectGateway,
    private val mapper: HealthConnectRecordMapper,
    private val nowUtcMs: () -> Long,
    private val packageName: String,
) : HealthConnectProvider {
    private val operationMutex = Mutex()
    private var healthConnectGateway: HealthConnectGateway? = null

    override fun permissionRequestContract(): ActivityResultContract<Set<String>, Set<String>> =
        HealthConnectPermissions.requestContract()

    override fun providerResolutionIntent(
        availability: HealthConnectAvailability,
    ): Intent? = resolutionIntent(availability)

    override suspend fun refreshStatus(
        sessionId: String,
        revision: Long,
    ): HealthConnectSessionStatus =
        exclusive {
            recoverInterrupted(sessionId)
            statusLocked(sessionId, revision)
        }

    override suspend fun publish(
        sessionId: String,
        revision: Long,
    ): HealthConnectOperationResult =
        exclusive {
            upsertLocked(HealthConnectOperationKind.PUBLISH, sessionId, revision)
        }

    override suspend fun update(
        sessionId: String,
        revision: Long,
    ): HealthConnectOperationResult =
        exclusive {
            upsertLocked(HealthConnectOperationKind.UPDATE, sessionId, revision)
        }

    override suspend fun delete(
        sessionId: String,
        revision: Long,
    ): HealthConnectOperationResult =
        exclusive {
            deleteLocked(sessionId, revision)
        }

    override suspend fun retry(operationId: String): HealthConnectOperationResult =
        exclusive {
            val token = RetryToken.parse(operationId)
            when (token.kind) {
                HealthConnectOperationKind.PUBLISH,
                HealthConnectOperationKind.UPDATE,
                -> upsertLocked(token.kind, token.sessionId, token.revision)
                HealthConnectOperationKind.DELETE ->
                    deleteLocked(token.sessionId, token.revision)
            }
        }

    override suspend fun verifyOwnedExerciseSession(
        sessionId: String,
        revision: Long,
    ): HealthConnectVerificationResult =
        exclusive {
            verifyOwnedExerciseSessionLocked(sessionId, revision)
        }

    override fun observeCanonicalChanges(
        executor: Executor,
        listener: Runnable,
    ): AutoCloseable {
        val activityObserver = activities.observeChanges(executor, listener)
        val ledgerObserver = ledger.observeChanges(executor, listener)
        return AutoCloseable {
            ledgerObserver.close()
            activityObserver.close()
        }
    }

    override fun close() {
        ledger.close()
        activities.close()
    }

    private suspend fun statusLocked(
        sessionId: String,
        revision: Long,
    ): HealthConnectSessionStatus {
        val availability = availability()
        val detail = activities.detail(sessionId, revision)
        val owned = ledger.ownedRecordsForSession(sessionId)
        val fullPlan =
            detail?.let {
                mapper.plan(
                    activity = it.activity,
                    phoneTitle = it.metadata.title,
                    phoneNotes = it.metadata.notes,
                    includeRoute = true,
                )
            }
        val required =
            if (fullPlan == null) {
                ownedRequiredPermissions(owned)
            } else {
                requiredPermissions(sessionId, revision, fullPlan, owned)
            }
        val grantedPermissions =
            if (availability.isAvailable) {
                try {
                    gateway().grantedPermissions()
                } catch (_: Exception) {
                    null
                }
            } else {
                emptySet()
            }
        val permissionStatus =
            if (grantedPermissions == null) {
                HealthConnectPermissionStatus(
                    grant = HealthConnectPermissionGrant.UNAVAILABLE,
                    required = required,
                    granted = emptySet(),
                    missing = required,
                )
            } else {
                HealthConnectPermissions.status(
                    availability = availability,
                    required = required,
                    grantedAndroidPermissions = grantedPermissions,
                )
            }
        val readPermissionStatus =
            if (grantedPermissions == null) {
                HealthConnectReadPermissionStatus(
                    grant = HealthConnectPermissionGrant.UNAVAILABLE,
                    required = HealthConnectReadPermission.entries.toSet(),
                    granted = emptySet(),
                    missing = HealthConnectReadPermission.entries.toSet(),
                )
            } else {
                HealthConnectPermissions.readStatus(
                    availability = availability,
                    grantedAndroidPermissions = grantedPermissions,
                )
            }
        return HealthConnectSessionStatus(
            sessionId = sessionId,
            revision = revision,
            availability = availability,
            permissions = permissionStatus,
            readPermissions = readPermissionStatus,
            presentRecordTypes = fullPlan?.presentRecordTypes.orEmpty(),
            routePresent = fullPlan?.routePresent == true,
            lastOperation =
                lastOperationResult(
                    sessionId = sessionId,
                    revision = revision,
                    missingPermissions = permissionStatus.missing,
                ),
        )
    }

    private suspend fun verifyOwnedExerciseSessionLocked(
        sessionId: String,
        revision: Long,
    ): HealthConnectVerificationResult {
        recoverInterrupted(sessionId)
        val providerAvailability = availability()
        if (!providerAvailability.isAvailable) {
            return verificationBlocked(
                sessionId,
                revision,
                HealthConnectFailure(
                    HealthConnectErrorCode.PROVIDER_UNAVAILABLE,
                    "Health Connect is not available",
                    retryable = true,
                ),
            )
        }
        val requestedDetail =
            activities.detail(sessionId, revision)
                ?: return verificationBlocked(
                    sessionId,
                    revision,
                    HealthConnectFailure(
                        HealthConnectErrorCode.ACTIVITY_NOT_FOUND,
                        "The canonical activity revision is unavailable",
                        retryable = false,
                    ),
                )
        val granted =
            try {
                gateway().grantedPermissions()
            } catch (error: Exception) {
                return verificationFailed(sessionId, revision, classify(error))
            }
        if (
            HealthConnectReadPermission.EXERCISE_SESSION.androidPermission !in granted
        ) {
            return verificationBlocked(
                sessionId,
                revision,
                HealthConnectFailure(
                    HealthConnectErrorCode.PERMISSION_DENIED,
                    "Exercise session read permission is required for provider verification",
                    retryable = true,
                ),
            )
        }
        val exerciseRecords =
            ledger.ownedRecordsForSession(sessionId)
                .filter {
                    it.key.recordType ==
                        HealthConnectRecordMapper.PROVIDER_EXERCISE_SESSION
                }
        val target =
            exerciseRecords
                .filter { it.key.revision == revision }
                .maxByOrNull { it.clientRecordVersion }
                ?: return verificationBlocked(
                    sessionId,
                    revision,
                    HealthConnectFailure(
                        HealthConnectErrorCode.PROVIDER_VERIFICATION,
                        "No owned Health Connect exercise receipt exists for this revision",
                        retryable = false,
                    ),
                )
        return try {
            if (
                exerciseRecords.isNotEmpty() &&
                    exerciseRecords.all {
                        it.state == HealthConnectLedgerRepository.RecordState.DELETED
                    }
            ) {
                val details =
                    exerciseRecords
                        .mapNotNull { activities.detail(sessionId, it.key.revision) }
                        .ifEmpty { listOf(requestedDetail) }
                val start =
                    Instant.ofEpochMilli(
                        details.minOf { it.activity.startTimeUtcMs }
                    ).minusMillis(1)
                val end =
                    Instant.ofEpochMilli(
                        details.maxOf { it.activity.endTimeUtcMs }
                    ).plusMillis(1)
                val clientIds = exerciseRecords.mapTo(hashSetOf()) { it.clientRecordId }
                val providerIds =
                    exerciseRecords
                        .flatMap {
                            it.ownedProviderRecordIds +
                                listOfNotNull(it.providerRecordId)
                        }
                        .toHashSet()
                val remaining =
                    gateway().readOwnedExerciseSessions(start, end, packageName)
                        .filter {
                            it.clientRecordId in clientIds ||
                                it.providerRecordId in providerIds
                        }
                if (remaining.isEmpty()) {
                    verificationResult(
                        sessionId,
                        revision,
                        HealthConnectVerificationState.ABSENT,
                        target,
                        providerDataOrigin = null,
                        providerRoutePointCount = null,
                        failure = null,
                    )
                } else {
                    verificationResult(
                        sessionId,
                        revision,
                        HealthConnectVerificationState.MISMATCH,
                        target,
                        providerDataOrigin =
                            remaining.first().dataOriginPackageName,
                        providerRoutePointCount =
                            remaining.first().routePointCount,
                        failure =
                            HealthConnectFailure(
                                HealthConnectErrorCode.PROVIDER_VERIFICATION,
                                "An app-owned Health Connect exercise record remains after delete",
                                retryable = true,
                            ),
                    )
                }
            } else {
                val providerRecordId = target.providerRecordId
                if (
                    target.state != HealthConnectLedgerRepository.RecordState.UPSERTED ||
                        providerRecordId.isNullOrEmpty()
                ) {
                    return verificationBlocked(
                        sessionId,
                        revision,
                        HealthConnectFailure(
                            HealthConnectErrorCode.PROVIDER_VERIFICATION,
                            "The exercise record has not completed a provider upsert",
                            retryable = true,
                        ),
                    )
                }
                val providerRecord =
                    gateway().readExerciseSession(providerRecordId)
                val matches =
                    providerRecord.providerRecordId == providerRecordId &&
                        providerRecord.clientRecordId == target.clientRecordId &&
                        providerRecord.clientRecordVersion ==
                        target.clientRecordVersion &&
                        providerRecord.dataOriginPackageName == packageName
                verificationResult(
                    sessionId,
                    revision,
                    if (matches) {
                        HealthConnectVerificationState.PRESENT
                    } else {
                        HealthConnectVerificationState.MISMATCH
                    },
                    target,
                    providerDataOrigin =
                        providerRecord.dataOriginPackageName,
                    providerRoutePointCount = providerRecord.routePointCount,
                    failure =
                        if (matches) {
                            null
                        } else {
                            HealthConnectFailure(
                                HealthConnectErrorCode.PROVIDER_VERIFICATION,
                                "Health Connect read-back does not match the owned receipt",
                                retryable = false,
                            )
                        },
                )
            }
        } catch (error: Exception) {
            verificationFailed(sessionId, revision, classify(error), target)
        }
    }

    private fun verificationBlocked(
        sessionId: String,
        revision: Long,
        failure: HealthConnectFailure,
    ): HealthConnectVerificationResult =
        HealthConnectVerificationResult(
            sessionId = sessionId,
            revision = revision,
            state = HealthConnectVerificationState.BLOCKED,
            clientRecordId = null,
            clientRecordVersion = null,
            providerRecordId = null,
            providerDataOrigin = null,
            providerRoutePointCount = null,
            failure = failure,
        )

    private fun verificationFailed(
        sessionId: String,
        revision: Long,
        failure: HealthConnectFailure,
        record: HealthConnectLedgerRepository.LedgerRecord? = null,
    ): HealthConnectVerificationResult =
        verificationResult(
            sessionId = sessionId,
            revision = revision,
            state = HealthConnectVerificationState.FAILED,
            record = record,
            providerDataOrigin = null,
            providerRoutePointCount = null,
            failure = failure,
        )

    private fun verificationResult(
        sessionId: String,
        revision: Long,
        state: HealthConnectVerificationState,
        record: HealthConnectLedgerRepository.LedgerRecord?,
        providerDataOrigin: String?,
        providerRoutePointCount: Int?,
        failure: HealthConnectFailure?,
    ): HealthConnectVerificationResult =
        HealthConnectVerificationResult(
            sessionId = sessionId,
            revision = revision,
            state = state,
            clientRecordId = record?.clientRecordId,
            clientRecordVersion = record?.clientRecordVersion,
            providerRecordId = record?.providerRecordId,
            providerDataOrigin = providerDataOrigin,
            providerRoutePointCount = providerRoutePointCount,
            failure = failure,
        )

    private suspend fun upsertLocked(
        requestedKind: HealthConnectOperationKind,
        sessionId: String,
        revision: Long,
    ): HealthConnectOperationResult {
        recoverInterrupted(sessionId)
        val retryToken = RetryToken(requestedKind, sessionId, revision).value
        val providerAvailability = availability()
        if (!providerAvailability.isAvailable) {
            return blockedResult(
                operationId = retryToken,
                kind = requestedKind,
                sessionId = sessionId,
                revision = revision,
                failure =
                    HealthConnectFailure(
                        HealthConnectErrorCode.PROVIDER_UNAVAILABLE,
                        "Health Connect is not available",
                        retryable = true,
                    ),
            )
        }
        val detail =
            activities.detail(sessionId, revision)
                ?: return blockedResult(
                    operationId = retryToken,
                    kind = requestedKind,
                    sessionId = sessionId,
                    revision = revision,
                    failure =
                        HealthConnectFailure(
                            HealthConnectErrorCode.ACTIVITY_NOT_FOUND,
                            "The canonical activity revision is unavailable",
                            retryable = false,
                        ),
                )

        val fullPlan =
            try {
                mapper.plan(
                    activity = detail.activity,
                    phoneTitle = detail.metadata.title,
                    phoneNotes = detail.metadata.notes,
                    includeRoute = true,
                )
            } catch (error: Exception) {
                return blockedResult(
                    operationId = retryToken,
                    kind = requestedKind,
                    sessionId = sessionId,
                    revision = revision,
                    failure = classify(error),
                )
            }
        val grantedAndroidPermissions =
            try {
                gateway().grantedPermissions()
            } catch (error: Exception) {
                return blockedResult(
                    operationId = retryToken,
                    kind = requestedKind,
                    sessionId = sessionId,
                    revision = revision,
                    failure = classify(error),
                )
            }
        val ownedBefore = ledger.ownedRecordsForSession(sessionId)
        val required =
            requiredPermissions(
                sessionId = sessionId,
                revision = revision,
                fullPlan = fullPlan,
                owned = ownedBefore,
            )
        val permissions =
            HealthConnectPermissions.status(
                availability = providerAvailability,
                required = required,
                grantedAndroidPermissions = grantedAndroidPermissions,
            )
        if (!permissions.hasExerciseSessionWrite) {
            return blockedResult(
                operationId = retryToken,
                kind = requestedKind,
                sessionId = sessionId,
                revision = revision,
                missingPermissions = permissions.missing,
                failure =
                    HealthConnectFailure(
                        HealthConnectErrorCode.PERMISSION_DENIED,
                        "Exercise session write permission is required",
                        retryable = true,
                    ),
            )
        }

        val executionPlan =
            mapper.plan(
                activity = detail.activity,
                phoneTitle = detail.metadata.title,
                phoneNotes = detail.metadata.notes,
                includeRoute = permissions.hasPhoneRouteWrite,
            )
        val allowedDrafts =
            executionPlan.drafts.filter {
                it.requiredPermission.androidPermission in grantedAndroidPermissions
            }
        val failures = executionPlan.mappingFailures.toMutableList()
        val prepared =
            try {
                prepareRecords(sessionId, revision, allowedDrafts)
            } catch (error: Exception) {
                return blockedResult(
                    operationId = retryToken,
                    kind = requestedKind,
                    sessionId = sessionId,
                    revision = revision,
                    missingPermissions = permissions.missing,
                    failure = classify(error),
                )
            }
        val draftsByKey =
            allowedDrafts.associateBy { DraftKey(it.providerRecordType, it.chunkIndex) }

        for (record in prepared) {
            if (record.state == HealthConnectLedgerRepository.RecordState.UPSERTED) {
                continue
            }
            val draft =
                draftsByKey[
                    DraftKey(record.key.recordType, record.key.chunkIndex)
                ] ?: continue
            var began = false
            try {
                ledger.beginOperation(
                    record.key,
                    record.clientRecordVersion,
                    record.operation,
                    nowUtcMs(),
                )
                began = true
                val providerRecordId =
                    gateway().upsert(
                        draft.record(
                            clientRecordId = record.clientRecordId,
                            clientRecordVersion = record.clientRecordVersion,
                        )
                    )
                ledger.markUpserted(
                    record.key,
                    record.clientRecordVersion,
                    providerRecordId,
                    nowUtcMs(),
                )
            } catch (error: Exception) {
                val failure = classify(error)
                failures += failure
                if (began) {
                    try {
                        ledger.markFailure(
                            record.key,
                            record.clientRecordVersion,
                            failure.code.name,
                            failure.message,
                            nowUtcMs(),
                        )
                    } catch (_: Exception) {
                        failures += ledgerFailure()
                    }
                }
                if (record.key.recordType == HealthConnectRecordMapper.PROVIDER_EXERCISE_SESSION) {
                    break
                }
            }
        }

        val currentRecords = ledger.records(sessionId, revision)
        val currentByKey =
            currentRecords.associateBy {
                DraftKey(it.key.recordType, it.key.chunkIndex)
            }
        val currentComplete =
            allowedDrafts.all { draft ->
                currentByKey[DraftKey(draft.providerRecordType, draft.chunkIndex)]?.state ==
                    HealthConnectLedgerRepository.RecordState.UPSERTED
            }
        val cleanupMissing = linkedSetOf<HealthConnectWritePermission>()
        if (permissions.missing.isEmpty() && currentComplete) {
            cleanupSuperseded(
                sessionId = sessionId,
                currentRevision = revision,
                grantedAndroidPermissions = grantedAndroidPermissions,
                failures = failures,
                missingPermissions = cleanupMissing,
            )
        }

        val missingPermissions = permissions.missing + cleanupMissing
        return operationResult(
            operationId = retryToken,
            kind = requestedKind,
            sessionId = sessionId,
            revision = revision,
            missingPermissions = missingPermissions,
            failures = failures,
        )
    }

    private fun prepareRecords(
        sessionId: String,
        revision: Long,
        drafts: List<HealthConnectRecordDraft>,
    ): List<HealthConnectLedgerRepository.LedgerRecord> {
        val existingByKey =
            ledger.records(sessionId, revision).associateBy {
                DraftKey(it.key.recordType, it.key.chunkIndex)
            }
        val plans =
            drafts.map { draft ->
                val key = DraftKey(draft.providerRecordType, draft.chunkIndex)
                val existing = existingByKey[key]
                val clientRecordId =
                    HealthConnectLedgerRepository.deterministicClientRecordId(
                        sessionId,
                        draft.providerRecordType,
                        draft.chunkIndex,
                    )
                val canReuseVersion =
                    existing != null &&
                        existing.mappingSha256 == draft.mappingSha256 &&
                        existing.state != HealthConnectLedgerRepository.RecordState.DELETED &&
                        existing.state != HealthConnectLedgerRepository.RecordState.DELETE_PENDING
                val version =
                    if (canReuseVersion) {
                        existing.clientRecordVersion
                    } else {
                        ledger.allocateNextClientRecordVersion(
                            sessionId,
                            clientRecordId,
                            nowUtcMs(),
                        )
                    }
                HealthConnectLedgerRepository.RecordPlan(
                    draft.providerRecordType,
                    draft.chunkIndex,
                    clientRecordId,
                    version,
                    draft.mappingSha256,
                )
            }
        return ledger.prepareRecords(sessionId, revision, plans, nowUtcMs())
    }

    private suspend fun cleanupSuperseded(
        sessionId: String,
        currentRevision: Long,
        grantedAndroidPermissions: Set<String>,
        failures: MutableList<HealthConnectFailure>,
        missingPermissions: MutableSet<HealthConnectWritePermission>,
    ) {
        val before = ledger.ownedRecordsForSession(sessionId)
        val priorRevisions =
            before
                .asSequence()
                .map { it.key.revision }
                .filter { it != currentRevision }
                .distinct()
                .toList()
        priorRevisions.forEach { ledger.markDeletePending(sessionId, it, nowUtcMs()) }

        val activeRecords =
            ledger.records(sessionId, currentRevision).filter {
                it.state == HealthConnectLedgerRepository.RecordState.UPSERTED
            }
        val activeClientIds = activeRecords.mapTo(hashSetOf()) { it.clientRecordId }
        val activeProviderIds =
            activeRecords
                .flatMap { it.ownedProviderRecordIds + listOfNotNull(it.providerRecordId) }
                .toHashSet()
        val pending =
            ledger.ownedRecordsForSession(sessionId)
                .filter {
                    it.key.revision != currentRevision &&
                        it.state == HealthConnectLedgerRepository.RecordState.DELETE_PENDING
                }
                .sortedWith(deleteOrder())
        for (record in pending) {
            val permission = permissionForProviderRecord(record.key.recordType)
            if (permission == null) {
                failures +=
                    HealthConnectFailure(
                        HealthConnectErrorCode.PROVIDER_PROTOCOL,
                        "An owned Health Connect record has an unknown type",
                        retryable = false,
                    )
                continue
            }
            if (permission.androidPermission !in grantedAndroidPermissions) {
                missingPermissions += permission
                continue
            }
            deleteRecord(
                record = record,
                protectClientIds = activeClientIds,
                protectProviderIds = activeProviderIds,
                failures = failures,
            )
        }
    }

    private suspend fun deleteLocked(
        sessionId: String,
        revision: Long,
    ): HealthConnectOperationResult {
        recoverInterrupted(sessionId)
        val operationId =
            RetryToken(HealthConnectOperationKind.DELETE, sessionId, revision).value
        val providerAvailability = availability()
        if (!providerAvailability.isAvailable) {
            return blockedResult(
                operationId = operationId,
                kind = HealthConnectOperationKind.DELETE,
                sessionId = sessionId,
                revision = revision,
                failure =
                    HealthConnectFailure(
                        HealthConnectErrorCode.PROVIDER_UNAVAILABLE,
                        "Health Connect is not available",
                        retryable = true,
                    ),
            )
        }
        ledger.markAllOwnedDeletePending(sessionId, nowUtcMs())
        val records =
            ledger.ownedRecordsForSession(sessionId)
                .filter { it.state == HealthConnectLedgerRepository.RecordState.DELETE_PENDING }
                .sortedWith(deleteOrder())
        if (records.isEmpty()) {
            return HealthConnectOperationResult(
                operationId = operationId,
                kind = HealthConnectOperationKind.DELETE,
                sessionId = sessionId,
                revision = revision,
                state = HealthConnectOperationState.SUCCEEDED,
                receipts = receipts(sessionId),
                missingPermissions = emptySet(),
                failure = null,
            )
        }
        val grantedAndroidPermissions =
            try {
                gateway().grantedPermissions()
            } catch (error: Exception) {
                return blockedResult(
                    operationId = operationId,
                    kind = HealthConnectOperationKind.DELETE,
                    sessionId = sessionId,
                    revision = revision,
                    failure = classify(error),
                )
            }
        val failures = mutableListOf<HealthConnectFailure>()
        val missingPermissions = linkedSetOf<HealthConnectWritePermission>()
        for (record in records) {
            val permission = permissionForProviderRecord(record.key.recordType)
            if (permission == null) {
                failures +=
                    HealthConnectFailure(
                        HealthConnectErrorCode.PROVIDER_PROTOCOL,
                        "An owned Health Connect record has an unknown type",
                        retryable = false,
                    )
                continue
            }
            if (permission.androidPermission !in grantedAndroidPermissions) {
                missingPermissions += permission
                continue
            }
            deleteRecord(
                record = record,
                protectClientIds = emptySet(),
                protectProviderIds = emptySet(),
                failures = failures,
            )
        }
        return operationResult(
            operationId = operationId,
            kind = HealthConnectOperationKind.DELETE,
            sessionId = sessionId,
            revision = revision,
            missingPermissions = missingPermissions,
            failures = failures,
        )
    }

    private suspend fun deleteRecord(
        record: HealthConnectLedgerRepository.LedgerRecord,
        protectClientIds: Set<String>,
        protectProviderIds: Set<String>,
        failures: MutableList<HealthConnectFailure>,
    ) {
        var began = false
        try {
            ledger.beginOperation(
                record.key,
                record.clientRecordVersion,
                HealthConnectLedgerRepository.Operation.DELETE,
                nowUtcMs(),
            )
            began = true
            val providerIds =
                (record.ownedProviderRecordIds + listOfNotNull(record.providerRecordId))
                    .filter { it !in protectProviderIds }
                    .distinct()
            val clientIds =
                if (record.clientRecordId in protectClientIds) {
                    emptyList()
                } else {
                    listOf(record.clientRecordId)
                }
            if (providerIds.isEmpty() && clientIds.isEmpty()) {
                ledger.markDeleted(
                    record.key,
                    record.clientRecordVersion,
                    HealthConnectLedgerRepository.DeletionResult.ALREADY_ABSENT,
                    record.providerRecordId,
                    null,
                    nowUtcMs(),
                )
                return
            }
            gateway().delete(record.key.recordType, providerIds, clientIds)
            ledger.markDeleted(
                record.key,
                record.clientRecordVersion,
                HealthConnectLedgerRepository.DeletionResult.DELETED,
                record.providerRecordId,
                null,
                nowUtcMs(),
            )
        } catch (error: Exception) {
            val failure = classify(error)
            failures += failure
            if (began) {
                try {
                    ledger.markDeleted(
                        record.key,
                        record.clientRecordVersion,
                        HealthConnectLedgerRepository.DeletionResult.FAILED,
                        record.providerRecordId,
                        failure.code.name,
                        nowUtcMs(),
                    )
                } catch (_: Exception) {
                    failures += ledgerFailure()
                }
            }
        }
    }

    private fun requiredPermissions(
        sessionId: String,
        revision: Long,
        fullPlan: HealthConnectWritePlan,
        owned: List<HealthConnectLedgerRepository.LedgerRecord>,
    ): Set<HealthConnectWritePermission> =
        linkedSetOf<HealthConnectWritePermission>().apply {
            addAll(fullPlan.requiredPermissions)
            addAll(ownedRequiredPermissions(owned))
            if (
                !fullPlan.routePresent &&
                    priorOwnedRevisionMayHaveRoute(sessionId, revision, owned)
            ) {
                add(HealthConnectWritePermission.PHONE_EXERCISE_ROUTE)
            }
        }

    private fun ownedRequiredPermissions(
        owned: List<HealthConnectLedgerRepository.LedgerRecord>,
    ): Set<HealthConnectWritePermission> =
        owned
            .asSequence()
            .filter { it.state != HealthConnectLedgerRepository.RecordState.DELETED }
            .mapNotNull { permissionForProviderRecord(it.key.recordType) }
            .toCollection(linkedSetOf())

    private fun priorOwnedRevisionMayHaveRoute(
        sessionId: String,
        revision: Long,
        owned: List<HealthConnectLedgerRepository.LedgerRecord>,
    ): Boolean =
        owned
            .asSequence()
            .filter {
                it.key.revision != revision &&
                    it.key.recordType ==
                        HealthConnectRecordMapper.PROVIDER_EXERCISE_SESSION &&
                    it.state != HealthConnectLedgerRepository.RecordState.DELETED
            }
            .map { it.key.revision }
            .distinct()
            .any { priorRevision ->
                val activity = activities.detail(sessionId, priorRevision)?.activity
                activity?.samplesList?.any { sample ->
                    sample.hasLocation() &&
                        sample.location.hasValue() &&
                        sample.location.status.quality != Quality.QUALITY_INVALID
                } == true
            }

    private fun permissionForProviderRecord(
        providerRecordType: String,
    ): HealthConnectWritePermission? =
        when (providerRecordType) {
            HealthConnectRecordMapper.PROVIDER_EXERCISE_SESSION ->
                HealthConnectWritePermission.EXERCISE_SESSION
            HealthConnectRecordMapper.PROVIDER_HEART_RATE ->
                HealthConnectWritePermission.HEART_RATE
            HealthConnectRecordMapper.PROVIDER_STEPS -> HealthConnectWritePermission.STEPS
            HealthConnectRecordMapper.PROVIDER_DISTANCE -> HealthConnectWritePermission.DISTANCE
            HealthConnectRecordMapper.PROVIDER_TOTAL_CALORIES ->
                HealthConnectWritePermission.TOTAL_CALORIES
            HealthConnectRecordMapper.PROVIDER_ELEVATION_GAINED ->
                HealthConnectWritePermission.ELEVATION_GAINED
            HealthConnectRecordMapper.PROVIDER_SPEED -> HealthConnectWritePermission.SPEED
            else -> null
        }

    private fun recoverInterrupted(sessionId: String) {
        ledger.ownedRecordsForSession(sessionId)
            .filter { it.state == HealthConnectLedgerRepository.RecordState.IN_FLIGHT }
            .forEach { record ->
                ledger.markFailure(
                    record.key,
                    record.clientRecordVersion,
                    PROCESS_INTERRUPTED,
                    "The prior Health Connect operation was interrupted and is safe to retry",
                    nowUtcMs(),
                )
            }
    }

    private fun operationResult(
        operationId: String,
        kind: HealthConnectOperationKind,
        sessionId: String,
        revision: Long,
        missingPermissions: Set<HealthConnectWritePermission>,
        failures: List<HealthConnectFailure>,
    ): HealthConnectOperationResult {
        val receipts = receipts(sessionId)
        val successCount =
            receipts.count {
                it.state == HealthConnectRecordState.WRITTEN ||
                    it.state == HealthConnectRecordState.DELETED
            }
        val failure = failures.firstOrNull()
        val state =
            when {
                failures.isEmpty() && missingPermissions.isEmpty() &&
                    receipts.none {
                        it.state == HealthConnectRecordState.PLANNED ||
                            it.state == HealthConnectRecordState.DELETE_PENDING ||
                            it.state == HealthConnectRecordState.FAILED_RETRYABLE ||
                            it.state == HealthConnectRecordState.FAILED_PERMANENT
                    } -> HealthConnectOperationState.SUCCEEDED
                successCount > 0 ->
                    HealthConnectOperationState.PARTIAL
                missingPermissions.isNotEmpty() ->
                    HealthConnectOperationState.BLOCKED
                failures.any { it.retryable } ->
                    HealthConnectOperationState.RETRYABLE_FAILURE
                else -> HealthConnectOperationState.PERMANENT_FAILURE
            }
        return HealthConnectOperationResult(
            operationId = operationId,
            kind = kind,
            sessionId = sessionId,
            revision = revision,
            state = state,
            receipts = receipts,
            missingPermissions = missingPermissions,
            failure =
                failure
                    ?: missingPermissions.takeIf { it.isNotEmpty() }?.let {
                        HealthConnectFailure(
                            HealthConnectErrorCode.PERMISSION_DENIED,
                            "One or more Health Connect write permissions are missing",
                            retryable = true,
                        )
                    },
        )
    }

    private fun lastOperationResult(
        sessionId: String,
        revision: Long,
        missingPermissions: Set<HealthConnectWritePermission>,
    ): HealthConnectOperationResult? {
        val owned = ledger.ownedRecordsForSession(sessionId)
        val latest = owned.maxByOrNull { it.updatedAtUtcMs } ?: return null
        val kind =
            when (latest.operation) {
                HealthConnectLedgerRepository.Operation.PUBLISH ->
                    HealthConnectOperationKind.PUBLISH
                HealthConnectLedgerRepository.Operation.UPDATE ->
                    HealthConnectOperationKind.UPDATE
                HealthConnectLedgerRepository.Operation.DELETE ->
                    HealthConnectOperationKind.DELETE
            }
        val relevant =
            if (kind == HealthConnectOperationKind.DELETE) {
                owned
            } else {
                owned.filter { it.key.revision == revision }
            }
        val state =
            when {
                relevant.any {
                    it.state == HealthConnectLedgerRepository.RecordState.IN_FLIGHT
                } -> HealthConnectOperationState.IN_PROGRESS
                relevant.any {
                    it.state == HealthConnectLedgerRepository.RecordState.FAILED ||
                        it.state == HealthConnectLedgerRepository.RecordState.DELETE_PENDING
                } -> HealthConnectOperationState.PARTIAL
                missingPermissions.isNotEmpty() -> HealthConnectOperationState.PARTIAL
                relevant.any {
                    it.state == HealthConnectLedgerRepository.RecordState.PENDING
                } ->
                    if (
                        relevant.any {
                            it.state == HealthConnectLedgerRepository.RecordState.UPSERTED
                        }
                    ) {
                        HealthConnectOperationState.PARTIAL
                    } else {
                        HealthConnectOperationState.NOT_STARTED
                    }
                relevant.isNotEmpty() -> HealthConnectOperationState.SUCCEEDED
                else -> HealthConnectOperationState.NOT_STARTED
            }
        val failure =
            relevant.firstNotNullOfOrNull { record ->
                record.lastErrorCode?.let {
                    failureFromStored(it, record.lastErrorMessage)
                }
            }
        return HealthConnectOperationResult(
            operationId = RetryToken(kind, sessionId, revision).value,
            kind = kind,
            sessionId = sessionId,
            revision = revision,
            state = state,
            receipts = relevant.map(::receipt),
            missingPermissions = missingPermissions,
            failure = failure,
        )
    }

    private fun blockedResult(
        operationId: String,
        kind: HealthConnectOperationKind,
        sessionId: String,
        revision: Long,
        missingPermissions: Set<HealthConnectWritePermission> = emptySet(),
        failure: HealthConnectFailure,
    ): HealthConnectOperationResult =
        HealthConnectOperationResult(
            operationId = operationId,
            kind = kind,
            sessionId = sessionId,
            revision = revision,
            state = HealthConnectOperationState.BLOCKED,
            receipts = receipts(sessionId),
            missingPermissions = missingPermissions,
            failure = failure,
        )

    private fun receipts(sessionId: String): List<HealthConnectRecordReceipt> =
        ledger.ownedRecordsForSession(sessionId).map(::receipt)

    private fun receipt(
        record: HealthConnectLedgerRepository.LedgerRecord,
    ): HealthConnectRecordReceipt {
        val failure =
            record.lastErrorCode?.let {
                failureFromStored(it, record.lastErrorMessage)
            }
        val state =
            when (record.state) {
                HealthConnectLedgerRepository.RecordState.PENDING,
                HealthConnectLedgerRepository.RecordState.IN_FLIGHT,
                -> HealthConnectRecordState.PLANNED
                HealthConnectLedgerRepository.RecordState.UPSERTED ->
                    HealthConnectRecordState.WRITTEN
                HealthConnectLedgerRepository.RecordState.FAILED ->
                    if (failure?.retryable == false) {
                        HealthConnectRecordState.FAILED_PERMANENT
                    } else {
                        HealthConnectRecordState.FAILED_RETRYABLE
                    }
                HealthConnectLedgerRepository.RecordState.DELETE_PENDING ->
                    HealthConnectRecordState.DELETE_PENDING
                HealthConnectLedgerRepository.RecordState.DELETED ->
                    HealthConnectRecordState.DELETED
            }
        return HealthConnectRecordReceipt(
            recordType =
                recordType(record.key.recordType)
                    ?: HealthConnectRecordType.EXERCISE_SESSION,
            chunkIndex = record.key.chunkIndex,
            clientRecordId = record.clientRecordId,
            clientRecordVersion = record.clientRecordVersion,
            providerRecordId = record.providerRecordId,
            state = state,
            failure = failure,
        )
    }

    private fun recordType(providerRecordType: String): HealthConnectRecordType? =
        when (providerRecordType) {
            HealthConnectRecordMapper.PROVIDER_EXERCISE_SESSION ->
                HealthConnectRecordType.EXERCISE_SESSION
            HealthConnectRecordMapper.PROVIDER_HEART_RATE -> HealthConnectRecordType.HEART_RATE
            HealthConnectRecordMapper.PROVIDER_STEPS -> HealthConnectRecordType.STEPS
            HealthConnectRecordMapper.PROVIDER_DISTANCE -> HealthConnectRecordType.DISTANCE
            HealthConnectRecordMapper.PROVIDER_TOTAL_CALORIES ->
                HealthConnectRecordType.TOTAL_CALORIES
            HealthConnectRecordMapper.PROVIDER_ELEVATION_GAINED ->
                HealthConnectRecordType.ELEVATION_GAINED
            HealthConnectRecordMapper.PROVIDER_SPEED -> HealthConnectRecordType.SPEED
            else -> null
        }

    private fun classify(error: Exception): HealthConnectFailure =
        when (error) {
            is HealthConnectMappingException ->
                HealthConnectFailure(error.code, error.message.orEmpty(), retryable = false)
            is CanonicalContractException ->
                HealthConnectFailure(
                    if (error.code.startsWith("HEALTH_CONNECT_")) {
                        HealthConnectErrorCode.LEDGER_CONFLICT
                    } else {
                        HealthConnectErrorCode.INVALID_CANONICAL_ACTIVITY
                    },
                    "The canonical Health Connect operation was rejected",
                    retryable = false,
                )
            is SecurityException ->
                HealthConnectFailure(
                    HealthConnectErrorCode.PERMISSION_REVOKED,
                    "A required Health Connect permission was revoked",
                    retryable = true,
                )
            is IOException,
            is RemoteException,
            -> HealthConnectFailure(
                HealthConnectErrorCode.PROVIDER_IO,
                "Health Connect could not complete the provider operation",
                retryable = true,
            )
            is HealthConnectProviderProtocolException ->
                HealthConnectFailure(
                    HealthConnectErrorCode.PROVIDER_PROTOCOL,
                    error.message.orEmpty(),
                    retryable = true,
                )
            is UnsupportedOperationException,
            is IllegalStateException,
            -> HealthConnectFailure(
                HealthConnectErrorCode.PROVIDER_UNAVAILABLE,
                "Health Connect became unavailable",
                retryable = true,
            )
            is IllegalArgumentException ->
                HealthConnectFailure(
                    HealthConnectErrorCode.INVALID_PROVIDER_RECORD,
                    "Health Connect rejected a mapped provider record",
                    retryable = false,
                )
            else ->
                HealthConnectFailure(
                    HealthConnectErrorCode.PROVIDER_FAILURE,
                    "Health Connect failed unexpectedly",
                    retryable = true,
                )
        }

    private fun failureFromStored(
        code: String,
        message: String?,
    ): HealthConnectFailure {
        val parsed = HealthConnectErrorCode.entries.firstOrNull { it.name == code }
        val errorCode =
            parsed
                ?: if (code == PROCESS_INTERRUPTED) {
                    HealthConnectErrorCode.PROVIDER_FAILURE
                } else {
                    HealthConnectErrorCode.PROVIDER_FAILURE
                }
        return HealthConnectFailure(
            code = errorCode,
            message = message ?: "Health Connect operation failed",
            retryable =
                errorCode != HealthConnectErrorCode.INVALID_CANONICAL_ACTIVITY &&
                    errorCode != HealthConnectErrorCode.INVALID_PROVIDER_RECORD &&
                    errorCode != HealthConnectErrorCode.LEDGER_CONFLICT,
        )
    }

    private fun ledgerFailure(): HealthConnectFailure =
        HealthConnectFailure(
            HealthConnectErrorCode.LEDGER_CONFLICT,
            "Health Connect provider state could not be recorded durably",
            retryable = false,
        )

    private fun gateway(): HealthConnectGateway {
        val existing = healthConnectGateway
        if (existing != null) {
            return existing
        }
        return gatewayFactory().also { healthConnectGateway = it }
    }

    private fun deleteOrder():
        Comparator<HealthConnectLedgerRepository.LedgerRecord> =
        compareBy<HealthConnectLedgerRepository.LedgerRecord> {
                it.key.recordType ==
                    HealthConnectRecordMapper.PROVIDER_EXERCISE_SESSION
            }
            .thenByDescending { it.key.revision }
            .thenBy { it.key.recordType }
            .thenBy { it.key.chunkIndex }

    private suspend fun <T> exclusive(block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            operationMutex.lock()
            try {
                block()
            } finally {
                operationMutex.unlock()
            }
        }

    private data class DraftKey(
        val recordType: String,
        val chunkIndex: Int,
    )

    private data class RetryToken(
        val kind: HealthConnectOperationKind,
        val sessionId: String,
        val revision: Long,
    ) {
        val value: String
            get() = "$PREFIX|${kind.name}|$sessionId|$revision"

        companion object {
            private const val PREFIX = "health-connect-v1"

            fun parse(value: String): RetryToken {
                val parts = value.split('|')
                require(parts.size == 4 && parts[0] == PREFIX) {
                    "Invalid Health Connect retry operation identifier"
                }
                val kind = HealthConnectOperationKind.valueOf(parts[1])
                val revision = parts[3].toLong()
                require(revision > 0L) {
                    "Invalid Health Connect retry revision"
                }
                return RetryToken(kind, parts[2], revision)
            }
        }
    }

    companion object {
        private const val PROCESS_INTERRUPTED = "PROCESS_INTERRUPTED"
    }
}
