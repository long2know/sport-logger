package com.long2know.sportlogger

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.long2know.sportlogger.transfer.PhoneSyncEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class MainActivityBroadcastReceiverTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun registrationFiltersKeepReceiptAndCanonicalActionsSeparated() {
        val activityFilter = ActivityReceiptReceiver.intentFilter()
        val canonicalFilter = CanonicalSyncReceiver.intentFilter()

        assertEquals(1, activityFilter.countActions())
        assertTrue(activityFilter.hasAction(ListenerService.ACTION_ACTIVITY_RECEIVED))
        assertFalse(
            activityFilter.hasAction(PhoneSyncEvents.ACTION_TOMBSTONE_SYNC_STATE_CHANGED),
        )

        assertEquals(3, canonicalFilter.countActions())
        assertTrue(canonicalFilter.hasAction(PhoneSyncEvents.ACTION_CANONICAL_ACTIVITY_CHANGED))
        assertTrue(canonicalFilter.hasAction(PhoneSyncEvents.ACTION_CANONICAL_SYNC_FAILED))
        assertTrue(
            canonicalFilter.hasAction(PhoneSyncEvents.ACTION_TOMBSTONE_SYNC_STATE_CHANGED),
        )
        assertFalse(canonicalFilter.hasAction(ListenerService.ACTION_ACTIVITY_RECEIVED))
    }

    @Test
    fun activityReceiptInvokesReceiptHandler() {
        var receiptCount = 0
        var historyCount = 0
        val receiver = ActivityReceiptReceiver(
            onActivityReceived = { receiptCount += 1 },
            onHistoryChanged = { historyCount += 1 },
        )

        receiver.onReceive(context, Intent(ListenerService.ACTION_ACTIVITY_RECEIVED))

        assertEquals(1, receiptCount)
        assertEquals(0, historyCount)
    }

    @Test
    fun canonicalActivityChangedForwardsAcknowledgementWithoutFailure() {
        val acknowledgements = mutableListOf<Boolean>()
        var failureCount = 0
        val tombstoneStates = mutableListOf<String>()
        val receiver = CanonicalSyncReceiver(
            onCanonicalSyncHint = acknowledgements::add,
            onCanonicalSyncFailure = { failureCount += 1 },
            onTombstoneSyncState = tombstoneStates::add,
        )

        receiver.onReceive(
            context,
            Intent(PhoneSyncEvents.ACTION_CANONICAL_ACTIVITY_CHANGED)
                .putExtra(PhoneSyncEvents.EXTRA_ACK_DELIVERED, true),
        )

        assertEquals(listOf(true), acknowledgements)
        assertEquals(0, failureCount)
        assertTrue(tombstoneStates.isEmpty())
    }

    @Test
    fun canonicalSyncFailureInvokesOnlyFailureHandler() {
        val acknowledgements = mutableListOf<Boolean>()
        var failureCount = 0
        val tombstoneStates = mutableListOf<String>()
        val receiver = CanonicalSyncReceiver(
            onCanonicalSyncHint = acknowledgements::add,
            onCanonicalSyncFailure = { failureCount += 1 },
            onTombstoneSyncState = tombstoneStates::add,
        )

        receiver.onReceive(
            context,
            Intent(PhoneSyncEvents.ACTION_CANONICAL_SYNC_FAILED),
        )

        assertTrue(acknowledgements.isEmpty())
        assertEquals(1, failureCount)
        assertTrue(tombstoneStates.isEmpty())
    }

    @Test
    fun tombstoneDisplayStatesReachTombstoneHandlerWithoutFailure() {
        val acknowledgements = mutableListOf<Boolean>()
        var failureCount = 0
        val tombstoneStates = mutableListOf<String>()
        val receiver = CanonicalSyncReceiver(
            onCanonicalSyncHint = acknowledgements::add,
            onCanonicalSyncFailure = { failureCount += 1 },
            onTombstoneSyncState = tombstoneStates::add,
        )
        val expectedStates = PhoneSyncEvents.DisplayState.values().map { it.name }

        expectedStates.forEach { displayState ->
            receiver.onReceive(
                context,
                Intent(PhoneSyncEvents.ACTION_TOMBSTONE_SYNC_STATE_CHANGED)
                    .putExtra(PhoneSyncEvents.EXTRA_DISPLAY_STATE, displayState),
            )
        }

        assertTrue(acknowledgements.isEmpty())
        assertEquals(0, failureCount)
        assertEquals(expectedStates, tombstoneStates)
    }
}
