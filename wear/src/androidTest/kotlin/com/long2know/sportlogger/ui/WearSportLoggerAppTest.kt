package com.long2know.sportlogger.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performRotaryScrollInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.long2know.sportlogger.acquisition.ExerciseMetric
import com.long2know.sportlogger.acquisition.MeasurementCapability
import com.long2know.sportlogger.acquisition.RecordingPermissionPolicy
import com.long2know.sportlogger.acquisition.RouteStatus
import com.long2know.sportlogger.acquisition.healthservices.HealthServicesExerciseSnapshot
import com.long2know.sportlogger.recording.RecordingState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WearSportLoggerAppTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun activitySelectionStartsTheChosenCanonicalType() {
        val state = mutableStateOf(
            WatchUiState(
                serviceConnection = ServiceConnectionState.CONNECTED,
                supportedActivities = ActivityChoice.entries.toSet(),
                acquisition = readyAcquisition(),
            ),
        )
        val recorder = ActionRecorder(state)
        setContent(state, recorder)

        scrollUntilVisible(
            WatchTestTags.SETUP,
            hasText("Walking"),
        )
        composeRule
            .onNodeWithText("Walking")
            .assertHasClickAction()
            .performClick()
        composeRule.waitForIdle()
        assertEquals(ActivityChoice.WALKING, state.value.selectedActivity)
        scrollUntilVisible(WatchTestTags.SETUP, hasTestTag(WatchTestTags.START))
        composeRule.onNodeWithTag(WatchTestTags.START).performClick()

        assertEquals(ActivityChoice.WALKING, recorder.startedActivity)
    }

    @Test
    fun focusedSetupListRespondsToRotaryInput() {
        val state = mutableStateOf(
            WatchUiState(
                serviceConnection = ServiceConnectionState.CONNECTED,
                supportedActivities = ActivityChoice.entries.toSet(),
                acquisition = readyAcquisition(),
            ),
        )
        val recorder = ActionRecorder(state)
        setContent(state, recorder)

        repeat(3) {
            composeRule.onNodeWithTag(WatchTestTags.SETUP).performRotaryScrollInput {
                rotateToScrollVertically(120f)
            }
            composeRule.waitForIdle()
        }

        composeRule.onNodeWithText("Walking").assertIsDisplayed()
    }

    @Test
    fun permanentPermissionDenialOffersSettingsRecovery() {
        val requestedAndDenied = setOf(
            RecordingPermissionPolicy.READ_HEART_RATE,
        )
        val derivedPermissions = RecordingPermissionPolicy.runtimeState(
            36,
            true,
            true,
            emptySet<String>()::contains,
            requestedAndDenied::contains,
            requestedAndDenied::contains,
            emptySet<String>()::contains,
        )
        val state = mutableStateOf(
            WatchUiState(
                serviceConnection = ServiceConnectionState.CONNECTED,
                supportedActivities = ActivityChoice.entries.toSet(),
                acquisition = readyAcquisition(),
                permissions = WatchUiMapper.permissions(derivedPermissions),
            ),
        )
        val recorder = ActionRecorder(state)
        setContent(state, recorder)

        scrollUntilVisible(
            WatchTestTags.SETUP,
            hasText("Open app settings"),
        )
        composeRule.onNodeWithText("Open app settings").performClick()

        assertEquals(1, recorder.settingsOpens)
    }

    @Test
    fun summaryWithoutActiveDurationShowsOnlyElapsedLabel() {
        val state = mutableStateOf(
            WatchUiState(
                serviceConnection = ServiceConnectionState.CONNECTED,
                summary = summary(
                    syncState = SyncUiState.SYNCED,
                    activeDurationMillis = null,
                ),
            ),
        )
        val recorder = ActionRecorder(state)
        setContent(state, recorder)

        composeRule.onAllNodesWithText("Active time").assertCountEquals(0)
        scrollUntilVisible(WatchTestTags.SUMMARY, hasText("Elapsed time"))
        composeRule.onNodeWithText("Elapsed time").assertIsDisplayed()
        composeRule.onNodeWithText("01:05").assertIsDisplayed()
    }

    @Test
    fun summaryWithRealZeroActiveDurationShowsZeroAndElapsedSeparately() {
        val state = mutableStateOf(
            WatchUiState(
                serviceConnection = ServiceConnectionState.CONNECTED,
                summary = summary(
                    syncState = SyncUiState.SYNCED,
                    activeDurationMillis = 0L,
                ),
            ),
        )
        val recorder = ActionRecorder(state)
        setContent(state, recorder)

        scrollUntilVisible(WatchTestTags.SUMMARY, hasText("Active time"))
        composeRule.onNodeWithText("Active time").assertIsDisplayed()
        composeRule.onNodeWithText("00:00").assertIsDisplayed()
        scrollUntilVisible(WatchTestTags.SUMMARY, hasText("Elapsed time"))
        composeRule.onNodeWithText("Elapsed time").assertIsDisplayed()
        composeRule.onNodeWithText("01:05").assertIsDisplayed()
    }

    @Test
    fun liveRouteDegradationDoesNotBlockPauseLapOrDeliberateStop() {
        val state = mutableStateOf(
            recordingState(
                RecordingState.RECORDING,
                routeStatus = RouteStatus.LOCATION_DISABLED,
            ),
        )
        val recorder = ActionRecorder(state)
        setContent(state, recorder)

        scrollUntilVisible(
            WatchTestTags.LIVE,
            hasTestTag(WatchTestTags.ROUTE_BANNER),
        )
        composeRule.onNodeWithTag(WatchTestTags.ROUTE_BANNER).assertIsDisplayed()
        scrollUntilVisible(WatchTestTags.LIVE, hasTestTag(WatchTestTags.LAP))
        composeRule
            .onNodeWithContentDescription("Record manual lap")
            .assertIsEnabled()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithTag(WatchTestTags.PAUSE).performClick()
        scrollUntilVisible(WatchTestTags.LIVE, hasTestTag(WatchTestTags.END))
        composeRule.onNodeWithTag(WatchTestTags.END).performClick()
        composeRule.onNodeWithTag(WatchTestTags.CONFIRMATION).assertIsDisplayed()
        composeRule.onNodeWithTag(WatchTestTags.CONFIRM).performClick()

        assertEquals(1, recorder.laps)
        assertEquals(1, recorder.pauses)
        assertEquals(1, recorder.stops)
    }

    @Test
    fun pausedWorkoutSupportsResumeAndSeparateDiscardConfirmation() {
        val state = mutableStateOf(recordingState(RecordingState.PAUSED))
        val recorder = ActionRecorder(state)
        setContent(state, recorder)

        scrollUntilVisible(WatchTestTags.PAUSED, hasTestTag(WatchTestTags.RESUME))
        composeRule.onNodeWithTag(WatchTestTags.RESUME).performClick()
        scrollUntilVisible(WatchTestTags.PAUSED, hasTestTag(WatchTestTags.DISCARD))
        composeRule.onNodeWithTag(WatchTestTags.DISCARD).performClick()
        composeRule.onNodeWithText("Discard workout?").assertIsDisplayed()
        composeRule.onNodeWithTag(WatchTestTags.CONFIRM).performClick()

        assertEquals(1, recorder.resumes)
        assertEquals(1, recorder.discards)
    }

    @Test
    fun failedCanonicalTransferShowsTruthfulQueueAndManualRetry() {
        val state = mutableStateOf(
            WatchUiState(
                serviceConnection = ServiceConnectionState.CONNECTED,
                summary = summary(SyncUiState.FAILED),
            ),
        )
        val recorder = ActionRecorder(state)
        setContent(state, recorder)

        composeRule.onNodeWithTag(WatchTestTags.SUMMARY).assertIsDisplayed()
        composeRule.onNodeWithText("Transfer needs retry").assertIsDisplayed()
        scrollUntilVisible(
            WatchTestTags.SUMMARY,
            hasTestTag(WatchTestTags.RETRY_SYNC),
        )
        composeRule.onNodeWithTag(WatchTestTags.RETRY_SYNC).performClick()

        assertEquals(1, recorder.retries)
    }

    @Test
    fun zeroMetricsAreRenderedWhenAvailabilitySaysTheyArePresent() {
        val state = mutableStateOf(
            recordingState(RecordingState.RECORDING).copy(
                metrics = LiveMetricsUi(
                    heartRate = MetricUi(
                        MetricId.HEART_RATE,
                        MetricValueUi.Decimal(0.0),
                        MetricAvailabilityUi.AVAILABLE,
                    ),
                    steps = MetricUi(
                        MetricId.STEPS,
                        MetricValueUi.Count(0L),
                        MetricAvailabilityUi.AVAILABLE,
                    ),
                    distance = MetricUi(
                        MetricId.DISTANCE,
                        MetricValueUi.Decimal(0.0),
                        MetricAvailabilityUi.AVAILABLE,
                    ),
                    location = MetricUi(
                        MetricId.LOCATION,
                        MetricValueUi.Coordinates(0.0, 0.0),
                        MetricAvailabilityUi.AVAILABLE,
                    ),
                ),
            ),
        )
        val recorder = ActionRecorder(state)
        setContent(state, recorder)

        scrollUntilVisible(
            WatchTestTags.LIVE,
            hasContentDescription("Heart rate, 0 bpm"),
        )
        composeRule
            .onNodeWithContentDescription("Heart rate, 0 bpm")
            .assertIsDisplayed()
        scrollUntilVisible(
            WatchTestTags.LIVE,
            hasContentDescription("Location, 0.00000, 0.00000"),
        )
        composeRule
            .onNodeWithContentDescription("Location, 0.00000, 0.00000")
            .assertIsDisplayed()
    }

    private fun setContent(
        state: MutableState<WatchUiState>,
        recorder: ActionRecorder,
    ) {
        composeRule.setContent {
            WearSportLoggerApp(
                state = state.value,
                actions = recorder.actions,
                isRound = true,
            )
        }
    }

    private fun scrollUntilVisible(
        containerTag: String,
        matcher: SemanticsMatcher,
    ) {
        repeat(20) {
            val displayed = runCatching {
                composeRule.onNode(matcher).assertIsDisplayed()
            }.isSuccess
            if (displayed) {
                return
            }
            composeRule.onNodeWithTag(containerTag).performTouchInput {
                swipe(
                    start = center + Offset(0f, 70f),
                    end = center - Offset(0f, 70f),
                    durationMillis = 180L,
                )
            }
            composeRule.waitForIdle()
        }
        composeRule.onNode(matcher).assertIsDisplayed()
    }

    private fun recordingState(
        state: RecordingState,
        routeStatus: RouteStatus = RouteStatus.AVAILABLE,
    ): WatchUiState =
        WatchUiState(
            serviceConnection = ServiceConnectionState.CONNECTED,
            recording = RecordingUi(
                state = state,
                activityId = 71,
                activeDurationMillis = 65_000L,
                elapsedDurationMillis = 72_000L,
                currentLapIndex = 2,
                lastLapActiveDurationMillis = 31_000L,
            ),
            selectedActivity = ActivityChoice.RUNNING,
            supportedActivities = ActivityChoice.entries.toSet(),
            lapAvailable = true,
            routeStatus = routeStatus,
        )

    private fun summary(
        syncState: SyncUiState,
        activeDurationMillis: Long? = 60_000L,
    ): WorkoutSummaryUi =
        WorkoutSummaryUi(
            transferId = "transfer",
            sessionId = "session",
            activity = ActivityChoice.RUNNING,
            startTimeUtcMillis = 1L,
            activeDurationMillis = activeDurationMillis,
            elapsedDurationMillis = 65_000L,
            distanceMeters = 0.0,
            steps = 0L,
            averageHeartRateBpm = null,
            maximumHeartRateBpm = null,
            lapCount = 1,
            syncState = syncState,
            attemptCount = 2L,
        )

    private fun readyAcquisition(
        capabilities: Map<ExerciseMetric, MeasurementCapability> = emptyMap(),
    ): HealthServicesExerciseSnapshot =
        HealthServicesExerciseSnapshot(
            HealthServicesExerciseSnapshot.State.READY,
            HealthServicesExerciseSnapshot.Ownership.NONE,
            capabilities,
            emptySet(),
            "",
        )

    private class ActionRecorder(
        private val state: MutableState<WatchUiState>,
    ) {
        var startedActivity: ActivityChoice? = null
        var pauses = 0
        var resumes = 0
        var laps = 0
        var stops = 0
        var discards = 0
        var retries = 0
        var settingsOpens = 0

        val actions = WatchAppActions(
            onSelectActivity = { choice ->
                state.value = state.value.copy(selectedActivity = choice)
            },
            onRequestPermissions = {},
            onOpenSettings = { settingsOpens++ },
            onRefreshReadiness = {},
            onStart = { choice -> startedActivity = choice },
            onPause = { pauses++ },
            onResume = { resumes++ },
            onLap = { laps++ },
            onRequestStop = {
                state.value = state.value.copy(confirmation = ConfirmationAction.STOP)
            },
            onRequestDiscard = {
                state.value = state.value.copy(confirmation = ConfirmationAction.DISCARD)
            },
            onConfirmStop = {
                stops++
                state.value = state.value.copy(confirmation = null)
            },
            onConfirmDiscard = {
                discards++
                state.value = state.value.copy(confirmation = null)
            },
            onCancelConfirmation = {
                state.value = state.value.copy(confirmation = null)
            },
            onRetrySync = { retries++ },
            onDismissSummary = {
                state.value = state.value.copy(summaryDismissed = true)
            },
            onDismissError = {},
        )
    }
}
