package com.long2know.sportlogger

import android.app.Instrumentation
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.long2know.sportlogger.persistence.phone.PhoneDatabase
import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository
import com.long2know.sportlogger.publishing.endurain.EndurainDeliveryKey
import com.long2know.sportlogger.publishing.endurain.factory.EndurainPublishingFactory
import com.long2know.sportlogger.publishing.endurain.factory.EndurainDeliveryStatus
import com.long2know.sportlogger.transfer.PhoneActivityStore
import com.long2know.utilities.models.SportActivity
import com.long2know.utilities.models.SportTrackPoint
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
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
        instrumentation.waitForIdleSync()
        clearAppData()
    }

    @Test
    fun legacyInboxMigratesAndEditedMetadataSurvivesRecreation() {
        persistLegacy(activity("Synthetic restart activity", 1_700_000_000_000L, 250.0))
        launch()

        waitForText("Synthetic restart activity")
        capture("compose-history.png")
        compose.onNodeWithText("Synthetic restart activity").performClick()
        waitForTag("detail_screen")
        compose.onNodeWithText(context.getString(R.string.route_preview_title))
            .performScrollTo()
            .assertIsDisplayed()
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
        compose.onNodeWithText("Clearly synthetic phone notes")
            .performScrollTo()
            .assertIsDisplayed()
        capture("compose-detail-edited.png")
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
        capture("compose-trends.png")

        compose.onNodeWithText(context.getString(R.string.nav_status)).performClick()
        waitForTag("status_screen")
        waitForText("3 canonical activities")
        compose.onNodeWithText(context.getString(R.string.legacy_recovery_title))
            .assertIsDisplayed()
        compose.onNodeWithTag("status_screen")
            .performScrollToNode(hasText("Health Connect"))
        compose.onNodeWithText("Health Connect").assertIsDisplayed()
        compose.onNodeWithTag("status_screen")
            .performScrollToNode(hasText("Endurain"))
        compose.onNodeWithText("Endurain").assertIsDisplayed()
        capture("compose-status.png")
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
        scenario?.onActivity { activity ->
            ViewModelProvider(activity)[ComposePhoneViewModel::class.java]
                .saveToUri(activity.contentResolver, saveUri)
        }
        waitForFile(saveDestination)
        assertTcx(saveDestination)
        waitForText("TCX saved successfully.")
        capture("compose-export-saved.png")

        compose.onNodeWithTag("share_tcx").performScrollTo().assertIsDisplayed()
        scenario?.onActivity { activity ->
            ViewModelProvider(activity)[ComposePhoneViewModel::class.java]
                .prepareShare(sharedDirectory)
        }
        val shared = waitForSharedTcx(sharedDirectory)
        assertTcx(shared)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    }

    @Test
    fun emptyCanonicalHistoryShowsRecoveryAwareEmptyState() {
        launch()

        waitForText(context.getString(R.string.history_empty))
        compose.onNodeWithText(context.getString(R.string.history_empty))
            .assertIsDisplayed()
    }

    @Test
    fun localDeleteConfirmsPhoneAndWatchThenQueuesCanonicalTombstone() {
        persistLegacy(activity("Synthetic delete activity", 1_700_000_400_000L, 500.0))
        launch()
        waitForText("Synthetic delete activity")
        compose.onNodeWithText("Synthetic delete activity").performClick()
        waitForTag("detail_screen")

        compose.onNodeWithTag("delete_activity").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.delete_activity_title))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.delete_activity_message))
            .assertIsDisplayed()
        capture("compose-delete-confirmation.png")
        compose.onNodeWithTag("confirm_delete").performClick()

        waitForText(context.getString(R.string.history_empty))
        PhoneActivityRepository.open(context).use { repository ->
            val outbox = repository.pendingTombstoneOutbox(10)
            assertEquals(1, outbox.size)
            assertTrue(
                outbox.single().state in setOf(
                    PhoneActivityRepository.TOMBSTONE_READY,
                    PhoneActivityRepository.TOMBSTONE_SENDING,
                    PhoneActivityRepository.TOMBSTONE_AWAITING_COMMIT_ACK,
                    PhoneActivityRepository.TOMBSTONE_RETRYABLE,
                ),
            )
        }
        compose.onNodeWithText(context.getString(R.string.nav_status)).performClick()
        waitForText(context.getString(R.string.status_pending_delete_count, 1))
        capture("compose-delete-queued.png")
    }

    @Test
    fun endurainRecoveryActionsPersistOwnershipAndValidateEveryRemoteId() {
        persistLegacy(activity("Synthetic Endurain activity", 1_700_000_500_000L, 750.0))
        launch()
        waitForText("Synthetic Endurain activity")
        compose.onNodeWithText(context.getString(R.string.nav_status)).performClick()
        waitForTag("status_screen")
        compose.onNodeWithTag("status_screen")
            .performScrollToNode(hasText(context.getString(R.string.endurain_configure)))
        compose.onNodeWithTag("endurain_configure").performClick()
        compose.onNodeWithTag("endurain_label")
            .performTextReplacement("Synthetic Endurain")
        compose.onNodeWithTag("endurain_base_url")
            .performTextReplacement("https://example.invalid")
        compose.onNodeWithTag("endurain_api_key")
            .performTextReplacement("synthetic-test-api-key")
        compose.onNodeWithTag("endurain_save").performClick()

        val connectionIdentity = waitForEndurainCredential()
        compose.onNodeWithTag("status_screen").performScrollToNode(
            hasText(context.getString(R.string.endurain_publish)),
        )
        compose.mainClock.advanceTimeBy(10_000L)
        compose.waitForIdle()
        compose.onNodeWithTag("endurain_publish_$connectionIdentity")
            .performTouchInput { click() }
        val queuedDelivery = waitForEndurainDelivery()
        waitForNoticeConsumed("Endurain upload is queued")
        compose.onNodeWithTag("status_screen").performScrollToNode(
            hasText(context.getString(R.string.endurain_remote_mutations_unsupported)),
        )
        compose.onNodeWithText(context.getString(R.string.endurain_remote_mutations_unsupported))
            .assertIsDisplayed()

        forceEndurainUnknownDelivery(queuedDelivery.key)
        scenario?.recreate()
        waitForTag("status_screen")
        val unknownDelivery = waitForEndurainDelivery {
            it.key == queuedDelivery.key &&
                it.state == EndurainDeliveryStatus.State.UNKNOWN_DELIVERY
        }
        compose.onNodeWithTag("status_screen").performScrollToNode(
            hasText(context.getString(R.string.endurain_reconcile)),
        )
        compose.onNodeWithText(context.getString(R.string.endurain_reconcile))
            .performClick()
        waitForTag("endurain_remote_ids")
        checkNotNull(scenario).onActivity { activity ->
            val reconcile = checkNotNull(
                ViewModelProvider(activity)[ComposePhoneViewModel::class.java]
                    .state
                    .value
                    .endurainReconcile,
            )
            assertEquals(unknownDelivery.key, reconcile.deliveryKey)
        }
        compose.onNodeWithTag("endurain_remote_ids")
            .performTextReplacement("41,bad,-2")
        compose.onNodeWithTag("endurain_reconcile_save").performClick()

        val validationError =
            "Each remote ID must be a comma-separated positive integer " +
                "between 1 and ${Long.MAX_VALUE}."
        waitForText(validationError)
        compose.onNodeWithTag("endurain_remote_ids").assertIsDisplayed()
        PhoneActivityRepository.open(context).use { repository ->
            val persisted = checkNotNull(
                repository.connectorDelivery(unknownDelivery.key.toRepositoryKey()),
            )
            assertEquals(
                PhoneActivityRepository.ConnectorState.UNKNOWN_DELIVERY,
                persisted.state,
            )
            assertTrue(persisted.remoteIds.isEmpty())
        }

        compose.onNodeWithTag("endurain_remote_ids")
            .performTextReplacement("41,42")
        compose.onNodeWithTag("endurain_reconcile_save").performClick()
        val reconciledDelivery = waitForEndurainDelivery {
            it.key == queuedDelivery.key && it.remoteIds == listOf(41L, 42L)
        }
        waitForNoticeConsumed()

        val remoteEditTag = "endurain_remote_edit_${reconciledDelivery.artifactId}"
        compose.onNodeWithTag("status_screen").performScrollToNode(
            hasTestTag(remoteEditTag),
        )
        compose.onNodeWithTag(remoteEditTag).performClick()
        waitForText("Endurain remote activity editing is not implemented")
        waitForNoticeConsumed()

        val remoteDeleteTag = "endurain_remote_delete_${reconciledDelivery.artifactId}"
        compose.onNodeWithTag("status_screen").performScrollToNode(
            hasTestTag(remoteDeleteTag),
        )
        compose.onNodeWithTag(remoteDeleteTag).performClick()
        val retainedDelivery = waitForEndurainDelivery {
            it.key == queuedDelivery.key && it.isDeleteUnsupportedRecorded
        }
        waitForNoticeConsumed()
        val retainedStatusTag =
            "endurain_remote_delete_recorded_${retainedDelivery.artifactId}"
        compose.onNodeWithTag("status_screen").performScrollToNode(
            hasTestTag(retainedStatusTag),
        )
        compose.onNodeWithTag(retainedStatusTag).assertIsDisplayed()
        PhoneActivityRepository.open(context).use { repository ->
            val persisted = checkNotNull(
                repository.connectorDelivery(retainedDelivery.key.toRepositoryKey()),
            )
            assertEquals(listOf(41L, 42L), persisted.remoteIds)
            assertTrue(persisted.isDeleteUnsupported)
        }

        scenario?.recreate()
        waitForTag("status_screen")
        waitForEndurainDelivery {
            it.key == queuedDelivery.key &&
                it.remoteIds == listOf(41L, 42L) &&
                it.isDeleteUnsupportedRecorded
        }
        compose.onNodeWithTag("status_screen").performScrollToNode(
            hasTestTag(retainedStatusTag),
        )
        compose.onNodeWithTag(retainedStatusTag).assertIsDisplayed()
        capture("compose-endurain.png")
    }

    @Test
    fun endurainPublishingFactoryInitializesWithApplicationStorageAndScheduler() {
        EndurainPublishingFactory.create(context).get().use { controller ->
            controller.statusList().get()
        }
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

    private fun waitForNoticeConsumed(snackbarText: String? = null) {
        val deadline = SystemClock.uptimeMillis() + 12_000L
        var notice: String? = null
        var snackbarNodeCount = 0
        while (SystemClock.uptimeMillis() < deadline) {
            checkNotNull(scenario).onActivity { activity ->
                notice = ViewModelProvider(activity)[ComposePhoneViewModel::class.java]
                    .state
                    .value
                    .notice
            }
            snackbarNodeCount = snackbarText?.let {
                compose.onAllNodesWithText(it, substring = true)
                    .fetchSemanticsNodes()
                    .size
            } ?: 0
            if (notice == null && snackbarNodeCount == 0) {
                compose.waitForIdle()
                return
            }
            compose.mainClock.advanceTimeBy(1_000L)
            compose.waitForIdle()
        }
        throw AssertionError(
            "Timed out waiting for notice consumption: notice=$notice, " +
                "snackbarNodes=$snackbarNodeCount",
        )
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

    private fun waitForFile(file: File) {
        val deadline = SystemClock.uptimeMillis() + 12_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (file.isFile && file.length() > 0L) {
                return
            }
            SystemClock.sleep(100L)
        }
        throw AssertionError("Timed out waiting for TCX output: ${file.name}")
    }

    private fun waitForEndurainCredential(): String {
        val deadline = SystemClock.uptimeMillis() + 12_000L
        var diagnostic = "controller snapshot unavailable"
        while (SystemClock.uptimeMillis() < deadline) {
            var availableIdentity: String? = null
            scenario?.onActivity { activity ->
                val state = ViewModelProvider(activity)[ComposePhoneViewModel::class.java]
                    .state
                    .value
                diagnostic = buildString {
                    append("credentials=")
                    append(
                        state.endurain.snapshot
                            ?.connections
                            ?.map { it.credentialState.name },
                    )
                    append(", error=")
                    append(state.endurain.error)
                    append(", editorError=")
                    append(state.endurainEditor?.error)
                }
                if (state.endurainEditor == null) {
                    availableIdentity = state.endurain.snapshot
                        ?.connections
                        ?.firstOrNull { it.credentialState.name == "AVAILABLE" }
                        ?.profile
                        ?.connectionIdentity
                }
            }
            if (availableIdentity != null) {
                return availableIdentity!!
            }
            SystemClock.sleep(100L)
        }
        throw AssertionError("Timed out waiting for Endurain credential state: $diagnostic")
    }

    private fun waitForEndurainDelivery(
        predicate: (EndurainDeliveryStatus) -> Boolean = { true },
    ): EndurainDeliveryStatus {
        val deadline = SystemClock.uptimeMillis() + 12_000L
        var diagnostic = "controller snapshot unavailable"
        while (SystemClock.uptimeMillis() < deadline) {
            var matching: EndurainDeliveryStatus? = null
            scenario?.onActivity { activity ->
                val state = ViewModelProvider(activity)[ComposePhoneViewModel::class.java]
                    .state
                    .value
                val deliveries = state.endurain.snapshot?.deliveries.orEmpty()
                diagnostic = buildString {
                    append("deliveries=")
                    append(
                        deliveries.map {
                            "${it.state}:${it.remoteIds}:${it.isDeleteUnsupportedRecorded}"
                        },
                    )
                    append(", error=")
                    append(state.endurain.error)
                    append(", notice=")
                    append(state.notice)
                }
                matching = deliveries.firstOrNull(predicate)
            }
            if (matching != null) {
                return matching!!
            }
            SystemClock.sleep(100L)
        }
        throw AssertionError("Timed out waiting for Endurain delivery: $diagnostic")
    }

    private fun forceEndurainUnknownDelivery(key: EndurainDeliveryKey) {
        val nowUtcMs = System.currentTimeMillis()
        PhoneActivityRepository.open(context).use { repository ->
            val repositoryKey = key.toRepositoryKey()
            repeat(20) { attempt ->
                val delivery = checkNotNull(repository.connectorDelivery(repositoryKey))
                assertTrue(delivery.remoteIds.isEmpty())
                if (
                    delivery.state == PhoneActivityRepository.ConnectorState.UNKNOWN_DELIVERY ||
                    delivery.state == PhoneActivityRepository.ConnectorState.DUPLICATE_REVIEW
                ) {
                    return
                }
                val leaseToken = 9_001L + attempt
                if (repository.claimConnectorDelivery(
                    repositoryKey,
                    delivery.state,
                    leaseToken,
                    "instrumented-reconciliation",
                    nowUtcMs + 60_000L,
                    nowUtcMs,
                )) {
                    repository.transitionConnectorDelivery(
                        repositoryKey,
                        leaseToken,
                        delivery.state,
                        PhoneActivityRepository.ConnectorState.UNKNOWN_DELIVERY,
                        null,
                        "SYNTHETIC_UNKNOWN_DELIVERY",
                        "Synthetic ambiguous delivery for reconciliation UI coverage",
                        nowUtcMs,
                    )
                    return
                }
                if (repository.recoverExpiredConnectorClaimAsUnknown(
                    repositoryKey,
                    nowUtcMs + 16L * 60L * 1_000L,
                )) {
                    return
                }
                SystemClock.sleep(100L)
            }
        }
        throw AssertionError("Could not establish an unknown Endurain delivery for reconciliation")
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

    private fun capture(fileName: String) {
        instrumentation.waitForIdleSync()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
            ?: throw AssertionError("Screenshot capture returned null")
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/SportLoggerComposeEvidence/"
        context.contentResolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(fileName, relativePath),
        )
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val destination: Uri = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values,
        ) ?: throw AssertionError("Could not create screenshot destination")
        try {
            context.contentResolver.openOutputStream(destination, "w").use { output ->
                checkNotNull(output)
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            screenshot.recycle()
        }
        val complete = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        assertEquals(
            1,
            context.contentResolver.update(destination, complete, null, null),
        )
    }

    private fun clearAppData() {
        deleteRecursively(File(context.filesDir, "received-sport-activities"))
        deleteRecursively(File(context.filesDir, "shared-tcx"))
        deleteRecursively(File(context.noBackupFilesDir, "endurain-publishing-v1"))
        deleteRecursively(File(context.noBackupFilesDir, "connector-credentials-v1"))
        context.getSharedPreferences("endurain_connection_profiles_v1", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
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
