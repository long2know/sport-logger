package com.long2know.sportlogger.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.long2know.sportlogger.MainActivity
import com.long2know.sportlogger.recording.RecordingAuthority
import com.long2know.sportlogger.recording.RecordingSnapshot
import com.long2know.sportlogger.recording.RecordingState
import com.long2know.utilities.contract.proto.ActivityType
import org.junit.Rule
import org.junit.Test

class MainActivityComposeRecreationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun authoritativeSnapshotRestoresAfterConfigurationRecreation() {
        renderAuthoritativeSnapshot()

        composeRule.onNodeWithText("Cycling").assertIsDisplayed()
        composeRule.onNodeWithText("Elapsed 00:17").assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        renderAuthoritativeSnapshot()

        composeRule.onNodeWithText("Cycling").assertIsDisplayed()
        composeRule.onNodeWithText("Elapsed 00:17").assertIsDisplayed()
    }

    private fun renderAuthoritativeSnapshot() {
        composeRule.runOnUiThread {
            composeRule.activity.onRecordingStateChanged(
                RecordingSnapshot(
                    RecordingState.RECORDING,
                    82,
                    ActivityType.ACTIVITY_TYPE_CYCLING,
                    RecordingAuthority.CANONICAL,
                    1_000L,
                    12_000L,
                    17_000L,
                    5_000L,
                    4_000L,
                    3,
                    null,
                    2,
                    6_000L,
                    5_000L,
                ),
            )
        }
        composeRule.waitForIdle()
    }
}
