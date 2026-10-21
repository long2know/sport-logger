package com.long2know.sportlogger

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.long2know.sportlogger.integration.IntegrationAction
import com.long2know.sportlogger.integration.IntegrationActionResult
import com.long2know.sportlogger.integration.PhoneIntegrationStatusGateway
import com.long2know.sportlogger.phone.ActivityDetailUi
import com.long2know.sportlogger.phone.ActivityKey
import com.long2know.sportlogger.phone.ActivityTypeTrendUi
import com.long2know.sportlogger.phone.BackfillUi
import com.long2know.sportlogger.phone.CanonicalPhoneDataSource
import com.long2know.sportlogger.phone.MeasurementAvailabilityUi
import com.long2know.sportlogger.phone.MetadataEditorUi
import com.long2know.sportlogger.phone.PhoneDestination
import com.long2know.sportlogger.phone.PhoneUiEvent
import com.long2know.sportlogger.phone.PhoneUiState
import com.long2know.sportlogger.phone.StatusUi
import com.long2know.sportlogger.phone.TrendsUi
import com.long2know.sportlogger.phone.WeeklyTrendUi
import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository
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

    private var dataSource: CanonicalPhoneDataSource? = null
    private var integrationGateway: PhoneIntegrationStatusGateway? = null
    private var repositoryObserver: AutoCloseable? = null
    private var integrationObserver: AutoCloseable? = null
    private var initialLoadJob: Job? = null
    private var repositoryRefreshJob: Job? = null
    private var detailJob: Job? = null
    private var trendJob: Job? = null
    private var statusJob: Job? = null
    private var exportJob: Job? = null

    fun initialize(
        dataSourceFactory: () -> CanonicalPhoneDataSource,
        integrationGateway: PhoneIntegrationStatusGateway,
    ) {
        if (dataSource != null) {
            return
        }
        dataSource = dataSourceFactory()
        this.integrationGateway = integrationGateway
        val callbackExecutor = Executor { command ->
            viewModelScope.launch(Dispatchers.IO) {
                command.run()
            }
        }
        repositoryObserver = requireDataSource().observeChanges(
            callbackExecutor,
            Runnable { scheduleRepositoryRefresh() },
        )
        integrationObserver = integrationGateway.observe {
            refreshStatus()
        }
        loadInitial()
    }

    fun refreshFromReceipt() {
        refreshAll(runBackfill = true, refreshing = true)
    }

    fun onActivityResumed() {
        refreshStatus()
    }

    fun refreshHistory() {
        refreshAll(runBackfill = false, refreshing = true)
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
            val status = runCatching { loadStatus(trends?.activityCount ?: page.size) }
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
        val status = runCatching { loadStatus(trends?.activityCount ?: refreshedHistory.size) }
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
        )
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
                loadStatus(_state.value.trends?.activityCount)
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

    private fun loadStatus(knownCount: Int? = null): StatusUi {
        val count = requireDataSource().summaryCount()
        val summaries = loadSummaryRange(minOf(count, STATUS_SCAN_LIMIT.toLong()).toInt())
        val latestAvailability = summaries.firstOrNull()?.let { summary ->
            requireDataSource().detail(summary.sessionId, summary.revision)
        }?.let { availability(it.activity) }.orEmpty()
        return StatusUi(
            canonicalActivityCount = count,
            countCapped = false,
            committedCount = summaries.count { it.transferState == "COMMITTED" },
            partialImportCount = summaries.count {
                it.importHealth != PhoneActivityRepository.IMPORT_COMPLETE
            },
            availability = latestAvailability,
            integrations = integrationGateway?.snapshot(),
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
        runCatching { dataSource?.close() }
        super.onCleared()
    }

    companion object {
        const val MAX_TITLE_CHARACTERS = PhoneActivityRepository.MAX_TITLE_LENGTH
        const val MAX_NOTES_CHARACTERS = PhoneActivityRepository.MAX_NOTES_LENGTH

        private const val HISTORY_PAGE_SIZE = 50
        private const val TREND_ACTIVITY_LIMIT = 5_000
        private const val STATUS_SCAN_LIMIT = 500
        private const val WEEK_BUCKETS = 12
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
    }
}
