package com.long2know.sportlogger

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.long2know.sportlogger.persistence.phone.PhoneDatabase
import com.long2know.sportlogger.transfer.PhoneActivityStore
import com.long2know.utilities.models.SportActivity
import com.long2know.utilities.models.SportTrackPoint
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityComposeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var instrumentation: Instrumentation
    private lateinit var context: Context
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        clearAppData()
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
        runCatching { Intents.release() }
        instrumentation.waitForIdleSync()
        clearAppData()
    }

    @Test
    fun legacyInboxMigratesAndEditedMetadataSurvivesRecreation() {
        persistLegacy(activity("Synthetic restart activity", 1_700_000_000_000L, 250.0))
        launch()

        waitForText("Synthetic restart activity")
        compose.onNodeWithText("Synthetic restart activity").performClick()
        waitForTag("detail_screen")
        compose.onNodeWithContentDescription(
            context.getString(R.string.edit_content_description),
        ).performClick()
        compose.onNodeWithTag("edit_title")
            .performTextReplacement("Edited phone title")
        compose.onNodeWithTag("edit_notes")
            .performTextReplacement("Clearly synthetic phone notes")
        compose.onNodeWithTag("edit_save").performClick()

        waitForText("Edited phone title")
        scenario?.recreate()
        waitForText("Edited phone title")
        compose.onNodeWithText("Clearly synthetic phone notes").assertIsDisplayed()
    }

    @Test
    fun persistedTrendsAndRecoveryStatusRemainUsefulWithCorruptInboxEntry() {
        persistLegacy(activity("Synthetic walk", 1_700_000_000_000L, 1_000.0))
        persistLegacy(activity("Synthetic run", 1_700_086_400_000L, 2_000.0))
        persistLegacy(activity("Synthetic ride", 1_700_172_800_000L, 5_000.0))
        val inbox = File(context.filesDir, "received-sport-activities")
        FileOutputStream(File(inbox, "corrupt.ser")).use {
            it.write(byteArrayOf(0x01, 0x02, 0x03))
        }
        launch()

        waitForText("Synthetic ride")
        compose.onNodeWithText(context.getString(R.string.nav_trends)).performClick()
        waitForTag("trends_screen")
        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.trend_weekly)).assertIsDisplayed()

        compose.onNodeWithText(context.getString(R.string.nav_status)).performClick()
        waitForTag("status_screen")
        waitForText("3 canonical activities")
        compose.onNodeWithText(context.getString(R.string.legacy_recovery_title))
            .assertIsDisplayed()
        compose.onNodeWithText("Health Connect").assertIsDisplayed()
        compose.onNodeWithText("Endurain").assertIsDisplayed()
    }

    @Test
    fun composeSaveAndShareProduceReadableTcx() {
        persistLegacy(activity("Synthetic export activity", 1_700_000_200_000L, 1_250.0))
        launch()
        waitForText("Synthetic export activity")
        compose.onNodeWithText("Synthetic export activity").performClick()
        waitForTag("detail_screen")

        val sharedDirectory = File(context.filesDir, "shared-tcx")
        assertTrue(sharedDirectory.mkdirs() || sharedDirectory.isDirectory)
        val saveDestination = File(sharedDirectory, "ui-compose-save.tcx")
        val saveUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            saveDestination,
        )
        Intents.init()
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(
            Instrumentation.ActivityResult(
                Activity.RESULT_OK,
                Intent().setData(saveUri),
            ),
        )
        compose.onNodeWithTag("save_tcx").performClick()
        waitForText("TCX saved successfully.")
        assertTcx(saveDestination)

        intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(
            Instrumentation.ActivityResult(Activity.RESULT_OK, null),
        )
        compose.onNodeWithTag("share_tcx").performClick()
        val shared = waitForSharedTcx(sharedDirectory)
        assertTcx(shared)
    }

    @Test
    fun emptyCanonicalHistoryShowsRecoveryAwareEmptyState() {
        launch()

        waitForText(context.getString(R.string.history_empty))
        compose.onNodeWithText(context.getString(R.string.history_empty))
            .assertIsDisplayed()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        instrumentation.waitForIdleSync()
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 12_000L) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(timeoutMillis = 12_000L) {
            runCatching {
                compose.onNodeWithTag(tag).fetchSemanticsNode()
            }.isSuccess
        }
    }

    private fun persistLegacy(activity: SportActivity) {
        val payload = SportActivity.serialize(activity)
        assertTrue(
            PhoneActivityStore(context).persist(
                "synthetic-watch",
                "synthetic-${activity.Id}",
                payload,
            ),
        )
    }

    private fun waitForSharedTcx(directory: File): File {
        val deadline = SystemClock.uptimeMillis() + 12_000L
        while (SystemClock.uptimeMillis() < deadline) {
            val files = directory.listFiles { file ->
                file.isFile &&
                    file.extension == "tcx" &&
                    file.name != "ui-compose-save.tcx"
            }
            if (!files.isNullOrEmpty() && files.first().length() > 0L) {
                return files.first()
            }
            SystemClock.sleep(100L)
        }
        throw AssertionError("Timed out waiting for shared TCX")
    }

    private fun assertTcx(file: File) {
        assertTrue(file.isFile)
        assertTrue(file.length() > 0L)
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val document = factory.newDocumentBuilder().parse(
            ByteArrayInputStream(file.readBytes()),
        )
        assertEquals(
            "TrainingCenterDatabase",
            document.documentElement.localName,
        )
    }

    private fun clearAppData() {
        deleteRecursively(File(context.filesDir, "received-sport-activities"))
        deleteRecursively(File(context.filesDir, "shared-tcx"))
        context.deleteDatabase(PhoneDatabase.DATABASE_NAME)
    }

    private fun activity(
        name: String,
        startTime: Long,
        distanceM: Double,
    ): SportActivity {
        val activity = SportActivity().apply {
            Id = ((startTime / 1_000L) % Int.MAX_VALUE).toInt().coerceAtLeast(1)
            Name = name
            Description = "Clearly synthetic migrated activity"
            StartTimeUTC = Date(startTime)
            EndTimeUTC = Date(startTime + 65_000L)
            Distance = distanceM
        }
        repeat(3) { index ->
            activity.SportTrackPoints.add(
                SportTrackPoint().apply {
                    TimeStampUTC = Date(startTime + index * 1_000L)
                    Latitude = if (index == 0) 0.0 else 47.6205 + index * 0.0001
                    Longitude = if (index == 0) 0.0 else -122.3493
                    Altitude = if (index == 0) 0.0 else 10.0
                    HeartRate = 100.0 + index
                },
            )
        }
        return activity
    }

    private fun deleteRecursively(file: File) {
        if (!file.exists()) {
            return
        }
        file.listFiles()?.forEach(::deleteRecursively)
        file.delete()
    }
}
