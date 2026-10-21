package com.long2know.sportlogger

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.long2know.sportlogger.integration.IntegrationAction
import com.long2know.sportlogger.integration.IntegrationActionResult
import com.long2know.sportlogger.integration.PhoneIntegrationStatusGateway
import com.long2know.sportlogger.healthconnect.HealthConnectOperationKind
import com.long2know.sportlogger.healthconnect.HealthConnectOperationResult
import com.long2know.sportlogger.healthconnect.HealthConnectOperationState
import com.long2know.sportlogger.healthconnect.HealthConnectPermissionGrant
import com.long2know.sportlogger.healthconnect.HealthConnectPermissions
import com.long2know.sportlogger.healthconnect.HealthConnectProvider
import com.long2know.sportlogger.healthconnect.HealthConnectSessionStatus
import com.long2know.sportlogger.healthconnect.HealthConnectVerificationResult
import com.long2know.sportlogger.healthconnect.HealthConnectVerificationState
import com.long2know.sportlogger.phone.ActivityDetailUi
import com.long2know.sportlogger.phone.ActivityKey
import com.long2know.sportlogger.phone.ActivityTypeTrendUi
import com.long2know.sportlogger.phone.BackfillUi
import com.long2know.sportlogger.phone.CanonicalPhoneDataSource
import com.long2know.sportlogger.phone.EndurainEditorUi
import com.long2know.sportlogger.phone.EndurainReconcileUi
import com.long2know.sportlogger.phone.EndurainUi
import com.long2know.sportlogger.phone.MeasurementAvailabilityUi
import com.long2know.sportlogger.phone.MetadataEditorUi
import com.long2know.sportlogger.phone.PhoneDestination
import com.long2know.sportlogger.phone.PhoneUiEvent
import com.long2know.sportlogger.phone.PhoneUiState
import com.long2know.sportlogger.phone.RoutePointUi
import com.long2know.sportlogger.phone.StatusUi
import com.long2know.sportlogger.phone.TrendsUi
import com.long2know.sportlogger.phone.WeeklyTrendUi
import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository
import com.long2know.sportlogger.publishing.endurain.EndurainDeliveryKey
import com.long2know.sportlogger.publishing.endurain.factory.EndurainActionResult
import com.long2know.sportlogger.publishing.endurain.factory.EndurainPublishingController
import com.long2know.utilities.contract.CanonicalContractException
import com.long2know.utilities.contract.proto.ActivityRecord
import com.long2know.utilities.export.tcx.TcxCancellation
import com.long2know.utilities.export.tcx.TcxWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ComposePhoneViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _state = MutableStateFlow(
        PhoneUiState(
            destination = savedDestination(),
            selectedKey = savedActivityKey(),
        ),
    )
    val state: StateFlow<PhoneUiState> = _state.asStateFlow()

    private val tcxWriter = TcxWriter()
    private val eventIds = AtomicLong()
    private val repositoryCallbackExecutor = Executor { command ->
        viewModelScope.launch(Dispatchers.IO) {
            command.run()
        }
    }

    private var dataSource: CanonicalPhoneDataSource? = null
    private var integrationGateway: PhoneIntegrationStatusGateway? = null
    private var healthConnectProvider: HealthConnectProvider? = null
    private var deletionCommittedHook:
        ((PhoneActivityRepository.TombstoneOutboxItem) -> Unit)? = null
    private var deleteRetryHook: (() -> Unit)? = null
    private var latestHealthConnectStatus: HealthConnectSessionStatus? = null
    private var latestHealthVerification: HealthConnectVerificationResult? = null
    private var endurainController: EndurainPublishingController? = null
    private var repositoryObserver: AutoCloseable? = null
    private var integrationObserver: AutoCloseable? = null
    private var healthConnectObserver: AutoCloseable? = null
    private var endurainObserver: AutoCloseable? = null
    private var initialLoadJob: Job? = null
    private var repositoryRefreshJob: Job? = null
    private var detailJob: Job? = null
    private var trendJob: Job? = null
    private var statusJob: Job? = null
    private var exportJob: Job? = null
    private var endurainJob: Job? = null

    fun initialize(
        dataSourceFactory: () -> CanonicalPhoneDataSource,
        integrationGateway: PhoneIntegrationStatusGateway,
        healthConnectProviderFactory: () -> HealthConnectProvider,
        endurainControllerFactory: () -> CompletableFuture<EndurainPublishingController>,
        deletionCommittedHook: (PhoneActivityRepository.TombstoneOutboxItem) -> Unit,
        deleteRetryHook: () -> Unit,
    ) {
        if (dataSource != null) {
            return
        }
        dataSource = dataSourceFactory()
        this.integrationGateway = integrationGateway
        healthConnectProvider = healthConnectProviderFactory()
        this.deletionCommittedHook = deletionCommittedHook
        this.deleteRetryHook = deleteRetryHook
        integrationObserver = integrationGateway.observe {
            refreshStatus()
        }
        healthConnectObserver = requireHealthConnectProvider().observeCanonicalChanges(
            repositoryCallbackExecutor,
            Runnable { refreshStatus() },
        )
        initializeEndurain(endurainControllerFactory)
        loadInitial()
    }

    fun healthConnectPermissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        requireHealthConnectProvider().permissionRequestContract()

    fun refreshFromReceipt() {
        refreshAll(runBackfill = true, refreshing = true)
    }

    fun onActivityResumed() {
        refreshStatus()
    }

    fun onHealthConnectPermissionResult(grantedPermissions: Set<String>) {
        val requested = savedStateHandle
            .get<ArrayList<String>>(HEALTH_PERMISSION_REQUEST_KEY)
            .orEmpty()
            .toSet()
        savedStateHandle.remove<ArrayList<String>>(HEALTH_PERMISSION_REQUEST_KEY)
        val result = HealthConnectPermissions.interpretRequestResult(
            requested,
            grantedPermissions,
        )
        _state.update {
            it.copy(
                notice = when (result.grant) {
                    HealthConnectPermissionGrant.GRANTED ->
                        "Health Connect permissions granted."
                    HealthConnectPermissionGrant.PARTIAL ->
                        "Some Health Connect permissions remain unavailable."
                    HealthConnectPermissionGrant.DENIED ->
                        "Health Connect permissions were not granted."
                    HealthConnectPermissionGrant.NOT_REQUIRED ->
                        "No Health Connect permissions were required."
                    HealthConnectPermissionGrant.UNAVAILABLE ->
                        "Health Connect permissions are unavailable."
                },
            )
        }
        refreshStatus()
    }

    fun refreshHistory() {
        refreshAll(runBackfill = false, refreshing = true)
    }

    fun onCanonicalSyncHint(acknowledgementDelivered: Boolean) {
        if (!acknowledgementDelivered) {
            _state.update {
                it.copy(
                    notice = "Activity committed on this phone; watch acknowledgement will retry.",
                )
            }
        }
        refreshHistory()
    }

    fun onCanonicalSyncFailure() {
        _state.update {
            it.copy(
                notice = "Watch sync failed before a phone commit. Existing activities are unchanged.",
            )
        }
    }

    fun loadMoreHistory() {
        val source = requireDataSource()
        val current = _state.value
        if (current.historyLoading || current.historyRefreshing || !current.historyHasMore) {
            return
        }
        _state.update { it.copy(historyRefreshing = true, historyError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                source.summaries(current.history.size, HISTORY_PAGE_SIZE)
            }.onSuccess { page ->
                val keys = current.history
                    .asSequence()
                    .map { ActivityKey(it.sessionId, it.revision) }
                    .toMutableSet()
                val appended = page.filter {
                    keys.add(ActivityKey(it.sessionId, it.revision))
                }
                _state.update {
                    it.copy(
                        history = it.history + appended,
                        historyRefreshing = false,
                        historyHasMore = page.size == HISTORY_PAGE_SIZE,
                    )
                }
            }.onFailure {
                _state.update {
                    it.copy(
                        historyRefreshing = false,
                        historyError = HISTORY_LOAD_ERROR,
                    )
                }
            }
        }
    }

    fun selectActivity(
        sessionId: String,
        revision: Long,
    ) {
        val key = ActivityKey(sessionId, revision)
        savedStateHandle[SELECTED_SESSION_KEY] = sessionId
        savedStateHandle[SELECTED_REVISION_KEY] = revision
        savedStateHandle[EDITING_KEY] = false
        _state.update {
            it.copy(
                selectedKey = key,
                detail = null,
                detailLoading = true,
                detailError = null,
                editor = null,
                notice = null,
            )
        }
        loadDetail(key)
    }

    fun closeDetail() {
        detailJob?.cancel()
        savedStateHandle.remove<String>(SELECTED_SESSION_KEY)
        savedStateHandle.remove<Long>(SELECTED_REVISION_KEY)
        savedStateHandle[EDITING_KEY] = false
        savedStateHandle.remove<String>(EDIT_TITLE_KEY)
        savedStateHandle.remove<String>(EDIT_NOTES_KEY)
        _state.update {
            it.copy(
                selectedKey = null,
                detail = null,
                detailLoading = false,
                detailError = null,
                editor = null,
                deleteConfirmation = false,
                deleteBusy = false,
                notice = null,
            )
        }
    }

    fun selectDestination(destination: PhoneDestination) {
        savedStateHandle[DESTINATION_KEY] = destination.name
        _state.update {
            it.copy(
                destination = destination,
                notice = null,
            )
        }
        when (destination) {
            PhoneDestination.HISTORY -> Unit
            PhoneDestination.TRENDS -> refreshTrends()
            PhoneDestination.STATUS -> refreshStatus()
        }
    }

    fun openEditor() {
        val detail = _state.value.detail ?: return
        val title = savedStateHandle.get<String>(EDIT_TITLE_KEY)
            ?: detail.detail.metadata.title.orEmpty()
        val notes = savedStateHandle.get<String>(EDIT_NOTES_KEY)
            ?: detail.detail.metadata.notes.orEmpty()
        savedStateHandle[EDITING_KEY] = true
        savedStateHandle[EDIT_TITLE_KEY] = title
        savedStateHandle[EDIT_NOTES_KEY] = notes
        _state.update {
            it.copy(
                editor = MetadataEditorUi(title = title, notes = notes),
                notice = null,
            )
        }
    }

    fun updateEditorTitle(value: String) {
        savedStateHandle[EDIT_TITLE_KEY] = value
        _state.update { state ->
            state.copy(
                editor = state.editor?.copy(
                    title = value,
                    error = null,
                ),
            )
        }
    }

    fun updateEditorNotes(value: String) {
        savedStateHandle[EDIT_NOTES_KEY] = value
        _state.update { state ->
            state.copy(
                editor = state.editor?.copy(
                    notes = value,
                    error = null,
                ),
            )
        }
    }

    fun cancelEditor() {
        savedStateHandle[EDITING_KEY] = false
        savedStateHandle.remove<String>(EDIT_TITLE_KEY)
        savedStateHandle.remove<String>(EDIT_NOTES_KEY)
        _state.update { it.copy(editor = null) }
    }

    fun requestDelete() {
        if (_state.value.detail == null || _state.value.deleteBusy) {
            return
        }
        _state.update { it.copy(deleteConfirmation = true, notice = null) }
    }

    fun cancelDelete() {
        if (!_state.value.deleteBusy) {
            _state.update { it.copy(deleteConfirmation = false) }
        }
    }

    fun confirmDelete() {
        val detail = _state.value.detail ?: return
        if (_state.value.deleteBusy) {
            return
        }
        _state.update {
            it.copy(
                deleteBusy = true,
                deleteConfirmation = false,
                notice = null,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val deleteResult = runCatching {
                requireDataSource().deleteLocal(
                    sessionId = detail.detail.sessionId,
                    expectedAcceptedRevision = detail.detail.revision,
                    deletedAtUtcMs = System.currentTimeMillis(),
                )
            }
            deleteResult.onSuccess { result ->
                val hookSucceeded = runCatching {
                    checkNotNull(deletionCommittedHook).invoke(result.outbox)
                }.isSuccess
                savedStateHandle.remove<String>(SELECTED_SESSION_KEY)
                savedStateHandle.remove<Long>(SELECTED_REVISION_KEY)
                _state.update {
                    it.copy(
                        selectedKey = null,
                        detail = null,
                        detailLoading = false,
                        deleteBusy = false,
                        notice = if (hookSucceeded) {
                            "Activity deleted on this phone; paired-watch deletion is queued."
                        } else {
                            "Activity deleted on this phone; paired-watch deletion remains durably queued."
                        },
                    )
                }
                scheduleRepositoryRefresh(immediate = true)
            }.onFailure { error ->
                when ((error as? CanonicalContractException)?.code) {
                    "DELETE_HEAD_CHANGED" -> {
                        _state.update {
                            it.copy(
                                deleteBusy = false,
                                notice = "Activity changed before deletion. Review the latest revision and confirm again.",
                            )
                        }
                        scheduleRepositoryRefresh(immediate = true)
                    }
                    "DELETE_ALREADY_DELETED" -> {
                        closeDetail()
                        _state.update {
                            it.copy(
                                deleteBusy = false,
                                notice = "This activity was already deleted by a canonical source.",
                            )
                        }
                        scheduleRepositoryRefresh(immediate = true)
                    }
                    else -> {
                        _state.update {
                            it.copy(
                                deleteBusy = false,
                                notice = "Activity deletion could not be committed.",
                            )
                        }
                    }
                }
            }
        }
    }

    fun retryDeleteSync() {
        runCatching { checkNotNull(deleteRetryHook).invoke() }
            .onSuccess {
                _state.update {
                    it.copy(notice = "Paired-watch deletion retry requested.")
                }
            }
            .onFailure {
                _state.update {
                    it.copy(notice = "Paired-watch deletion retry could not be scheduled.")
                }
            }
    }

    fun onTombstoneSyncState(displayState: String) {
        _state.update {
            it.copy(
                notice = when (displayState) {
                    "QUEUED" -> "Paired-watch deletion is queued."
                    "TRANSFERRING" -> "Paired-watch deletion is transferring."
                    "SYNCED" -> "Paired-watch deletion is synced."
                    "FAILED" -> "Paired-watch deletion needs retry."
                    else -> it.notice
                },
            )
        }
        refreshStatus()
    }

    fun saveMetadata() {
        val current = _state.value
        val detail = current.detail ?: return
        val editor = current.editor ?: return
        val validation = metadataValidation(editor.title, editor.notes)
        if (validation != null) {
            _state.update {
                it.copy(editor = editor.copy(error = validation))
            }
            return
        }
        _state.update {
            it.copy(editor = editor.copy(saving = true, error = null))
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                requireDataSource().updateMetadata(
                    sessionId = detail.detail.sessionId,
                    title = editor.title.trim().ifEmpty { null },
                    notes = editor.notes.trim().ifEmpty { null },
                    tags = detail.detail.metadata.tags,
                    updatedAtUtcMs = System.currentTimeMillis(),
                )
            }.onSuccess {
                savedStateHandle[EDITING_KEY] = false
                savedStateHandle.remove<String>(EDIT_TITLE_KEY)
                savedStateHandle.remove<String>(EDIT_NOTES_KEY)
                _state.update {
                    it.copy(
                        editor = null,
                        notice = "Activity details updated.",
                    )
                }
                loadDetail(ActivityKey(detail.detail.sessionId, detail.detail.revision))
                scheduleRepositoryRefresh(immediate = true)
            }.onFailure {
                _state.update { state ->
                    state.copy(
                        editor = state.editor?.copy(
                            saving = false,
                            error = "Activity details could not be saved.",
                        ),
                    )
                }
            }
        }
    }

    fun retryBackfill() {
        refreshAll(runBackfill = true, refreshing = true)
    }

    fun performIntegrationAction(action: IntegrationAction) {
        if (action.id.startsWith(HEALTH_ACTION_PREFIX)) {
            performHealthConnectAction(action)
            return
        }
        val gateway = integrationGateway ?: return
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = runCatching { gateway.perform(action) }.getOrElse {
                IntegrationActionResult.Failed("Integration action failed.")
            }) {
                is IntegrationActionResult.Completed -> {
                    _state.update { it.copy(notice = result.message) }
                }
                is IntegrationActionResult.Failed -> {
                    _state.update { it.copy(notice = result.message) }
                }
            }
            refreshStatus()
        }
    }

    private fun performHealthConnectAction(action: IntegrationAction) {
        val provider = requireHealthConnectProvider()
        val status = latestHealthConnectStatus ?: return
        when (action.id) {
            HEALTH_RESOLVE_ACTION -> {
                val resolution = provider.providerResolutionIntent(status.availability)
                if (resolution == null) {
                    _state.update {
                        it.copy(notice = "Health Connect provider resolution is unavailable.")
                    }
                } else {
                    _state.update {
                        it.copy(
                            event = PhoneUiEvent.ResolveHealthConnectProvider(
                                id = eventIds.incrementAndGet(),
                                intent = resolution,
                            ),
                        )
                    }
                }
            }
            HEALTH_PERMISSION_ACTION -> {
                val permissions = if (BuildConfig.DEBUG) {
                    status.permissionRequestInput
                } else {
                    status.permissions.requestInput
                }
                if (permissions.isEmpty()) {
                    _state.update {
                        it.copy(notice = "No Health Connect permissions are missing.")
                    }
                } else {
                    savedStateHandle[HEALTH_PERMISSION_REQUEST_KEY] =
                        ArrayList(permissions)
                    _state.update {
                        it.copy(
                            event = PhoneUiEvent.RequestHealthConnectPermissions(
                                id = eventIds.incrementAndGet(),
                                permissions = permissions,
                            ),
                        )
                    }
                }
            }
            HEALTH_PUBLISH_ACTION -> runHealthConnectOperation {
                provider.publish(status.sessionId, status.revision)
            }
            HEALTH_UPDATE_ACTION -> runHealthConnectOperation {
                provider.update(status.sessionId, status.revision)
            }
            HEALTH_DELETE_ACTION -> runHealthConnectOperation {
                provider.delete(status.sessionId, status.revision)
            }
            HEALTH_RETRY_ACTION -> {
                val operationId = status.lastOperation?.operationId
                if (operationId == null) {
                    _state.update {
                        it.copy(notice = "No Health Connect operation is available to retry.")
                    }
                } else {
                    runHealthConnectOperation {
                        provider.retry(operationId)
                    }
                }
            }
            HEALTH_VERIFY_ACTION -> {
                viewModelScope.launch(Dispatchers.IO) {
                    val result = runCatching {
                        provider.verifyOwnedExerciseSession(
                            status.sessionId,
                            status.revision,
                        )
                    }.getOrNull()
                    latestHealthVerification = result
                    _state.update {
                        it.copy(
                            notice = result?.let(::healthConnectVerificationMessage)
                                ?: "Health Connect provider verification failed.",
                        )
                    }
                    refreshStatus()
                }
            }
        }
    }

    private fun runHealthConnectOperation(
        operation: suspend () -> HealthConnectOperationResult,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            setHealthConnectWorking()
            runCatching { operation() }
                .onSuccess { result ->
                    val verification = if (
                        BuildConfig.DEBUG &&
                        latestHealthConnectStatus?.canVerifyProvider == true &&
                        (
                            result.state == HealthConnectOperationState.SUCCEEDED ||
                                result.state == HealthConnectOperationState.PARTIAL
                            )
                    ) {
                        runCatching {
                            requireHealthConnectProvider().verifyOwnedExerciseSession(
                                result.sessionId,
                                result.revision,
                            )
                        }.getOrNull()
                    } else {
                        null
                    }
                    latestHealthVerification = verification
                    _state.update {
                        it.copy(
                            notice = buildString {
                                append(healthConnectResultMessage(result))
                                if (verification != null) {
                                    append(" ")
                                    append(healthConnectVerificationMessage(verification))
                                }
                            },
                        )
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(notice = "Health Connect operation failed.")
                    }
                }
            refreshStatus()
        }
    }

    fun openNewEndurainConnection() {
        _state.update {
            it.copy(
                endurainEditor = EndurainEditorUi(),
                notice = null,
            )
        }
    }

    fun editEndurainConnection(connectionIdentity: String) {
        val connection = _state.value.endurain.snapshot
            ?.connections
            ?.firstOrNull {
                it.profile.connectionIdentity == connectionIdentity
            } ?: return
        _state.update {
            it.copy(
                endurainEditor = EndurainEditorUi(
                    existingConnectionIdentity = connection.profile.connectionIdentity,
                    label = connection.profile.label,
                    httpsBaseUrl = connection.profile.normalizedBaseUrl,
                    enabled = connection.profile.isEnabled,
                ),
                notice = null,
            )
        }
    }

    fun updateEndurainLabel(value: String) {
        _state.update {
            it.copy(
                endurainEditor = it.endurainEditor?.copy(label = value, error = null),
            )
        }
    }

    fun updateEndurainBaseUrl(value: String) {
        _state.update {
            it.copy(
                endurainEditor = it.endurainEditor?.copy(
                    httpsBaseUrl = value,
                    error = null,
                ),
            )
        }
    }

    fun updateEndurainApiKey(value: String) {
        _state.update {
            it.copy(
                endurainEditor = it.endurainEditor?.copy(apiKey = value, error = null),
            )
        }
    }

    fun updateEndurainEnabled(value: Boolean) {
        _state.update {
            it.copy(
                endurainEditor = it.endurainEditor?.copy(enabled = value, error = null),
            )
        }
    }

    fun cancelEndurainEditor() {
        if (_state.value.endurainEditor?.saving != true) {
            _state.update { it.copy(endurainEditor = null) }
        }
    }

    fun saveEndurainConnection() {
        val editor = _state.value.endurainEditor ?: return
        if (editor.label.isBlank()) {
            _state.update {
                it.copy(
                    endurainEditor = editor.copy(error = "Connection name is required."),
                )
            }
            return
        }
        if (!editor.httpsBaseUrl.startsWith("https://", ignoreCase = true)) {
            _state.update {
                it.copy(
                    endurainEditor = editor.copy(error = "An HTTPS base URL is required."),
                )
            }
            return
        }
        val controller = endurainController ?: return
        _state.update {
            it.copy(endurainEditor = editor.copy(saving = true, error = null))
        }
        endurainJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val profile = controller.saveConnection(
                    editor.existingConnectionIdentity,
                    editor.label.trim(),
                    editor.httpsBaseUrl.trim(),
                    editor.enabled,
                ).get()
                if (editor.apiKey.isNotEmpty()) {
                    controller.saveCredential(
                        profile.connectionIdentity,
                        editor.apiKey.toCharArray(),
                    ).get()
                }
            }.onSuccess {
                _state.update {
                    it.copy(
                        endurainEditor = null,
                        notice = "Endurain connection saved.",
                    )
                }
                refreshEndurain()
            }.onFailure {
                _state.update {
                    it.copy(
                        endurainEditor = it.endurainEditor?.copy(
                            saving = false,
                            apiKey = "",
                            error = "Endurain connection could not be saved.",
                        ),
                    )
                }
            }
        }
    }

    fun resetEndurainCredential(connectionIdentity: String) {
        runEndurainOperation("Endurain credential reset.") {
            it.resetCredential(connectionIdentity).get()
        }
    }

    fun queueEndurainPublish(connectionIdentity: String) {
        val key = _state.value.selectedKey
            ?: _state.value.history.firstOrNull()?.let {
                ActivityKey(it.sessionId, it.revision)
            }
        if (key == null) {
            _state.update {
                it.copy(notice = "Receive an activity before publishing to Endurain.")
            }
            return
        }
        _state.update {
            it.copy(notice = "Preparing Endurain TCX publish…")
        }
        runEndurainAction {
            it.queuePublish(connectionIdentity, key.sessionId, key.revision).get()
        }
    }

    fun endurainManualRetry(key: EndurainDeliveryKey) {
        runEndurainAction { it.manualRetry(key).get() }
    }

    fun endurainConfirmDuplicateRiskRetry(key: EndurainDeliveryKey) {
        runEndurainAction { it.confirmDuplicateRiskRetry(key).get() }
    }

    fun endurainRepairArtifact(key: EndurainDeliveryKey) {
        runEndurainAction { it.repairPreparedArtifact(key).get() }
    }

    fun endurainRemoteEdit(key: EndurainDeliveryKey) {
        runEndurainAction { it.requestRemoteEdit(key).get() }
    }

    fun endurainRemoteDelete(key: EndurainDeliveryKey) {
        runEndurainAction { it.requestRemoteDelete(key).get() }
    }

    fun openEndurainReconcile(key: EndurainDeliveryKey) {
        _state.update {
            it.copy(
                endurainReconcile = EndurainReconcileUi(deliveryKey = key),
                notice = null,
            )
        }
    }

    fun updateEndurainRemoteIds(value: String) {
        _state.update {
            it.copy(
                endurainReconcile = it.endurainReconcile?.copy(
                    remoteIds = value,
                    error = null,
                ),
            )
        }
    }

    fun cancelEndurainReconcile() {
        if (_state.value.endurainReconcile?.saving != true) {
            _state.update { it.copy(endurainReconcile = null) }
        }
    }

    fun reconcileEndurainRemoteIds() {
        val reconcile = _state.value.endurainReconcile ?: return
        val ids = runCatching {
            parseEndurainRemoteIds(reconcile.remoteIds)
        }.getOrElse { error ->
            _state.update {
                it.copy(
                    endurainReconcile = reconcile.copy(
                        error = error.message,
                    ),
                )
            }
            return
        }
        _state.update {
            it.copy(endurainReconcile = reconcile.copy(saving = true, error = null))
        }
        runEndurainAction(
            onSuccess = {
                _state.update { it.copy(endurainReconcile = null) }
            },
        ) {
            it.reconcileRemoteIds(reconcile.deliveryKey, ids).get()
        }
    }

    private fun initializeEndurain(
        factory: () -> CompletableFuture<EndurainPublishingController>,
    ) {
        _state.update { it.copy(endurain = EndurainUi(loading = true)) }
        endurainJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching { factory().get() }
                .onSuccess { controller ->
                    endurainController = controller
                    endurainObserver = controller.observe(
                        repositoryCallbackExecutor,
                        Runnable { refreshEndurain() },
                    )
                    refreshEndurain()
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            endurain = EndurainUi(
                                loading = false,
                                error = "Endurain publishing could not be initialized.",
                            ),
                        )
                    }
                }
        }
    }

    private fun refreshEndurain() {
        val controller = endurainController ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { controller.statusList().get() }
                .onSuccess { snapshot ->
                    _state.update {
                        it.copy(
                            endurain = EndurainUi(
                                loading = false,
                                snapshot = snapshot,
                            ),
                        )
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            endurain = it.endurain.copy(
                                loading = false,
                                error = "Endurain status could not be loaded.",
                            ),
                        )
                    }
                }
        }
    }

    private fun runEndurainAction(
        onSuccess: () -> Unit = {},
        operation: (EndurainPublishingController) -> EndurainActionResult,
    ) {
        val controller = endurainController ?: return
        endurainJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching { operation(controller) }
                .onSuccess { result ->
                    onSuccess()
                    _state.update {
                        it.copy(notice = endurainActionMessage(result))
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(notice = "Endurain action failed.")
                    }
                }
            refreshEndurain()
        }
    }

    private fun <T> runEndurainOperation(
        successMessage: String,
        operation: (EndurainPublishingController) -> T,
    ) {
        val controller = endurainController ?: return
        endurainJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching { operation(controller) }
                .onSuccess {
                    _state.update { it.copy(notice = successMessage) }
                }
                .onFailure {
                    _state.update { it.copy(notice = "Endurain action failed.") }
                }
            refreshEndurain()
        }
    }

    private fun endurainActionMessage(result: EndurainActionResult): String =
        result.message?.takeIf(String::isNotBlank)
            ?: when (result.code) {
                EndurainActionResult.Code.COMPLETED -> "Endurain action completed."
                EndurainActionResult.Code.QUEUED -> "Endurain publish queued."
                EndurainActionResult.Code.CREDENTIAL_REQUIRED ->
                    "Endurain credential is required."
                EndurainActionResult.Code.ARTIFACT_REPAIR_REQUIRED ->
                    "Endurain TCX artifact requires repair."
                EndurainActionResult.Code.RETRY_NOT_DUE ->
                    "Endurain retry is not due yet."
                EndurainActionResult.Code.RECONCILIATION_REQUIRED ->
                    "Endurain delivery requires manual reconciliation."
                EndurainActionResult.Code.UNSUPPORTED_REMOTE_EDIT ->
                    "Endurain remote edit is unsupported."
                EndurainActionResult.Code.UNSUPPORTED_REMOTE_DELETE ->
                    "Endurain remote delete is unsupported."
                EndurainActionResult.Code.INVALID_STATE ->
                    "Endurain action is invalid for the current state."
            }

    fun prepareSave() {
        val detail = _state.value.detail ?: return
        if (_state.value.exportBusy) {
            return
        }
        exportJob?.cancel()
        _state.update { it.copy(exportBusy = true, notice = null) }
        exportJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                tcxWriter.validate(detail.exportSource)
                exportFileName(detail.detail)
            }.onSuccess { fileName ->
                savedStateHandle[PENDING_EXPORT_SESSION_KEY] = detail.detail.sessionId
                savedStateHandle[PENDING_EXPORT_REVISION_KEY] = detail.detail.revision
                _state.update {
                    it.copy(
                        exportBusy = false,
                        event = PhoneUiEvent.CreateDocument(
                            id = eventIds.incrementAndGet(),
                            suggestedFileName = fileName,
                        ),
                    )
                }
            }.onFailure {
                _state.update {
                    it.copy(
                        exportBusy = false,
                        notice = "TCX export is unavailable for this activity.",
                    )
                }
            }
        }
    }

    fun saveToUri(
        resolver: ContentResolver,
        destination: Uri?,
    ) {
        if (destination == null) {
            exportCancelled()
            return
        }
        val key = pendingExportKey() ?: _state.value.selectedKey ?: return
        if (_state.value.exportBusy) {
            return
        }
        exportJob?.cancel()
        _state.update { it.copy(exportBusy = true, notice = null) }
        exportJob = viewModelScope.launch(Dispatchers.IO) {
            var removed = false
            runCatching {
                val source = exportSource(key)
                resolver.openOutputStream(destination, "wt").use { output ->
                    if (output == null) {
                        throw IOException("Selected destination cannot be opened")
                    }
                    val context = currentCoroutineContext()
                    tcxWriter.write(
                        source,
                        output,
                        TcxCancellation { !context.isActive },
                    )
                }
            }.onSuccess {
                clearPendingExport()
                _state.update {
                    it.copy(
                        exportBusy = false,
                        notice = "TCX saved successfully.",
                    )
                }
            }.onFailure {
                removed = runCatching {
                    resolver.delete(destination, null, null) > 0
                }.getOrDefault(false)
                clearPendingExport()
                _state.update {
                    it.copy(
                        exportBusy = false,
                        notice = if (removed) {
                            "TCX export failed; the incomplete destination was removed."
                        } else {
                            "TCX export failed. An incomplete destination may remain."
                        },
                    )
                }
            }
        }
    }

    fun exportCancelled() {
        clearPendingExport()
        _state.update { it.copy(notice = "TCX save cancelled.") }
    }

    fun prepareShare(sharedDirectory: File) {
        val key = _state.value.selectedKey ?: return
        if (_state.value.exportBusy) {
            return
        }
        exportJob?.cancel()
        _state.update { it.copy(exportBusy = true, notice = null) }
        exportJob = viewModelScope.launch(Dispatchers.IO) {
            var pending: File? = null
            runCatching {
                val source = exportSource(key)
                tcxWriter.validate(source)
                if (!sharedDirectory.exists() && !sharedDirectory.mkdirs()) {
                    throw IOException("Could not create TCX share directory")
                }
                if (!sharedDirectory.isDirectory) {
                    throw IOException("TCX share directory is unavailable")
                }
                val detail = requireDataSource().detail(key.sessionId, key.revision)
                    ?: throw IOException("Activity no longer exists")
                val destination = File(sharedDirectory, exportFileName(detail))
                pending = File(sharedDirectory, "${destination.name}.pending")
                FileOutputStream(pending, false).use { output ->
                    val context = currentCoroutineContext()
                    tcxWriter.write(
                        source,
                        output,
                        TcxCancellation { !context.isActive },
                    )
                    output.fd.sync()
                }
                if (!pending.renameTo(destination)) {
                    throw IOException("Could not finalize shared TCX")
                }
                sharedDirectory.listFiles { file ->
                    file.isFile && file.extension == "tcx" && file != destination
                }?.forEach(File::delete)
                destination
            }.onSuccess { file ->
                _state.update {
                    it.copy(
                        exportBusy = false,
                        event = PhoneUiEvent.ShareFile(
                            id = eventIds.incrementAndGet(),
                            file = file,
                            fileName = file.name,
                        ),
                    )
                }
            }.onFailure {
                pending?.delete()
                _state.update {
                    it.copy(
                        exportBusy = false,
                        notice = "TCX share file could not be generated.",
                    )
                }
            }
        }
    }

    fun shareLaunchFailed() {
        _state.update { it.copy(notice = "No compatible sharing app could be opened.") }
    }

    fun providerResolutionLaunchFailed() {
        _state.update {
            it.copy(notice = "The Health Connect provider page could not be opened.")
        }
    }

    fun consumeEvent(id: Long) {
        _state.update {
            if (it.event?.id == id) {
                it.copy(event = null)
            } else {
                it
            }
        }
    }

    fun consumeNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun handleBack(): Boolean {
        val current = _state.value
        return when {
            current.editor != null -> {
                cancelEditor()
                true
            }
            current.selectedKey != null -> {
                closeDetail()
                true
            }
            else -> false
        }
    }

    private fun loadInitial() {
        initialLoadJob?.cancel()
        initialLoadJob = viewModelScope.launch(Dispatchers.IO) {
            runBackfill()
            ensureRepositoryObserver()
            val page = runCatching {
                requireDataSource().summaries(0, HISTORY_PAGE_SIZE)
            }.getOrElse {
                _state.update {
                    it.copy(
                        historyLoading = false,
                        historyError = HISTORY_LOAD_ERROR,
                    )
                }
                emptyList()
            }
            val trends = runCatching { loadTrends() }.getOrNull()
            val status = runCatching { loadStatus() }
                .getOrElse {
                    StatusUi(
                        loading = false,
                        error = "Phone status could not be loaded.",
                    )
                }
            _state.update {
                it.copy(
                    history = page,
                    historyLoading = false,
                    historyHasMore = page.size == HISTORY_PAGE_SIZE,
                    trends = trends,
                    trendsLoading = false,
                    status = status,
                )
            }
            savedActivityKey()?.let { loadDetail(it) }
        }
    }

    private fun refreshAll(
        runBackfill: Boolean,
        refreshing: Boolean,
    ) {
        initialLoadJob?.cancel()
        _state.update {
            it.copy(
                historyRefreshing = refreshing,
                historyError = null,
            )
        }
        initialLoadJob = viewModelScope.launch(Dispatchers.IO) {
            if (runBackfill) {
                runBackfill()
            }
            refreshRepositoryViews()
        }
    }

    private suspend fun runBackfill() {
        _state.update {
            it.copy(backfill = it.backfill.copy(running = true, error = null))
        }
        runCatching {
            requireDataSource().backfill(System.currentTimeMillis())
        }.onSuccess { result ->
            _state.update {
                it.copy(
                    backfill = BackfillUi(
                        running = false,
                        sourceFiles = result.sourceFiles,
                        imported = result.imported,
                        alreadyProcessed = result.alreadyProcessed,
                        partialOrFailed = result.partialOrFailed,
                    ),
                )
            }
        }.onFailure {
            _state.update {
                it.copy(
                    backfill = it.backfill.copy(
                        running = false,
                        error = "Legacy activity recovery failed. Original files were retained.",
                    ),
                )
            }
        }
    }

    private fun scheduleRepositoryRefresh(immediate: Boolean = false) {
        repositoryRefreshJob?.cancel()
        repositoryRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            if (!immediate) {
                delay(200)
            }
            refreshRepositoryViews()
        }
    }

    private fun ensureRepositoryObserver() {
        if (repositoryObserver != null) {
            return
        }
        repositoryObserver = requireDataSource().observeChanges(
            repositoryCallbackExecutor,
            Runnable { scheduleRepositoryRefresh() },
        )
    }

    private suspend fun refreshRepositoryViews() {
        val current = _state.value
        val target = maxOf(HISTORY_PAGE_SIZE, current.history.size)
        val refreshedHistory = runCatching {
            loadSummaryRange(target)
        }.getOrElse {
            _state.update {
                it.copy(
                    historyRefreshing = false,
                    historyError = HISTORY_LOAD_ERROR,
                )
            }
            return
        }
        val trends = runCatching { loadTrends() }.getOrNull()
        val status = runCatching { loadStatus() }
            .getOrElse {
                current.status.copy(
                    loading = false,
                    error = "Phone status could not be refreshed.",
                )
            }
        _state.update {
            it.copy(
                history = refreshedHistory,
                historyLoading = false,
                historyRefreshing = false,
                historyHasMore = refreshedHistory.size >= target,
                trends = trends ?: it.trends,
                status = status,
            )
        }
        _state.value.selectedKey?.let { loadDetail(it) }
    }

    private fun loadDetail(key: ActivityKey) {
        detailJob?.cancel()
        _state.update {
            it.copy(
                selectedKey = key,
                detailLoading = true,
                detailError = null,
            )
        }
        detailJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val detail = requireDataSource().detail(key.sessionId, key.revision)
                    ?: throw IOException("Activity no longer exists")
                buildDetailUi(detail)
            }.onSuccess { detail ->
                if (_state.value.selectedKey != key) {
                    return@onSuccess
                }
                val editing = savedStateHandle.get<Boolean>(EDITING_KEY) == true
                _state.update {
                    it.copy(
                        detail = detail,
                        detailLoading = false,
                        detailError = null,
                        editor = if (editing) {
                            MetadataEditorUi(
                                title = savedStateHandle.get<String>(EDIT_TITLE_KEY)
                                    ?: detail.detail.metadata.title.orEmpty(),
                                notes = savedStateHandle.get<String>(EDIT_NOTES_KEY)
                                    ?: detail.detail.metadata.notes.orEmpty(),
                            )
                        } else {
                            it.editor
                        },
                    )
                }
            }.onFailure {
                if (_state.value.selectedKey == key) {
                    _state.update {
                        it.copy(
                            detail = null,
                            detailLoading = false,
                            detailError = "Activity details could not be loaded.",
                        )
                    }
                }
            }
        }
    }

    private fun buildDetailUi(
        detail: PhoneActivityRepository.ActivityDetail,
    ): ActivityDetailUi {
        val source = requireDataSource().exportSource(detail)
        val warnings = source.formatWarnings.toMutableList()
        runCatching { tcxWriter.assess(source) }
            .getOrNull()
            ?.placeholderFields
            ?.takeIf(List<String>::isNotEmpty)
            ?.let { placeholders ->
                warnings += "TCX requires marked placeholders for: ${placeholders.joinToString()}."
            }
        return ActivityDetailUi(
            detail = detail,
            exportSource = source,
            availability = availability(detail.activity),
            exportWarnings = warnings,
            routePreview = routePreview(detail.activity),
        )
    }

    private fun routePreview(activity: ActivityRecord): List<RoutePointUi> {
        val points = activity.samplesList.mapNotNull { sample ->
            if (sample.hasLocation() && sample.location.hasValue()) {
                RoutePointUi(
                    latitude = sample.location.value.latitudeDegrees,
                    longitude = sample.location.value.longitudeDegrees,
                )
            } else {
                null
            }
        }
        if (points.size <= ROUTE_PREVIEW_LIMIT) {
            return points
        }
        val step = (points.size - 1).toDouble() / (ROUTE_PREVIEW_LIMIT - 1)
        return List(ROUTE_PREVIEW_LIMIT) { index ->
            points[(index * step).toInt().coerceAtMost(points.lastIndex)]
        }
    }

    private fun refreshTrends() {
        trendJob?.cancel()
        _state.update { it.copy(trendsLoading = true, trendsError = null) }
        trendJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching { loadTrends() }
                .onSuccess { trends ->
                    _state.update {
                        it.copy(
                            trends = trends,
                            trendsLoading = false,
                            trendsError = null,
                        )
                    }
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            trendsLoading = false,
                            trendsError = "Trends could not be loaded.",
                        )
                    }
                }
        }
    }

    private fun loadTrends(): TrendsUi {
        val summaries = loadSummaryRange(TREND_ACTIVITY_LIMIT)
        val capped = summaries.size == TREND_ACTIVITY_LIMIT
        val totalDuration = summaries.sumOf {
            it.activeDurationMs ?: it.elapsedDurationMs
        }
        val totalDistance = summaries.mapNotNull { it.distanceM }.sum()
        val latest = summaries.firstOrNull()?.startTimeUtcMs
        val weekly = if (latest == null) {
            emptyList()
        } else {
            val latestWeek = weekStart(latest)
            (WEEK_BUCKETS - 1 downTo 0).map { distance ->
                val start = latestWeek - distance * WEEK_MILLIS
                val end = start + WEEK_MILLIS
                val activities = summaries.filter {
                    it.startTimeUtcMs in start until end
                }
                WeeklyTrendUi(
                    weekStartUtcMs = start,
                    activityCount = activities.size,
                    durationMs = activities.sumOf {
                        it.activeDurationMs ?: it.elapsedDurationMs
                    },
                    distanceM = activities.mapNotNull { it.distanceM }.sum(),
                )
            }
        }
        val byType = summaries
            .groupingBy { it.activityType }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { ActivityTypeTrendUi(it.key, it.value) }
        return TrendsUi(
            activityCount = summaries.size,
            totalDurationMs = totalDuration,
            totalDistanceM = totalDistance,
            weekly = weekly,
            byType = byType,
            capped = capped,
        )
    }

    private fun refreshStatus() {
        statusJob?.cancel()
        _state.update { it.copy(status = it.status.copy(loading = true, error = null)) }
        statusJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                loadStatus()
            }.onSuccess { status ->
                _state.update { it.copy(status = status) }
            }.onFailure {
                _state.update {
                    it.copy(
                        status = it.status.copy(
                            loading = false,
                            error = "Phone status could not be loaded.",
                        ),
                    )
                }
            }
        }
    }

    private suspend fun loadStatus(): StatusUi {
        val count = requireDataSource().summaryCount()
        val countCapped = count > STATUS_SCAN_LIMIT
        val summaries = loadSummaryRange(minOf(count, STATUS_SCAN_LIMIT.toLong()).toInt())
        val latestSummary = _state.value.selectedKey?.let { selected ->
            summaries.firstOrNull {
                it.sessionId == selected.sessionId && it.revision == selected.revision
            }
        } ?: summaries.firstOrNull()
        val latestAvailability = latestSummary?.let { summary ->
            requireDataSource().detail(summary.sessionId, summary.revision)
        }?.let { availability(it.activity) }.orEmpty()
        val healthConnect = latestSummary?.let { summary ->
            runCatching {
                requireHealthConnectProvider().refreshStatus(
                    summary.sessionId,
                    summary.revision,
                )
            }.getOrNull()
        }
        latestHealthConnectStatus = healthConnect
        val baseIntegrations = integrationGateway?.snapshot()
        val pendingTombstones = requireDataSource().pendingTombstones(500)
        return StatusUi(
            canonicalActivityCount = count,
            countCapped = countCapped,
            committedCount = summaries.count { it.transferState == "COMMITTED" }.toLong(),
            partialImportCount = summaries.count {
                it.importHealth != PhoneActivityRepository.IMPORT_COMPLETE
            }.toLong(),
            pendingDeleteSyncCount = pendingTombstones.count {
                it.state != PhoneActivityRepository.TOMBSTONE_COMMITTED
            },
            failedDeleteSyncCount = pendingTombstones.count {
                it.state == PhoneActivityRepository.TOMBSTONE_RETRYABLE
            },
            availability = latestAvailability,
            integrations = baseIntegrations?.copy(
                healthConnect = healthConnectConnectorStatus(
                    status = healthConnect,
                    hasActivity = latestSummary != null,
                ),
            ),
            loading = false,
            error = null,
        )
    }

    private fun availability(activity: ActivityRecord): List<MeasurementAvailabilityUi> {
        val statuses = linkedMapOf<String, com.long2know.utilities.contract.proto.MeasurementStatus>()
        if (activity.hasSummary()) {
            if (activity.summary.hasDistanceM()) {
                statuses["Distance"] = activity.summary.distanceM.status
            }
            if (activity.summary.hasCaloriesKcal()) {
                statuses["Calories"] = activity.summary.caloriesKcal.status
            }
        }
        for (sample in activity.samplesList) {
            if (!statuses.containsKey("Location") && sample.hasLocation()) {
                statuses["Location"] = sample.location.status
            }
            if (!statuses.containsKey("Heart rate") && sample.hasHeartRateBpm()) {
                statuses["Heart rate"] = sample.heartRateBpm.status
            }
            if (statuses.containsKey("Location")
                && statuses.containsKey("Heart rate")
                && statuses.size >= 4
            ) {
                break
            }
        }
        return statuses.map { (label, status) ->
            MeasurementAvailabilityUi(label, status)
        }
    }

    private fun loadSummaryRange(limit: Int): List<PhoneActivityRepository.ActivitySummary> {
        val source = requireDataSource()
        val result = ArrayList<PhoneActivityRepository.ActivitySummary>(limit)
        var offset = 0
        while (offset < limit) {
            val pageLimit = minOf(500, limit - offset)
            val page = source.summaries(offset, pageLimit)
            result += page
            offset += page.size
            if (page.size < pageLimit) {
                break
            }
        }
        return result
    }

    private fun exportSource(key: ActivityKey) =
        requireDataSource().detail(key.sessionId, key.revision)
            ?.let(requireDataSource()::exportSource)
            ?: throw IOException("Activity no longer exists")

    private fun exportFileName(
        detail: PhoneActivityRepository.ActivityDetail,
    ): String {
        val format = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return "SportLogger-${format.format(Date(detail.activity.startTimeUtcMs))}.tcx"
    }

    private fun metadataValidation(
        title: String,
        notes: String,
    ): String? =
        when {
            title.length > MAX_TITLE_CHARACTERS ->
                "Title must be $MAX_TITLE_CHARACTERS characters or fewer."
            notes.length > MAX_NOTES_CHARACTERS ->
                "Notes must be $MAX_NOTES_CHARACTERS characters or fewer."
            title.contains('\n') || title.contains('\r') ->
                "Title must be a single line."
            else -> null
        }

    private fun savedDestination(): PhoneDestination =
        savedStateHandle.get<String>(DESTINATION_KEY)
            ?.let { runCatching { PhoneDestination.valueOf(it) }.getOrNull() }
            ?: PhoneDestination.HISTORY

    private fun savedActivityKey(): ActivityKey? {
        val sessionId = savedStateHandle.get<String>(SELECTED_SESSION_KEY) ?: return null
        val revision = savedStateHandle.get<Long>(SELECTED_REVISION_KEY) ?: return null
        return ActivityKey(sessionId, revision)
    }

    private fun pendingExportKey(): ActivityKey? {
        val sessionId =
            savedStateHandle.get<String>(PENDING_EXPORT_SESSION_KEY) ?: return null
        val revision =
            savedStateHandle.get<Long>(PENDING_EXPORT_REVISION_KEY) ?: return null
        return ActivityKey(sessionId, revision)
    }

    private fun clearPendingExport() {
        savedStateHandle.remove<String>(PENDING_EXPORT_SESSION_KEY)
        savedStateHandle.remove<Long>(PENDING_EXPORT_REVISION_KEY)
    }

    private fun requireDataSource(): CanonicalPhoneDataSource =
        checkNotNull(dataSource) { "ComposePhoneViewModel is not initialized" }

    private fun requireHealthConnectProvider(): HealthConnectProvider =
        checkNotNull(healthConnectProvider) {
            "Health Connect provider is not initialized"
        }

    private fun healthConnectConnectorStatus(
        status: HealthConnectSessionStatus?,
        hasActivity: Boolean,
    ): com.long2know.sportlogger.integration.ConnectorUiStatus {
        if (!hasActivity) {
            return com.long2know.sportlogger.integration.ConnectorUiStatus(
                id = "health-connect",
                title = "Health Connect",
                state = com.long2know.sportlogger.integration.ConnectorUiState.NOT_CONFIGURED,
                detail = "Receive an activity before publishing to Health Connect.",
                actions = emptyList(),
            )
        }
        if (status == null) {
            return com.long2know.sportlogger.integration.ConnectorUiStatus(
                id = "health-connect",
                title = "Health Connect",
                state = com.long2know.sportlogger.integration.ConnectorUiState.FAILED,
                detail = "Health Connect status could not be loaded.",
                actions = emptyList(),
            )
        }
        val actions = buildList {
            if (status.canResolveProvider) {
                add(IntegrationAction(HEALTH_RESOLVE_ACTION, "Install or update provider"))
            }
            val canRequestPermissions = if (BuildConfig.DEBUG) {
                status.canRequestPermissions
            } else {
                status.availability.isAvailable && status.permissions.missing.isNotEmpty()
            }
            if (canRequestPermissions) {
                add(IntegrationAction(HEALTH_PERMISSION_ACTION, "Grant permissions"))
            }
            if (status.lastOperation == null && status.canPublish) {
                add(IntegrationAction(HEALTH_PUBLISH_ACTION, "Publish"))
            } else if (status.canUpdate) {
                add(IntegrationAction(HEALTH_UPDATE_ACTION, "Update"))
            }
            if (status.canDelete) {
                add(IntegrationAction(HEALTH_DELETE_ACTION, "Delete published records"))
            }
            if (status.canRetry) {
                add(IntegrationAction(HEALTH_RETRY_ACTION, "Retry"))
            }
            if (BuildConfig.DEBUG && status.canVerifyProvider && status.lastOperation != null) {
                add(IntegrationAction(HEALTH_VERIFY_ACTION, "Verify provider record"))
            }
        }
        val operationState = status.lastOperation?.state
        val state = when {
            !status.availability.isAvailable ->
                com.long2know.sportlogger.integration.ConnectorUiState.UNSUPPORTED
            status.permissions.missing.isNotEmpty() ->
                com.long2know.sportlogger.integration.ConnectorUiState.PERMISSION_REQUIRED
            operationState == HealthConnectOperationState.IN_PROGRESS ->
                com.long2know.sportlogger.integration.ConnectorUiState.WORKING
            operationState == HealthConnectOperationState.SUCCEEDED ->
                com.long2know.sportlogger.integration.ConnectorUiState.CONNECTED
            operationState == HealthConnectOperationState.PARTIAL ->
                com.long2know.sportlogger.integration.ConnectorUiState.PARTIAL
            operationState == HealthConnectOperationState.RETRYABLE_FAILURE ->
                com.long2know.sportlogger.integration.ConnectorUiState.RETRYABLE_ERROR
            operationState == HealthConnectOperationState.PERMANENT_FAILURE
                || operationState == HealthConnectOperationState.BLOCKED ->
                com.long2know.sportlogger.integration.ConnectorUiState.FAILED
            else -> com.long2know.sportlogger.integration.ConnectorUiState.AVAILABLE
        }
        val detail = buildString {
            append("Provider: ")
            append(status.availability.name.lowercase().replace('_', ' '))
            append(". Permissions: ")
            append(status.permissions.granted.size)
            append("/")
            append(status.permissions.required.size)
            append(" write granted.")
            if (BuildConfig.DEBUG) {
                append(" Verification read: ")
                append(status.readPermissions.granted.size)
                append("/")
                append(status.readPermissions.required.size)
                append(" granted.")
            }
            if (status.routePresent) {
                append(" Route data is present.")
            }
            status.lastOperation?.let { operation ->
                append(" Last ")
                append(operation.kind.name.lowercase())
                append(": ")
                append(operation.state.name.lowercase().replace('_', ' '))
                append(".")
                if (operation.missingPermissions.isNotEmpty()) {
                    append(" Missing ")
                    append(operation.missingPermissions.size)
                    append(" permission(s).")
                }
                latestHealthVerification?.let { verification ->
                    append(" Verification: ")
                    append(verification.state.name.lowercase())
                    append(".")
                    verification.providerRoutePointCount?.let { count ->
                        append(" Provider route points: ")
                        append(count)
                        append(".")
                    }
                }
            }
        }
        return com.long2know.sportlogger.integration.ConnectorUiStatus(
            id = "health-connect",
            title = "Health Connect",
            state = state,
            detail = detail,
            actions = actions,
        )
    }

    private fun setHealthConnectWorking() {
        _state.update { state ->
            val integrations = state.status.integrations ?: return@update state
            state.copy(
                status = state.status.copy(
                    integrations = integrations.copy(
                        healthConnect = integrations.healthConnect.copy(
                            state = com.long2know.sportlogger.integration.ConnectorUiState.WORKING,
                            detail = "Health Connect operation is running.",
                            actions = emptyList(),
                        ),
                    ),
                ),
            )
        }
    }

    private fun healthConnectResultMessage(result: HealthConnectOperationResult): String {
        val operation = when (result.kind) {
            HealthConnectOperationKind.PUBLISH -> "publish"
            HealthConnectOperationKind.UPDATE -> "update"
            HealthConnectOperationKind.DELETE -> "delete"
        }
        return when (result.state) {
            HealthConnectOperationState.SUCCEEDED ->
                "Health Connect $operation completed."
            HealthConnectOperationState.PARTIAL ->
                "Health Connect $operation completed partially; review missing permissions or failed records."
            HealthConnectOperationState.BLOCKED ->
                "Health Connect $operation is blocked."
            HealthConnectOperationState.RETRYABLE_FAILURE ->
                "Health Connect $operation failed and can be retried."
            HealthConnectOperationState.PERMANENT_FAILURE ->
                "Health Connect $operation failed permanently."
            HealthConnectOperationState.IN_PROGRESS ->
                "Health Connect $operation is still in progress."
            HealthConnectOperationState.NOT_STARTED ->
                "Health Connect $operation did not start."
        }
    }

    private fun healthConnectVerificationMessage(
        result: HealthConnectVerificationResult,
    ): String =
        when (result.state) {
            HealthConnectVerificationState.PRESENT ->
                "Provider query verified the owned exercise record."
            HealthConnectVerificationState.ABSENT ->
                "Provider query verified the owned exercise record is absent."
            HealthConnectVerificationState.BLOCKED ->
                "Provider verification is blocked by availability or permission."
            HealthConnectVerificationState.MISMATCH ->
                "Provider verification found a record mismatch."
            HealthConnectVerificationState.FAILED ->
                "Provider verification failed."
        }

    private fun weekStart(timestampUtcMs: Long): Long =
        Instant.ofEpochMilli(timestampUtcMs)
            .atZone(ZoneOffset.UTC)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .toLocalDate()
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()

    override fun onCleared() {
        exportJob?.cancel()
        runCatching { repositoryObserver?.close() }
        runCatching { integrationObserver?.close() }
        runCatching { healthConnectObserver?.close() }
        runCatching { healthConnectProvider?.close() }
        runCatching { endurainObserver?.close() }
        runCatching { endurainController?.close() }
        runCatching { dataSource?.close() }
        super.onCleared()
    }

    companion object {
        const val MAX_TITLE_CHARACTERS = PhoneActivityRepository.MAX_TITLE_LENGTH
        const val MAX_NOTES_CHARACTERS = PhoneActivityRepository.MAX_NOTES_LENGTH

        internal fun parseEndurainRemoteIds(value: String): List<Long> {
            val tokens = value
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
            if (tokens.isEmpty()) {
                throw IllegalArgumentException("Enter at least one positive remote ID.")
            }
            val remoteIds = LinkedHashSet<Long>(tokens.size)
            for (token in tokens) {
                val remoteId = token.toLongOrNull()
                if (remoteId == null || remoteId <= 0L) {
                    throw IllegalArgumentException(
                        "Each remote ID must be a comma-separated positive integer " +
                            "between 1 and ${Long.MAX_VALUE}.",
                    )
                }
                remoteIds += remoteId
            }
            return remoteIds.toList()
        }

        private const val HISTORY_PAGE_SIZE = 50
        private const val TREND_ACTIVITY_LIMIT = 5_000
        private const val STATUS_SCAN_LIMIT = 5_000
        private const val WEEK_BUCKETS = 12
        private const val ROUTE_PREVIEW_LIMIT = 500
        private const val WEEK_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        private const val HISTORY_LOAD_ERROR =
            "Activity history could not be loaded. Stored activities were not changed."

        private const val DESTINATION_KEY = "phone.destination"
        private const val SELECTED_SESSION_KEY = "phone.selectedSession"
        private const val SELECTED_REVISION_KEY = "phone.selectedRevision"
        private const val EDITING_KEY = "phone.editing"
        private const val EDIT_TITLE_KEY = "phone.editTitle"
        private const val EDIT_NOTES_KEY = "phone.editNotes"
        private const val PENDING_EXPORT_SESSION_KEY = "phone.pendingExportSession"
        private const val PENDING_EXPORT_REVISION_KEY = "phone.pendingExportRevision"
        private const val HEALTH_PERMISSION_REQUEST_KEY =
            "phone.healthConnectPermissionRequest"
        private const val HEALTH_ACTION_PREFIX = "health-connect."
        private const val HEALTH_RESOLVE_ACTION = "health-connect.resolve"
        private const val HEALTH_PERMISSION_ACTION = "health-connect.permissions"
        private const val HEALTH_PUBLISH_ACTION = "health-connect.publish"
        private const val HEALTH_UPDATE_ACTION = "health-connect.update"
        private const val HEALTH_DELETE_ACTION = "health-connect.delete"
        private const val HEALTH_RETRY_ACTION = "health-connect.retry"
        private const val HEALTH_VERIFY_ACTION = "health-connect.verify"
    }
}
