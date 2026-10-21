package com.long2know.sportlogger.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.long2know.sportlogger.ComposePhoneViewModel
import com.long2know.sportlogger.R
import com.long2know.sportlogger.integration.ConnectorUiState
import com.long2know.sportlogger.integration.ConnectorUiStatus
import com.long2know.sportlogger.integration.IntegrationAction
import com.long2know.sportlogger.phone.ActivityDetailUi
import com.long2know.sportlogger.phone.ActivityTypeTrendUi
import com.long2know.sportlogger.phone.MeasurementAvailabilityUi
import com.long2know.sportlogger.phone.MetadataEditorUi
import com.long2know.sportlogger.phone.PhoneDestination
import com.long2know.sportlogger.phone.PhoneUiEvent
import com.long2know.sportlogger.phone.PhoneUiState
import com.long2know.sportlogger.phone.StatusUi
import com.long2know.sportlogger.phone.TrendsUi
import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository
import com.long2know.utilities.contract.proto.ActivityType
import com.long2know.utilities.contract.proto.MeasurementStatus
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

@Composable
fun SportLoggerPhoneRoute(
    viewModel: ComposePhoneViewModel,
    onPrepareShare: () -> Unit,
    onEvent: (PhoneUiEvent) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.event?.id) {
        state.event?.let(onEvent)
    }
    SportLoggerTheme {
        SportLoggerPhoneApp(
            state = state,
            onDestination = viewModel::selectDestination,
            onRefresh = viewModel::refreshHistory,
            onLoadMore = viewModel::loadMoreHistory,
            onSelectActivity = viewModel::selectActivity,
            onBack = viewModel::handleBack,
            onOpenEditor = viewModel::openEditor,
            onRequestDelete = viewModel::requestDelete,
            onConfirmDelete = viewModel::confirmDelete,
            onCancelDelete = viewModel::cancelDelete,
            onEditorTitle = viewModel::updateEditorTitle,
            onEditorNotes = viewModel::updateEditorNotes,
            onSaveMetadata = viewModel::saveMetadata,
            onCancelEditor = viewModel::cancelEditor,
            onPrepareSave = viewModel::prepareSave,
            onPrepareShare = onPrepareShare,
            onRetryBackfill = viewModel::retryBackfill,
            onRetryDeleteSync = viewModel::retryDeleteSync,
            onIntegrationAction = viewModel::performIntegrationAction,
            onConsumeNotice = viewModel::consumeNotice,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SportLoggerPhoneApp(
    state: PhoneUiState,
    onDestination: (PhoneDestination) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onSelectActivity: (String, Long) -> Unit,
    onBack: () -> Boolean,
    onOpenEditor: () -> Unit,
    onRequestDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onEditorTitle: (String) -> Unit,
    onEditorNotes: (String) -> Unit,
    onSaveMetadata: () -> Unit,
    onCancelEditor: () -> Unit,
    onPrepareSave: () -> Unit,
    onPrepareShare: () -> Unit,
    onRetryBackfill: () -> Unit,
    onRetryDeleteSync: () -> Unit,
    onIntegrationAction: (IntegrationAction) -> Unit,
    onConsumeNotice: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            onConsumeNotice()
        }
    }
    BackHandler(enabled = state.selectedKey != null || state.editor != null) {
        onBack()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoPane = maxWidth >= 840.dp
        val detailVisible = state.selectedKey != null
        Scaffold(
            topBar = {
                PhoneTopBar(
                    state = state,
                    showBack = detailVisible && !twoPane,
                    onBack = onBack,
                    onRefresh = onRefresh,
                    onOpenEditor = onOpenEditor,
                )
            },
            bottomBar = {
                if (!detailVisible || twoPane) {
                    PhoneNavigationBar(
                        destination = state.destination,
                        onDestination = onDestination,
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { innerPadding ->
            if (state.destination == PhoneDestination.HISTORY && twoPane) {
                Row(
                    modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize(),
                ) {
                    HistoryScreen(
                        state = state,
                        onRefresh = onRefresh,
                        onLoadMore = onLoadMore,
                        onSelectActivity = onSelectActivity,
                        modifier = Modifier
                            .weight(0.44f)
                            .fillMaxHeight(),
                    )
                    VerticalDivider(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp),
                    )
                    if (detailVisible) {
                        DetailScreen(
                            state = state,
                            onPrepareSave = onPrepareSave,
                            onPrepareShare = onPrepareShare,
                            onRequestDelete = onRequestDelete,
                            modifier = Modifier
                                .weight(0.56f)
                                .fillMaxHeight(),
                        )
                    } else {
                        EmptySelection(
                            modifier = Modifier
                                .weight(0.56f)
                                .fillMaxHeight(),
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize(),
                ) {
                    when {
                        detailVisible -> DetailScreen(
                            state = state,
                            onPrepareSave = onPrepareSave,
                            onPrepareShare = onPrepareShare,
                            onRequestDelete = onRequestDelete,
                        )
                        state.destination == PhoneDestination.HISTORY -> HistoryScreen(
                            state = state,
                            onRefresh = onRefresh,
                            onLoadMore = onLoadMore,
                            onSelectActivity = onSelectActivity,
                        )
                        state.destination == PhoneDestination.TRENDS -> TrendsScreen(state)
                        state.destination == PhoneDestination.STATUS -> StatusScreen(
                            state = state,
                            onRetryBackfill = onRetryBackfill,
                            onRetryDeleteSync = onRetryDeleteSync,
                            onIntegrationAction = onIntegrationAction,
                        )
                    }
                }
            }
        }
    }

    state.editor?.let { editor ->
        MetadataEditorDialog(
            editor = editor,
            onTitleChange = onEditorTitle,
            onNotesChange = onEditorNotes,
            onSave = onSaveMetadata,
            onCancel = onCancelEditor,
        )
    }
    if (state.deleteConfirmation) {
        DeleteConfirmationDialog(
            deleting = state.deleteBusy,
            onConfirm = onConfirmDelete,
            onCancel = onCancelDelete,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneTopBar(
    state: PhoneUiState,
    showBack: Boolean,
    onBack: () -> Boolean,
    onRefresh: () -> Unit,
    onOpenEditor: () -> Unit,
) {
    val title = when {
        state.selectedKey != null -> stringResource(R.string.activity_details_title)
        state.destination == PhoneDestination.TRENDS -> stringResource(R.string.trends_title)
        state.destination == PhoneDestination.STATUS -> stringResource(R.string.status_title)
        else -> stringResource(R.string.history_title)
    }
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (showBack) {
                IconButton(onClick = { onBack() }) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.back_content_description),
                    )
                }
            }
        },
        actions = {
            if (state.selectedKey != null && state.detail != null) {
                IconButton(onClick = onOpenEditor) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.edit_content_description),
                    )
                }
            } else if (state.destination == PhoneDestination.HISTORY) {
                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = stringResource(
                            R.string.refresh_content_description,
                        ),
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

@Composable
private fun PhoneNavigationBar(
    destination: PhoneDestination,
    onDestination: (PhoneDestination) -> Unit,
) {
    NavigationBar {
        NavigationItem(
            selected = destination == PhoneDestination.HISTORY,
            label = stringResource(R.string.nav_history),
            icon = Icons.Outlined.History,
            onClick = { onDestination(PhoneDestination.HISTORY) },
        )
        NavigationItem(
            selected = destination == PhoneDestination.TRENDS,
            label = stringResource(R.string.nav_trends),
            icon = Icons.AutoMirrored.Outlined.ShowChart,
            onClick = { onDestination(PhoneDestination.TRENDS) },
        )
        NavigationItem(
            selected = destination == PhoneDestination.STATUS,
            label = stringResource(R.string.nav_status),
            icon = Icons.Outlined.Storage,
            onClick = { onDestination(PhoneDestination.STATUS) },
        )
    }
}

@Composable
private fun RowScope.NavigationItem(
    selected: Boolean,
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label) },
    )
}

@Composable
private fun HistoryScreen(
    state: PhoneUiState,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onSelectActivity: (String, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.historyLoading && state.history.isEmpty() -> LoadingPane(modifier)
        state.historyError != null && state.history.isEmpty() -> ErrorPane(
            message = state.historyError,
            onRetry = onRefresh,
            modifier = modifier,
        )
        state.history.isEmpty() -> EmptyPane(
            title = stringResource(R.string.history_empty),
            modifier = modifier,
        )
        else -> LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .testTag("history_screen"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.historyError != null) {
                item {
                    InlineWarning(state.historyError)
                }
            }
            itemsIndexed(
                items = state.history,
                key = { _, summary -> "${summary.sessionId}:${summary.revision}" },
            ) { index, summary ->
                if (index == state.history.lastIndex && state.historyHasMore) {
                    LaunchedEffect(index, state.history.size) {
                        onLoadMore()
                    }
                }
                ActivitySummaryCard(
                    summary = summary,
                    onClick = {
                        onSelectActivity(summary.sessionId, summary.revision)
                    },
                )
            }
            if (state.historyRefreshing) {
                item {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if (state.historyHasMore) {
                item {
                    OutlinedButton(
                        onClick = onLoadMore,
                        enabled = !state.historyRefreshing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.load_more))
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivitySummaryCard(
    summary: PhoneActivityRepository.ActivitySummary,
    onClick: () -> Unit,
) {
    val title = summary.title?.takeIf(String::isNotBlank)
        ?: activityTypeLabel(summary.activityType)
    val description = stringResource(
        R.string.history_row_description,
        title,
        formatDateTime(summary.startTimeUtcMs),
        formatDuration(summary.activeDurationMs ?: summary.elapsedDurationMs),
    )
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("activity_row_${summary.sessionId}")
            .semantics { contentDescription = description },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatDateTime(summary.startTimeUtcMs),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetricText(
                    formatDuration(summary.activeDurationMs ?: summary.elapsedDurationMs),
                )
                MetricText(formatDistance(summary.distanceM))
                MetricText("${summary.sampleCount} samples")
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusChip(
                    text = if (summary.transferState == "COMMITTED") {
                        "Received"
                    } else {
                        humanize(summary.transferState)
                    },
                    warning = summary.transferState != "COMMITTED",
                )
                if (summary.importHealth != PhoneActivityRepository.IMPORT_COMPLETE) {
                    StatusChip(
                        text = humanize(summary.importHealth),
                        warning = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StatusChip(
    text: String,
    warning: Boolean = false,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (warning) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (warning) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (warning) Icons.Outlined.Warning else Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun DetailScreen(
    state: PhoneUiState,
    onPrepareSave: () -> Unit,
    onPrepareShare: () -> Unit,
    onRequestDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.detailLoading -> LoadingPane(modifier)
        state.detailError != null -> ErrorPane(
            message = state.detailError,
            onRetry = {},
            modifier = modifier,
            showRetry = false,
        )
        state.detail == null -> EmptySelection(modifier)
        else -> ActivityDetailContent(
            detail = state.detail,
            exportBusy = state.exportBusy,
            deleteBusy = state.deleteBusy,
            onPrepareSave = onPrepareSave,
            onPrepareShare = onPrepareShare,
            onRequestDelete = onRequestDelete,
            modifier = modifier,
        )
    }
}

@Composable
private fun ActivityDetailContent(
    detail: ActivityDetailUi,
    exportBusy: Boolean,
    deleteBusy: Boolean,
    onPrepareSave: () -> Unit,
    onPrepareShare: () -> Unit,
    onRequestDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = detail.detail.activity
    val metadata = detail.detail.metadata
    val title = metadata.title?.takeIf(String::isNotBlank)
        ?: activity.recordedName.takeIf { activity.hasRecordedName() && it.isNotBlank() }
        ?: activityTypeLabel(activity.activityTypeValue)
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("detail_screen")
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip("Revision ${detail.detail.revision}")
            StatusChip(activityTypeLabel(activity.activityTypeValue))
        }
        MetricCard(
            entries = listOf(
                stringResource(R.string.detail_start_label) to
                    formatDateTime(activity.startTimeUtcMs),
                stringResource(R.string.detail_end_label) to
                    formatDateTime(activity.endTimeUtcMs),
                stringResource(R.string.elapsed_duration_label) to
                    formatDuration(activity.elapsedDurationMs),
                stringResource(R.string.active_duration_label) to
                    if (activity.hasActiveDurationMs()) {
                        formatDuration(activity.activeDurationMs)
                    } else {
                        stringResource(R.string.value_unavailable)
                    },
                stringResource(R.string.detail_distance_label) to
                    formatDistance(
                        activity.summary.distanceM.value.takeIf {
                            activity.hasSummary()
                                && activity.summary.hasDistanceM()
                                && activity.summary.distanceM.hasValue()
                        },
                    ),
                stringResource(R.string.detail_samples_label) to
                    "${activity.samplesCount}",
                stringResource(R.string.detail_route_label) to
                    "${activity.summary.routePointCount}",
                stringResource(R.string.detail_heart_rate_label) to
                    "${activity.summary.heartRateSampleCount}",
            ),
        )
        if (detail.routePreview.size >= 2) {
            RoutePreview(detail)
        }
        if (metadata.notes?.isNotBlank() == true) {
            TextSection(
                title = stringResource(R.string.phone_notes_label),
                value = metadata.notes,
            )
        }

        if (activity.hasRecordedDescription() && activity.recordedDescription.isNotBlank()) {
            TextSection(
                title = stringResource(R.string.recorded_description_label),
                value = activity.recordedDescription,
            )
        }
        if (metadata.tags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                metadata.tags.forEach { tag -> StatusChip(tag) }
            }
        }
        if (activity.lapsCount > 0) {
            LapsSection(detail)
        }
        AvailabilitySection(detail.availability)
        if (detail.exportWarnings.isNotEmpty()) {
            WarningCard(
                title = stringResource(R.string.export_limitations_title),
                lines = detail.exportWarnings,
            )
        }
        Text(
            text = stringResource(R.string.export_section_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        if (exportBusy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.export_working))
        }
        if (deleteBusy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.delete_working))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onPrepareSave,
                enabled = !exportBusy && !deleteBusy,
                modifier = Modifier.testTag("save_tcx"),
            ) {
                Text(stringResource(R.string.save_tcx))
            }
            OutlinedButton(
                onClick = onPrepareShare,
                enabled = !exportBusy && !deleteBusy,
                modifier = Modifier.testTag("share_tcx"),
            ) {
                Icon(Icons.Outlined.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.share_tcx))
            }
        }
        OutlinedButton(
            onClick = onRequestDelete,
            enabled = !exportBusy && !deleteBusy,
            modifier = Modifier.testTag("delete_activity"),
        ) {
            Icon(Icons.Outlined.Delete, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.delete_activity))
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun RoutePreview(detail: ActivityDetailUi) {
    val points = detail.routePreview
    val lineColor = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surfaceVariant
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.route_preview_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            val description = stringResource(
                R.string.route_preview_description,
                points.size,
            )
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .semantics { contentDescription = description },
            ) {
                drawRect(background)
                val minLatitude = points.minOf { it.latitude }
                val maxLatitude = points.maxOf { it.latitude }
                val minLongitude = points.minOf { it.longitude }
                val maxLongitude = points.maxOf { it.longitude }
                val latitudeRange = (maxLatitude - minLatitude).takeIf { it > 0.0 } ?: 1.0
                val longitudeRange = (maxLongitude - minLongitude).takeIf { it > 0.0 } ?: 1.0
                val padding = 12.dp.toPx()
                val drawableWidth = (size.width - padding * 2).coerceAtLeast(1f)
                val drawableHeight = (size.height - padding * 2).coerceAtLeast(1f)
                val path = Path()
                points.forEachIndexed { index, point ->
                    val x = padding +
                        ((point.longitude - minLongitude) / longitudeRange).toFloat() *
                        drawableWidth
                    val y = padding +
                        (1f - ((point.latitude - minLatitude) / latitudeRange).toFloat()) *
                        drawableHeight
                    if (index == 0) {
                        path.moveTo(x, y)
                    } else {
                        path.lineTo(x, y)
                    }
                }
                drawPath(
                    path = path,
                    color = lineColor,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 4.dp.toPx(),
                    ),
                )
            }
        }
    }
}

@Composable
private fun MetricCard(entries: List<Pair<String, String>>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            entries.forEach { (label, value) ->
                ListItem(
                    headlineContent = { Text(value) },
                    overlineContent = { Text(label) },
                )
            }
        }
    }
}

@Composable
private fun TextSection(
    title: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun LapsSection(detail: ActivityDetailUi) {
    Text(
        text = stringResource(R.string.laps_label),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() },
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        detail.detail.activity.lapsList.forEach { lap ->
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Lap ${lap.index + 1}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "${formatDuration(lap.activeDurationMs.takeIf {
                            lap.hasActiveDurationMs()
                        } ?: lap.elapsedDurationMs)} • " +
                            formatDistance(
                                lap.summary.distanceM.value.takeIf {
                                    lap.hasSummary()
                                        && lap.summary.hasDistanceM()
                                        && lap.summary.distanceM.hasValue()
                                },
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun AvailabilitySection(availability: List<MeasurementAvailabilityUi>) {
    Text(
        text = stringResource(R.string.availability_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() },
    )
    if (availability.isEmpty()) {
        Text(stringResource(R.string.no_availability_evidence))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        availability.forEach { item ->
            AvailabilityCard(item)
        }
    }
}

@Composable
private fun AvailabilityCard(item: MeasurementAvailabilityUi) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text("Permission: ${humanize(item.status.runtimePermission.name)}")
            Text("Capability: ${humanize(item.status.exerciseCapability.name)}")
            Text("Availability: ${humanize(item.status.liveAvailability.name)}")
            Text("Source: ${humanize(item.status.source.kind.name)}")
        }
    }
}

@Composable
private fun WarningCard(
    title: String,
    lines: List<String>,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Warning, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            lines.forEach { Text(it) }
        }
    }
}

@Composable
private fun TrendsScreen(state: PhoneUiState) {
    when {
        state.trendsLoading && state.trends == null -> LoadingPane()
        state.trendsError != null && state.trends == null -> ErrorPane(
            message = state.trendsError,
            onRetry = {},
            showRetry = false,
        )
        state.trends == null || state.trends.activityCount == 0 -> EmptyPane(
            title = stringResource(R.string.trends_empty),
        )
        else -> TrendsContent(state.trends)
    }
}

@Composable
private fun TrendsContent(trends: TrendsUi) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("trends_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TrendTotalCard(
                    label = stringResource(R.string.trend_total_activities),
                    value = trends.activityCount.toString(),
                    modifier = Modifier.weight(1f),
                )
                TrendTotalCard(
                    label = stringResource(R.string.trend_total_distance),
                    value = formatDistance(trends.totalDistanceM),
                    modifier = Modifier.weight(1f),
                )
                TrendTotalCard(
                    label = stringResource(R.string.trend_total_time),
                    value = formatDuration(trends.totalDurationMs),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (trends.capped) {
            item { InlineWarning(stringResource(R.string.trend_capped)) }
        }
        item {
            Text(
                text = stringResource(R.string.trend_weekly),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
        }
        val maxWeekly = trends.weekly.maxOfOrNull { it.durationMs }?.coerceAtLeast(1L) ?: 1L
        items(trends.weekly, key = { it.weekStartUtcMs }) { week ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(formatWeek(week.weekStartUtcMs))
                    Text("${week.activityCount} • ${formatDuration(week.durationMs)}")
                }
                LinearProgressIndicator(
                    progress = { week.durationMs.toFloat() / maxWeekly.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            Text(
                text = stringResource(R.string.trend_by_type),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
        }
        items(trends.byType, key = ActivityTypeTrendUi::activityType) { type ->
            ListItem(
                headlineContent = { Text(activityTypeLabel(type.activityType)) },
                trailingContent = { Text(type.count.toString()) },
            )
        }
    }
}

@Composable
private fun TrendTotalCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(modifier) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun StatusScreen(
    state: PhoneUiState,
    onRetryBackfill: () -> Unit,
    onRetryDeleteSync: () -> Unit,
    onIntegrationAction: (IntegrationAction) -> Unit,
) {
    val status = state.status
    if (status.loading && status.integrations == null) {
        LoadingPane()
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("status_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        status.error?.let { error ->
            item { InlineWarning(error) }
        }
        item {
            StatusSummaryCard(
                status = status,
                backfill = state.backfill,
                onRetryBackfill = onRetryBackfill,
                onRetryDeleteSync = onRetryDeleteSync,
            )
        }
        item {
            Text(
                text = stringResource(R.string.permission_status_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (status.availability.isEmpty()) {
            item { Text(stringResource(R.string.no_availability_evidence)) }
        } else {
            items(status.availability, key = MeasurementAvailabilityUi::label) {
                AvailabilityCard(it)
            }
        }
        item {
            Text(
                text = stringResource(R.string.connector_status_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
        }
        status.integrations?.let { integrations ->
            item {
                ConnectorStatusCard(
                    connector = integrations.healthConnect,
                    onAction = onIntegrationAction,
                )
            }
            item {
                ConnectorStatusCard(
                    connector = integrations.endurain,
                    onAction = onIntegrationAction,
                )
            }
        }
    }
}

@Composable
private fun StatusSummaryCard(
    status: StatusUi,
    backfill: com.long2know.sportlogger.phone.BackfillUi,
    onRetryBackfill: () -> Unit,
    onRetryDeleteSync: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.canonical_storage_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (status.countCapped) {
                    stringResource(
                        R.string.status_activity_count_capped,
                        status.canonicalActivityCount,
                    )
                } else {
                    stringResource(
                        R.string.status_activity_count,
                        status.canonicalActivityCount,
                    )
                },
            )
            Text(
                stringResource(
                    R.string.status_committed_count,
                    status.committedCount,
                ),
            )
            if (status.partialImportCount > 0) {
                Text(
                    stringResource(
                        R.string.status_partial_count,
                        status.partialImportCount,
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (status.pendingDeleteSyncCount > 0) {
                Text(
                    stringResource(
                        R.string.status_pending_delete_count,
                        status.pendingDeleteSyncCount,
                    ),
                )
            }
            if (status.failedDeleteSyncCount > 0) {
                Text(
                    stringResource(
                        R.string.status_failed_delete_count,
                        status.failedDeleteSyncCount,
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = onRetryDeleteSync) {
                    Text(stringResource(R.string.retry_delete_sync))
                }
            }
            HorizontalDivider()
            Text(
                text = stringResource(R.string.legacy_recovery_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (backfill.sourceFiles == 0 && backfill.error == null) {
                Text(stringResource(R.string.status_recovery_empty))
            } else {
                Text(
                    stringResource(
                        R.string.status_recovery_summary,
                        backfill.imported,
                        backfill.alreadyProcessed,
                        backfill.partialOrFailed,
                    ),
                )
            }
            backfill.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = onRetryBackfill,
                enabled = !backfill.running,
            ) {
                if (backfill.running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.retry_legacy_import))
            }
        }
    }
}

@Composable
private fun ConnectorStatusCard(
    connector: ConnectorUiStatus,
    onAction: (IntegrationAction) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = connector.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                StatusChip(
                    text = connectorStateLabel(connector.state),
                    warning = connector.state != ConnectorUiState.CONNECTED
                        && connector.state != ConnectorUiState.AVAILABLE,
                )
            }
            Text(connector.detail)
            connector.actions.forEach { action ->
                Button(onClick = { onAction(action) }) {
                    Text(action.label)
                }
            }
        }
    }
}

@Composable
private fun DeleteConfirmationDialog(
    deleting: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {
            if (!deleting) {
                onCancel()
            }
        },
        title = { Text(stringResource(R.string.delete_activity_title)) },
        text = { Text(stringResource(R.string.delete_activity_message)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !deleting,
                modifier = Modifier.testTag("confirm_delete"),
            ) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                enabled = !deleting,
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun MetadataEditorDialog(
    editor: MetadataEditorUi,
    onTitleChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {
            if (!editor.saving) {
                onCancel()
            }
        },
        title = { Text(stringResource(R.string.edit_phone_metadata)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = editor.title,
                    onValueChange = onTitleChange,
                    label = { Text(stringResource(R.string.phone_title_label)) },
                    supportingText = {
                        Text(
                            stringResource(
                                R.string.metadata_title_count,
                                editor.title.length,
                                ComposePhoneViewModel.MAX_TITLE_CHARACTERS,
                            ),
                        )
                    },
                    singleLine = true,
                    enabled = !editor.saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_title"),
                )
                OutlinedTextField(
                    value = editor.notes,
                    onValueChange = onNotesChange,
                    label = { Text(stringResource(R.string.phone_notes_label)) },
                    supportingText = {
                        Text(
                            stringResource(
                                R.string.metadata_notes_count,
                                editor.notes.length,
                                ComposePhoneViewModel.MAX_NOTES_CHARACTERS,
                            ),
                        )
                    },
                    minLines = 4,
                    maxLines = 10,
                    enabled = !editor.saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_notes"),
                )
                editor.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                enabled = !editor.saving,
                modifier = Modifier.testTag("edit_save"),
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                enabled = !editor.saving,
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
        properties = DialogProperties(dismissOnClickOutside = !editor.saving),
    )
}

@Composable
private fun LoadingPane(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorPane(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    showRetry: Boolean = true,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Outlined.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(message)
            if (showRetry) {
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}

@Composable
private fun EmptyPane(
    title: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptySelection(modifier: Modifier = Modifier) {
    EmptyPane(
        title = stringResource(R.string.no_activity_selected),
        modifier = modifier,
    )
}

@Composable
private fun InlineWarning(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Warning, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(message)
        }
    }
}

private fun activityTypeLabel(activityType: Int): String =
    when (ActivityType.forNumber(activityType)) {
        ActivityType.ACTIVITY_TYPE_RUNNING -> "Running"
        ActivityType.ACTIVITY_TYPE_WALKING -> "Walking"
        ActivityType.ACTIVITY_TYPE_CYCLING -> "Cycling"
        ActivityType.ACTIVITY_TYPE_HIKING -> "Hiking"
        ActivityType.ACTIVITY_TYPE_SWIMMING -> "Swimming"
        ActivityType.ACTIVITY_TYPE_STRENGTH -> "Strength"
        ActivityType.ACTIVITY_TYPE_OTHER -> "Other activity"
        ActivityType.ACTIVITY_TYPE_UNSPECIFIED, null, ActivityType.UNRECOGNIZED -> "Activity"
    }

private fun formatDateTime(timestampUtcMs: Long): String =
    DateFormat.getDateTimeInstance(
        DateFormat.MEDIUM,
        DateFormat.SHORT,
        Locale.getDefault(),
    ).format(Date(timestampUtcMs))

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds / 60L) % 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}

private fun formatDistance(distanceM: Double?): String =
    when {
        distanceM == null || !distanceM.isFinite() -> "Unavailable"
        distanceM < 1_000.0 -> String.format(Locale.getDefault(), "%.0f m", distanceM)
        else -> String.format(Locale.getDefault(), "%.2f km", distanceM / 1_000.0)
    }

private fun formatWeek(timestampUtcMs: Long): String =
    DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
        .format(Instant.ofEpochMilli(timestampUtcMs).atZone(ZoneOffset.UTC))

private fun humanize(value: String): String =
    value
        .removePrefix("ACTIVITY_TYPE_")
        .removePrefix("PERMISSION_STATE_")
        .removePrefix("CAPABILITY_STATE_")
        .removePrefix("LIVE_AVAILABILITY_")
        .removePrefix("SOURCE_KIND_")
        .lowercase()
        .split('_')
        .joinToString(" ") { word ->
            word.replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }

private fun connectorStateLabel(state: ConnectorUiState): String =
    humanize(state.name)
