package com.long2know.sportlogger.ui

import com.long2know.sportlogger.recording.RecordingSnapshot

internal object AmbientRecordingUpdatePolicy {
    fun shouldApply(
        previous: RecordingSnapshot,
        next: RecordingSnapshot,
    ): Boolean =
        previous.state != next.state ||
            previous.activityId != next.activityId ||
            previous.activityType != next.activityType ||
            previous.authority != next.authority ||
            previous.currentLapIndex != next.currentLapIndex ||
            previous.lastLapElapsedDurationMillis != next.lastLapElapsedDurationMillis ||
            previous.lastLapActiveDurationMillis != next.lastLapActiveDurationMillis ||
            previous.error != next.error ||
            minuteBucket(previous.durationMillis) != minuteBucket(next.durationMillis) ||
            minuteBucket(previous.elapsedDurationMillis) !=
            minuteBucket(next.elapsedDurationMillis)

    private fun minuteBucket(durationMillis: Long): Long =
        durationMillis / 60_000L
}
