package com.long2know.sportlogger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.long2know.sportlogger.R
import com.long2know.sportlogger.acquisition.MeasurementCapability
import com.long2know.sportlogger.acquisition.RouteStatus
import com.long2know.sportlogger.acquisition.healthservices.HealthServicesExerciseSnapshot
import com.long2know.sportlogger.recording.RecordingState
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToLong

data class WatchAppActions(
    val onSelectActivity: (ActivityChoice) -> Unit,
    val onRequestPermissions: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onRefreshReadiness: () -> Unit,
    val onStart: (ActivityChoice) -> Unit,
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onLap: () -> Unit,
    val onRequestStop: () -> Unit,
    val onRequestDiscard: () -> Unit,
    val onConfirmStop: () -> Unit,
    val onConfirmDiscard: () -> Unit,
    val onCancelConfirmation: () -> Unit,
    val onRetrySync: () -> Unit,
    val onDismissSummary: () -> Unit,
    val onDismissError: () -> Unit,
)

object WatchTestTags {
    const val ROOT = "watch_root"
    const val SETUP = "setup_screen"
    const val LIVE = "live_screen"
    const val PAUSED = "paused_screen"
    const val FINISHING = "finishing_screen"
    const val SUMMARY = "summary_screen"
    const val CONFIRMATION = "confirmation_screen"
    const val ROUTE_BANNER = "route_banner"
    const val START = "start_action"
    const val PAUSE = "pause_action"
    const val RESUME = "resume_action"
    const val LAP = "lap_action"
    const val END = "end_action"
    const val DISCARD = "discard_action"
    const val CONFIRM = "confirm_action"
    const val RETRY_SYNC = "retry_sync_action"
}

@Composable
fun WearSportLoggerApp(
    state: WatchUiState,
    actions: WatchAppActions,
    modifier: Modifier = Modifier,
    isRound: Boolean = LocalConfiguration.current.isScreenRound,
) {
    val configuration = LocalConfiguration.current
    val layout = remember(isRound, configuration.screenWidthDp) {
        WatchLayoutPolicy.forScreen(isRound, configuration.screenWidthDp)
    }
    MaterialTheme {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag(WatchTestTags.ROOT),
        ) {
            if (state.ambient &&
                (state.destination == WatchDestination.LIVE ||
                    state.destination == WatchDestination.PAUSED)
            ) {
                AmbientWorkoutScreen(state)
            } else {
                when (state.destination) {
                    WatchDestination.SETUP -> SetupScreen(state, actions, layout)
                    WatchDestination.LIVE -> LiveScreen(state, actions, layout, paused = false)
                    WatchDestination.PAUSED -> LiveScreen(state, actions, layout, paused = true)
                    WatchDestination.FINISHING -> FinishingScreen(state, layout)
                    WatchDestination.SUMMARY -> SummaryScreen(state, actions, layout)
                    WatchDestination.CONFIRMATION ->
                        ConfirmationScreen(state, actions, layout)
                }
            }
        }
    }
}

@Composable
private fun SetupScreen(
    state: WatchUiState,
    actions: WatchAppActions,
    layout: WatchLayoutPolicy,
) {
    RotaryScreen(WatchTestTags.SETUP, layout) {
        item {
            ScreenTitle(stringResource(R.string.choose_activity))
        }
        state.errorMessage?.let { message ->
            item {
                ErrorBanner(message, actions.onDismissError)
            }
        }
        item {
            StatusPanel(
                title = connectionLabel(state.serviceConnection),
                detail = acquisitionLabel(state.acquisition.state),
            )
        }
        items(ActivityChoice.entries.size) { index ->
            val choice = ActivityChoice.entries[index]
            val selected = choice == state.selectedActivity
            val choiceName = activityName(choice)
            val supportLabel = activitySupportLabel(state, choice)
            Chip(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("activity_${choice.name}")
                    .semantics {
                        contentDescription = "$choiceName, $supportLabel"
                    },
                onClick = { actions.onSelectActivity(choice) },
                label = {
                    Text(
                        text = choiceName,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                secondaryLabel = {
                    Text(supportLabel)
                },
                colors = if (selected) {
                    ChipDefaults.primaryChipColors()
                } else {
                    ChipDefaults.secondaryChipColors()
                },
            )
        }
        item {
            ReadinessPanel(state)
        }
        if (state.missingPermissionCount > 0) {
            item {
                val permanentlyDenied = state.acquisition.capabilities.values.any {
                    it.permissionState ==
                        MeasurementCapability.PermissionState.DENIED_PERMANENTLY
                }
                Chip(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    onClick = if (permanentlyDenied) {
                        actions.onOpenSettings
                    } else {
                        actions.onRequestPermissions
                    },
                    label = {
                        Text(
                            if (permanentlyDenied) {
                                stringResource(R.string.open_app_settings)
                            } else {
                                stringResource(R.string.grant_permissions)
                            },
                        )
                    },
                    secondaryLabel = {
                        Text(
                            pluralStringResource(
                                R.plurals.permissions_missing_count,
                                state.missingPermissionCount,
                                state.missingPermissionCount,
                            ),
                        )
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }
        item {
            val selectedName = activityName(state.selectedActivity)
            val startDescription =
                "${stringResource(R.string.start_activity)} $selectedName"
            Chip(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .testTag(WatchTestTags.START)
                    .semantics {
                        contentDescription = startDescription
                    },
                onClick = { actions.onStart(state.selectedActivity) },
                enabled = canStart(state),
                label = {
                    Text(
                        stringResource(
                            R.string.start_named_activity,
                            selectedName,
                        ),
                        fontWeight = FontWeight.Bold,
                    )
                },
                colors = ChipDefaults.primaryChipColors(),
            )
        }
        item {
            Chip(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                onClick = actions.onRefreshReadiness,
                label = { Text(stringResource(R.string.check_again)) },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
    }
}

@Composable
private fun LiveScreen(
    state: WatchUiState,
    actions: WatchAppActions,
    layout: WatchLayoutPolicy,
    paused: Boolean,
) {
    RotaryScreen(if (paused) WatchTestTags.PAUSED else WatchTestTags.LIVE, layout) {
        item {
            ScreenTitle(
                if (paused) {
                    stringResource(R.string.workout_paused)
                } else {
                    activityName(state.selectedActivity)
                },
            )
        }
        if (state.routeStatus.shouldShowBanner()) {
            item {
                RouteBanner(state.routeStatus)
            }
        }
        state.errorMessage?.let { message ->
            item {
                ErrorBanner(message, actions.onDismissError)
            }
        }
        item {
            DurationPanel(
                activeMillis = state.recording.activeDurationMillis,
                elapsedMillis = state.recording.elapsedDurationMillis,
            )
        }
        if (state.recording.currentLapIndex > 0) {
            item {
                StatusPanel(
                    title = stringResource(
                        R.string.current_lap_value,
                        state.recording.currentLapIndex,
                    ),
                    detail = state.recording.lastLapActiveDurationMillis?.let { duration ->
                        stringResource(
                            R.string.last_lap_value,
                            formatDuration(duration),
                        )
                    } ?: stringResource(R.string.first_lap_in_progress),
                )
            }
        }
        item {
            MetricCard(state.metrics.heartRate)
        }
        item {
            MetricCard(state.metrics.distance)
        }
        item {
            MetricCard(state.metrics.steps)
        }
        item {
            MetricCard(state.metrics.location)
        }
        item {
            val primaryDescription = if (paused) {
                stringResource(R.string.resume_activity)
            } else {
                stringResource(R.string.pause_activity)
            }
            val lapDescription = stringResource(R.string.record_lap)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    modifier = Modifier
                        .size(60.dp)
                        .testTag(if (paused) WatchTestTags.RESUME else WatchTestTags.PAUSE)
                        .semantics {
                            contentDescription = primaryDescription
                        },
                    onClick = if (paused) actions.onResume else actions.onPause,
                    colors = ButtonDefaults.primaryButtonColors(),
                ) {
                    Text(if (paused) stringResource(R.string.resume_short) else stringResource(R.string.pause_short))
                }
                Button(
                    modifier = Modifier
                        .size(60.dp)
                        .testTag(WatchTestTags.LAP)
                        .semantics {
                            contentDescription = lapDescription
                        },
                    onClick = actions.onLap,
                        enabled = state.lapAvailable &&
                            !paused &&
                            state.recording.state == RecordingState.RECORDING,
                    colors = ButtonDefaults.secondaryButtonColors(),
                ) {
                    Text(stringResource(R.string.lap_short))
                }
            }
        }
        item {
            Chip(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag(WatchTestTags.END),
                onClick = actions.onRequestStop,
                label = { Text(stringResource(R.string.end_workout)) },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
        if (paused) {
            item {
                Chip(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag(WatchTestTags.DISCARD),
                    onClick = actions.onRequestDiscard,
                    label = { Text(stringResource(R.string.discard_workout)) },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }
    }
}

@Composable
private fun FinishingScreen(
    state: WatchUiState,
    layout: WatchLayoutPolicy,
) {
    RotaryScreen(WatchTestTags.FINISHING, layout) {
        item {
            ScreenTitle(stringResource(R.string.saving_workout))
        }
        item {
            Text(
                text = finishingLabel(state.recording.state),
                textAlign = TextAlign.Center,
                color = Color.LightGray,
            )
        }
        item {
            DurationPanel(
                activeMillis = state.recording.activeDurationMillis,
                elapsedMillis = state.recording.elapsedDurationMillis,
            )
        }
        item {
            Text(
                text = stringResource(R.string.safe_to_leave),
                textAlign = TextAlign.Center,
                color = Color.Gray,
            )
        }
    }
}

@Composable
private fun ConfirmationScreen(
    state: WatchUiState,
    actions: WatchAppActions,
    layout: WatchLayoutPolicy,
) {
    val discarding = state.confirmation == ConfirmationAction.DISCARD
    RotaryScreen(WatchTestTags.CONFIRMATION, layout) {
        item {
            ScreenTitle(
                if (discarding) {
                    stringResource(R.string.discard_question)
                } else {
                    stringResource(R.string.finish_question)
                },
            )
        }
        item {
            Text(
                text = if (discarding) {
                    stringResource(R.string.discard_warning)
                } else {
                    stringResource(R.string.finish_explanation)
                },
                textAlign = TextAlign.Center,
                color = Color.LightGray,
            )
        }
        item {
            Chip(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .testTag(WatchTestTags.CONFIRM),
                onClick = if (discarding) actions.onConfirmDiscard else actions.onConfirmStop,
                label = {
                    Text(
                        if (discarding) {
                            stringResource(R.string.discard_confirm)
                        } else {
                            stringResource(R.string.finish_confirm)
                        },
                        fontWeight = FontWeight.Bold,
                    )
                },
                colors = ChipDefaults.primaryChipColors(),
            )
        }
        item {
            Chip(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                onClick = actions.onCancelConfirmation,
                label = { Text(stringResource(R.string.keep_recording)) },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
    }
}

@Composable
private fun SummaryScreen(
    state: WatchUiState,
    actions: WatchAppActions,
    layout: WatchLayoutPolicy,
) {
    val summary = requireNotNull(state.summary)
    val locale = LocalLocale.current.platformLocale
    RotaryScreen(WatchTestTags.SUMMARY, layout) {
        item {
            ScreenTitle(stringResource(R.string.workout_saved))
        }
        item {
            StatusPanel(
                title = activityName(summary.activity),
                detail = syncLabel(summary.syncState),
            )
        }
        item {
            SummaryMetric(
                stringResource(R.string.active_time),
                formatDuration(summary.activeDurationMillis ?: summary.elapsedDurationMillis),
            )
        }
        if (summary.activeDurationMillis != null &&
            summary.activeDurationMillis != summary.elapsedDurationMillis
        ) {
            item {
                SummaryMetric(
                    stringResource(R.string.elapsed_time),
                    formatDuration(summary.elapsedDurationMillis),
                )
            }
        }
        summary.distanceMeters?.let { distance ->
            item {
                SummaryMetric(
                    stringResource(R.string.distance),
                    formatDistance(distance, locale),
                )
            }
        }
        summary.steps?.let { steps ->
            item {
                SummaryMetric(
                    stringResource(R.string.steps),
                    formatCount(steps, locale),
                )
            }
        }
        summary.averageHeartRateBpm?.let { average ->
            item {
                SummaryMetric(
                    stringResource(R.string.average_heart_rate),
                    stringResource(R.string.bpm_value, average.roundToLong()),
                )
            }
        }
        if (summary.lapCount > 0) {
            item {
                SummaryMetric(
                    stringResource(R.string.laps),
                    summary.lapCount.toString(),
                )
            }
        }
        if (summary.syncState == SyncUiState.FAILED) {
            item {
                Chip(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag(WatchTestTags.RETRY_SYNC),
                    onClick = actions.onRetrySync,
                    label = { Text(stringResource(R.string.retry_transfer)) },
                    secondaryLabel = {
                        Text(stringResource(R.string.phone_offline_queue_truth))
                    },
                    colors = ChipDefaults.primaryChipColors(),
                )
            }
        } else if (summary.syncState != SyncUiState.SYNCED) {
            item {
                Text(
                    text = stringResource(R.string.phone_offline_queue_truth),
                    textAlign = TextAlign.Center,
                    color = Color.LightGray,
                )
            }
        }
        item {
            Chip(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                onClick = actions.onDismissSummary,
                label = { Text(stringResource(R.string.new_activity)) },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
    }
}

@Composable
private fun AmbientWorkoutScreen(state: WatchUiState) {
    val minuteShift = ((state.recording.elapsedDurationMillis / 60_000L) % 3L - 1L).toInt()
    val ambientDescription = stringResource(R.string.ambient_workout)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 24.dp)
            .offset(x = minuteShift.dp)
            .semantics {
                contentDescription = ambientDescription
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = formatAmbientDuration(state.recording.activeDurationMillis),
            style = MaterialTheme.typography.display1,
            color = Color.White,
        )
        metricValue(state.metrics.heartRate)?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "$it ${stringResource(R.string.bpm_unit)}",
                color = Color.LightGray,
            )
        }
        metricValue(state.metrics.distance)?.let {
            Spacer(Modifier.height(4.dp))
            Text(text = it, color = Color.LightGray)
        }
        if (state.recording.state == RecordingState.PAUSED) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.paused_short), color = Color.Gray)
        }
    }
}

@Composable
private fun RotaryScreen(
    tag: String,
    layout: WatchLayoutPolicy,
    content: ScalingLazyListScope.() -> Unit,
) {
    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(focusRequester) {
        focusRequester.requestFocus()
    }
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = {
            PositionIndicator(scalingLazyListState = listState)
        },
    ) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(tag)
                .onRotaryScrollEvent {
                    scope.launch {
                        listState.scrollBy(it.verticalScrollPixels)
                    }
                    true
                }
                .focusRequester(focusRequester)
                .focusable(),
            state = listState,
            contentPadding = PaddingValues(
                start = layout.horizontalPaddingDp.dp,
                end = layout.horizontalPaddingDp.dp,
                top = layout.topPaddingDp.dp,
                bottom = layout.bottomPaddingDp.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun ScreenTitle(text: String) {
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { heading() },
        text = text,
        textAlign = TextAlign.Center,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        style = MaterialTheme.typography.title2,
    )
}

@Composable
private fun StatusPanel(
    title: String,
    detail: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF171717), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontWeight = FontWeight.Bold, color = Color.White)
        Text(detail, color = Color.LightGray, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ReadinessPanel(state: WatchUiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF111111), RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            stringResource(R.string.measurement_readiness),
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
        ReadinessLine(stringResource(R.string.heart_rate), state.metrics.heartRate)
        ReadinessLine(stringResource(R.string.distance), state.metrics.distance)
        ReadinessLine(stringResource(R.string.steps), state.metrics.steps)
        ReadinessLine(stringResource(R.string.route), state.metrics.location)
    }
}

@Composable
private fun ReadinessLine(
    label: String,
    metric: MetricUi,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color.LightGray)
        Text(
            availabilityLabel(metric.availability),
            color = availabilityColor(metric.availability),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun DurationPanel(
    activeMillis: Long,
    elapsedMillis: Long,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF121212), RoundedCornerShape(20.dp))
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = formatDuration(activeMillis),
            style = MaterialTheme.typography.display2,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Text(
            text = stringResource(
                R.string.elapsed_value,
                formatDuration(elapsedMillis),
            ),
            color = Color.LightGray,
        )
    }
}

@Composable
private fun MetricCard(metric: MetricUi) {
    val label = when (metric.id) {
        MetricId.HEART_RATE -> stringResource(R.string.heart_rate)
        MetricId.STEPS -> stringResource(R.string.steps)
        MetricId.DISTANCE -> stringResource(R.string.distance)
        MetricId.LOCATION -> stringResource(R.string.location)
    }
    val value = metricValue(metric)
    val source = metric.sources.joinToString(separator = " · ")
    val availability = availabilityLabel(metric.availability)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF151515), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("metric_${metric.id.name}")
            .semantics {
                contentDescription = buildString {
                    append(label)
                    append(", ")
                    append(value ?: availability)
                    if (source.isNotEmpty()) {
                        append(", ")
                        append(source)
                    }
                }
            },
    ) {
        Text(label, color = Color.LightGray)
        Text(
            text = value ?: availability,
            color = if (value == null) availabilityColor(metric.availability) else Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.title2,
        )
        if (source.isNotEmpty()) {
            Text(
                text = "$source · $availability",
                color = Color.Gray,
                style = MaterialTheme.typography.caption2,
            )
        }
    }
}

@Composable
private fun RouteBanner(status: RouteStatus) {
    val label = routeLabel(status)
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF3A2D00), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(WatchTestTags.ROUTE_BANNER)
            .semantics {
                contentDescription = label
            },
        text = label,
        textAlign = TextAlign.Center,
        color = Color(0xFFFFD166),
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
) {
    Chip(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp),
        onClick = onDismiss,
        label = { Text(stringResource(R.string.needs_attention)) },
        secondaryLabel = { Text(message, maxLines = 2) },
        colors = ChipDefaults.secondaryChipColors(),
    )
}

@Composable
private fun SummaryMetric(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF151515), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.LightGray)
        Text(value, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

private fun canStart(state: WatchUiState): Boolean =
    state.serviceConnection == ServiceConnectionState.CONNECTED &&
        state.acquisition.ownership != HealthServicesExerciseSnapshot.Ownership.OWNED_BY_OTHER_APP &&
        (state.acquisition.state == HealthServicesExerciseSnapshot.State.READY ||
            state.acquisition.state == HealthServicesExerciseSnapshot.State.RAW_FALLBACK) &&
        (state.supportedActivities.isEmpty() ||
            state.selectedActivity in state.supportedActivities) &&
        state.activitySupport != ActivitySupportState.UNSUPPORTED

@Composable
private fun connectionLabel(state: ServiceConnectionState): String =
    when (state) {
        ServiceConnectionState.CONNECTING -> stringResource(R.string.connecting_service)
        ServiceConnectionState.CONNECTED -> stringResource(R.string.service_ready)
        ServiceConnectionState.DISCONNECTED -> stringResource(R.string.service_unavailable)
    }

@Composable
private fun acquisitionLabel(state: HealthServicesExerciseSnapshot.State): String =
    when (state) {
        HealthServicesExerciseSnapshot.State.UNINITIALIZED ->
            stringResource(R.string.checking_watch_support)

        HealthServicesExerciseSnapshot.State.READY ->
            stringResource(R.string.measurements_ready)

        HealthServicesExerciseSnapshot.State.STARTING ->
            stringResource(R.string.measurements_starting)

        HealthServicesExerciseSnapshot.State.ACTIVE ->
            stringResource(R.string.measurements_active)

        HealthServicesExerciseSnapshot.State.PAUSED ->
            stringResource(R.string.measurements_paused)

        HealthServicesExerciseSnapshot.State.ENDING ->
            stringResource(R.string.measurements_ending)

        HealthServicesExerciseSnapshot.State.RAW_FALLBACK ->
            stringResource(R.string.raw_fallback_active)

        HealthServicesExerciseSnapshot.State.BLOCKED_BY_OTHER_APP ->
            stringResource(R.string.other_app_recording)

        HealthServicesExerciseSnapshot.State.ERROR ->
            stringResource(R.string.measurements_error)

        HealthServicesExerciseSnapshot.State.CLOSED ->
            stringResource(R.string.measurements_closed)
    }

@Composable
private fun activityName(choice: ActivityChoice): String = stringResource(choice.labelRes)

@Composable
private fun activitySupportLabel(
    state: WatchUiState,
    choice: ActivityChoice,
): String {
    val support = WatchUiMapper.activitySupport(
        choice,
        state.acquisition,
        state.supportedActivities,
    )
    return when (support) {
        ActivitySupportState.CHECKING -> stringResource(R.string.support_checking)
        ActivitySupportState.SUPPORTED -> stringResource(R.string.supported_on_watch)
        ActivitySupportState.LIMITED -> stringResource(R.string.support_limited)
        ActivitySupportState.UNSUPPORTED -> stringResource(R.string.unsupported_on_watch)
    }
}

@Composable
private fun availabilityLabel(availability: MetricAvailabilityUi): String =
    when (availability) {
        MetricAvailabilityUi.CHECKING -> stringResource(R.string.status_checking)
        MetricAvailabilityUi.AVAILABLE -> stringResource(R.string.status_available)
        MetricAvailabilityUi.ACQUIRING -> stringResource(R.string.status_acquiring)
        MetricAvailabilityUi.PERMISSION_DENIED ->
            stringResource(R.string.status_permission_denied)

        MetricAvailabilityUi.UNSUPPORTED -> stringResource(R.string.status_unsupported)
        MetricAvailabilityUi.OFF_BODY -> stringResource(R.string.status_off_body)
        MetricAvailabilityUi.NO_LOCATION -> stringResource(R.string.status_no_location)
        MetricAvailabilityUi.UNAVAILABLE -> stringResource(R.string.status_unavailable)
    }

private fun availabilityColor(availability: MetricAvailabilityUi): Color =
    when (availability) {
        MetricAvailabilityUi.AVAILABLE -> Color(0xFF73E6A5)
        MetricAvailabilityUi.ACQUIRING,
        MetricAvailabilityUi.CHECKING,
        -> Color(0xFFFFD166)

        else -> Color(0xFFFF8A80)
    }

@Composable
private fun routeLabel(status: RouteStatus): String =
    when (status) {
        RouteStatus.ACQUIRING -> stringResource(R.string.route_status_acquiring)
        RouteStatus.LOCATION_DISABLED -> stringResource(R.string.route_status_location_disabled)
        RouteStatus.PERMISSION_DENIED -> stringResource(R.string.route_status_permission_denied)
        RouteStatus.APPROXIMATE_ONLY -> stringResource(R.string.route_status_approximate_only)
        RouteStatus.NO_GNSS -> stringResource(R.string.route_status_no_gnss)
        RouteStatus.NO_COMPATIBLE_PROVIDER -> stringResource(R.string.route_status_no_provider)
        RouteStatus.UNAVAILABLE -> stringResource(R.string.route_status_unavailable)
        RouteStatus.UNKNOWN,
        RouteStatus.AVAILABLE,
        -> ""
    }

@Composable
private fun finishingLabel(state: RecordingState): String =
    when (state) {
        RecordingState.DISCARDING -> stringResource(R.string.discarding_workout)
        RecordingState.STOPPING -> stringResource(R.string.finishing_recording)
        RecordingState.EXPORT_PENDING -> stringResource(R.string.building_summary)
        RecordingState.EXPORT_QUEUED -> stringResource(R.string.queued_for_phone)
        else -> stringResource(R.string.saving_workout)
    }

@Composable
private fun syncLabel(state: SyncUiState): String =
    when (state) {
        SyncUiState.QUEUED -> stringResource(R.string.sync_queued)
        SyncUiState.TRANSFERRING -> stringResource(R.string.sync_transferring)
        SyncUiState.SYNCED -> stringResource(R.string.sync_synced)
        SyncUiState.FAILED -> stringResource(R.string.sync_failed)
    }

@Composable
private fun metricValue(metric: MetricUi): String? {
    val locale = LocalLocale.current.platformLocale
    return when (val value = metric.value) {
        is MetricValueUi.Count -> formatCount(value.value, locale)
        is MetricValueUi.Decimal ->
            when (metric.id) {
                MetricId.HEART_RATE ->
                    stringResource(R.string.bpm_value, value.value.roundToLong())

                MetricId.DISTANCE -> formatDistance(value.value, locale)
                else -> String.format(locale, "%.1f", value.value)
            }

        is MetricValueUi.Coordinates ->
            String.format(
                locale,
                "%.5f, %.5f",
                value.latitude,
                value.longitude,
            )

        null -> null
    }
}

private fun formatDistance(
    meters: Double,
    locale: Locale,
): String =
    if (meters < 1_000.0) {
        String.format(locale, "%.0f m", meters)
    } else {
        String.format(locale, "%.2f km", meters / 1_000.0)
    }

private fun formatCount(
    value: Long,
    locale: Locale,
): String =
    String.format(locale, "%,d", value)

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

private fun formatAmbientDuration(millis: Long): String {
    val totalMinutes = millis.coerceAtLeast(0L) / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return String.format(Locale.ROOT, "%d:%02d", hours, minutes)
}

@Preview(name = "Round setup", widthDp = 192, heightDp = 192, showBackground = true)
@Composable
private fun RoundSetupPreview() {
    WearSportLoggerApp(
        state = previewState(),
        actions = previewActions(),
        isRound = true,
    )
}

@Preview(name = "Square live", widthDp = 220, heightDp = 220, showBackground = true)
@Composable
private fun SquareLivePreview() {
    WearSportLoggerApp(
        state = previewState().copy(
            recording = RecordingUi(
                state = RecordingState.RECORDING,
                activityId = 7,
                activeDurationMillis = 623_000L,
                elapsedDurationMillis = 641_000L,
            ),
            routeStatus = RouteStatus.ACQUIRING,
            metrics = LiveMetricsUi(
                heartRate = MetricUi(
                    MetricId.HEART_RATE,
                    MetricValueUi.Decimal(142.0),
                    MetricAvailabilityUi.AVAILABLE,
                    listOf("Health Services"),
                ),
                distance = MetricUi(
                    MetricId.DISTANCE,
                    MetricValueUi.Decimal(2_430.0),
                    MetricAvailabilityUi.AVAILABLE,
                    listOf("Health Services"),
                ),
                steps = MetricUi(
                    MetricId.STEPS,
                    MetricValueUi.Count(3_204L),
                    MetricAvailabilityUi.AVAILABLE,
                    listOf("Watch sensor"),
                ),
            ),
        ),
        actions = previewActions(),
        isRound = false,
    )
}

private fun previewState(): WatchUiState =
    WatchUiState(serviceConnection = ServiceConnectionState.CONNECTED)

private fun previewActions(): WatchAppActions =
    WatchAppActions(
        onSelectActivity = {},
        onRequestPermissions = {},
        onOpenSettings = {},
        onRefreshReadiness = {},
        onStart = {},
        onPause = {},
        onResume = {},
        onLap = {},
        onRequestStop = {},
        onRequestDiscard = {},
        onConfirmStop = {},
        onConfirmDiscard = {},
        onCancelConfirmation = {},
        onRetrySync = {},
        onDismissSummary = {},
        onDismissError = {},
    )
