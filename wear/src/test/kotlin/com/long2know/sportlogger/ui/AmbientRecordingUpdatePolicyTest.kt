package com.long2know.sportlogger.ui

import com.long2know.sportlogger.recording.RecordingAuthority
import com.long2know.sportlogger.recording.RecordingSnapshot
import com.long2know.sportlogger.recording.RecordingState
import com.long2know.utilities.contract.proto.ActivityType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientRecordingUpdatePolicyTest {
    @Test
    fun sameActiveAndElapsedMinuteBucketsRemainThrottled() {
        val previous = snapshot(activeMillis = 10_100L, elapsedMillis = 40_200L)
        val next = snapshot(activeMillis = 10_900L, elapsedMillis = 40_900L)

        assertFalse(AmbientRecordingUpdatePolicy.shouldApply(previous, next))
    }

    @Test
    fun activeBoundaryUpdatesWhenElapsedBucketIsUnchangedByPauseOffset() {
        val previous = snapshot(activeMillis = 59_900L, elapsedMillis = 90_100L)
        val next = snapshot(activeMillis = 60_100L, elapsedMillis = 90_300L)

        assertTrue(AmbientRecordingUpdatePolicy.shouldApply(previous, next))
    }

    @Test
    fun elapsedBoundaryAlsoUpdatesWhenActiveBucketIsUnchanged() {
        val previous = snapshot(activeMillis = 20_100L, elapsedMillis = 59_900L)
        val next = snapshot(activeMillis = 20_300L, elapsedMillis = 60_100L)

        assertTrue(AmbientRecordingUpdatePolicy.shouldApply(previous, next))
    }

    private fun snapshot(
        activeMillis: Long,
        elapsedMillis: Long,
    ): RecordingSnapshot =
        RecordingSnapshot(
            RecordingState.RECORDING,
            7,
            ActivityType.ACTIVITY_TYPE_RUNNING,
            RecordingAuthority.CANONICAL,
            1_000L,
            activeMillis,
            elapsedMillis,
            5_000L,
            5_000L,
            3,
            null,
            1,
            null,
            null,
        )
}
