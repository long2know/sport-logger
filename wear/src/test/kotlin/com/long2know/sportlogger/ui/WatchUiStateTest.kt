package com.long2know.sportlogger.ui

import com.long2know.sportlogger.recording.RecordingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchUiStateTest {
    @Test
    fun recordingLifecycleSelectsGlanceableDestination() {
        assertEquals(
            WatchDestination.SETUP,
            WatchUiState().destination,
        )
        assertEquals(
            WatchDestination.LIVE,
            state(RecordingState.RECORDING).destination,
        )
        assertEquals(
            WatchDestination.PAUSED,
            state(RecordingState.PAUSED).destination,
        )
        assertEquals(
            WatchDestination.FINISHING,
            state(RecordingState.EXPORT_PENDING).destination,
        )
    }

    @Test
    fun canonicalSummaryRestoresUntilExplicitlyDismissed() {
        val summary = summary()
        val restored = WatchUiState(
            recording = RecordingUi(state = RecordingState.IDLE),
            summary = summary,
        )

        assertEquals(WatchDestination.SUMMARY, restored.destination)
        assertEquals(
            WatchDestination.SETUP,
            restored.copy(summaryDismissed = true).destination,
        )
        assertEquals(
            WatchDestination.SETUP,
            restored.copy(
                recording = RecordingUi(state = RecordingState.EXPORT_QUEUED),
                summaryDismissed = true,
            ).destination,
        )
    }

    @Test
    fun deliberateConfirmationOverridesLiveAndPausedScreens() {
        assertEquals(
            WatchDestination.CONFIRMATION,
            state(RecordingState.RECORDING).copy(
                confirmation = ConfirmationAction.STOP,
            ).destination,
        )
        assertEquals(
            WatchDestination.CONFIRMATION,
            state(RecordingState.PAUSED).copy(
                confirmation = ConfirmationAction.DISCARD,
            ).destination,
        )
    }

    @Test
    fun roundAndSquarePoliciesKeepSafeResponsiveInsets() {
        val round = WatchLayoutPolicy.forScreen(isRound = true, widthDp = 192)
        val square = WatchLayoutPolicy.forScreen(isRound = false, widthDp = 220)

        assertTrue(round.horizontalPaddingDp > square.horizontalPaddingDp)
        assertTrue(round.bottomPaddingDp > square.bottomPaddingDp)
        assertTrue(round.topPaddingDp >= 26)
        assertTrue(square.horizontalPaddingDp >= 10)
    }

    private fun state(recordingState: RecordingState): WatchUiState =
        WatchUiState(recording = RecordingUi(state = recordingState))

    private fun summary(): WorkoutSummaryUi =
        WorkoutSummaryUi(
            transferId = "transfer",
            sessionId = "session",
            activity = ActivityChoice.RUNNING,
            startTimeUtcMillis = 1L,
            activeDurationMillis = 10L,
            elapsedDurationMillis = 12L,
            distanceMeters = null,
            steps = null,
            averageHeartRateBpm = null,
            maximumHeartRateBpm = null,
            lapCount = 0,
            syncState = SyncUiState.QUEUED,
            attemptCount = 0L,
        )
}
