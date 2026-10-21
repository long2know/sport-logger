package com.long2know.sportlogger.recording;

import com.long2know.utilities.contract.proto.ActivityRecord;

import java.util.Objects;

public final class CompletedRecordingSnapshot {
    private final ActivityRecord activity;
    private final String transferId;
    private final String outboxState;
    private final long attemptCount;
    private final Long committedAtUtcMillis;

    public CompletedRecordingSnapshot(
            ActivityRecord activity,
            String transferId,
            String outboxState,
            long attemptCount,
            Long committedAtUtcMillis) {
        this.activity = Objects.requireNonNull(activity);
        this.transferId = Objects.requireNonNull(transferId);
        this.outboxState = Objects.requireNonNull(outboxState);
        this.attemptCount = Math.max(0L, attemptCount);
        this.committedAtUtcMillis = committedAtUtcMillis;
    }

    public ActivityRecord getActivity() {
        return activity;
    }

    public String getTransferId() {
        return transferId;
    }

    public String getOutboxState() {
        return outboxState;
    }

    public long getAttemptCount() {
        return attemptCount;
    }

    public Long getCommittedAtUtcMillis() {
        return committedAtUtcMillis;
    }
}
