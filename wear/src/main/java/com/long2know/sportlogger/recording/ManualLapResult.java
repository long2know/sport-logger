package com.long2know.sportlogger.recording;

import java.util.Objects;

public final class ManualLapResult {
    public enum Status {
        ACCEPTED,
        REJECTED_NOT_RECORDING,
        REJECTED_BUSY,
        REJECTED_LEGACY_AUTHORITY,
        FAILED
    }

    private final Status status;
    private final RecordingSnapshot snapshot;
    private final String message;

    private ManualLapResult(
            Status status,
            RecordingSnapshot snapshot,
            String message) {
        this.status = Objects.requireNonNull(status);
        this.snapshot = Objects.requireNonNull(snapshot);
        this.message = message == null ? "" : message;
    }

    public static ManualLapResult accepted(RecordingSnapshot snapshot) {
        return new ManualLapResult(Status.ACCEPTED, snapshot, "");
    }

    public static ManualLapResult rejected(
            Status status,
            RecordingSnapshot snapshot,
            String message) {
        if (status == Status.ACCEPTED) {
            throw new IllegalArgumentException("Use accepted() for accepted laps");
        }
        return new ManualLapResult(status, snapshot, message);
    }

    public Status getStatus() {
        return status;
    }

    public RecordingSnapshot getSnapshot() {
        return snapshot;
    }

    public String getMessage() {
        return message;
    }

    public boolean isAccepted() {
        return status == Status.ACCEPTED;
    }
}
