package com.long2know.sportlogger.services;

public final class RecordingOperationResult {
    public enum Status {
        SUCCESS,
        NO_OP,
        INVALID_STATE,
        PERMISSION_DENIED,
        WRITER_TIMED_OUT,
        WRITER_FAILED,
        LISTENER_TIMED_OUT,
        LISTENER_FAILED,
        INTERRUPTED,
        START_FAILED,
        DATABASE_FAILED
    }

    public enum RecoveryAction {
        NONE,
        RETURN_TO_START,
        SHOW_PAUSED_CONTROLS,
        SHOW_RECOVERY_RETRY
    }

    private final Status _status;
    private final int _activityId;
    private final RecoveryAction _recoveryAction;

    private RecordingOperationResult(
            Status status, int activityId, RecoveryAction recoveryAction) {
        _status = status;
        _activityId = activityId;
        _recoveryAction = recoveryAction;
    }

    public static RecordingOperationResult of(Status status, int activityId) {
        return new RecordingOperationResult(
                status, activityId, RecoveryAction.NONE);
    }

    public static RecordingOperationResult recovery(
            Status status, int activityId, RecoveryAction recoveryAction) {
        return new RecordingOperationResult(status, activityId, recoveryAction);
    }

    static RecordingOperationResult success(int activityId) {
        return of(Status.SUCCESS, activityId);
    }

    static RecordingOperationResult noOp(int activityId) {
        return of(Status.NO_OP, activityId);
    }

    public Status getStatus() {
        return _status;
    }

    public int getActivityId() {
        return _activityId;
    }

    public RecoveryAction getRecoveryAction() {
        return _recoveryAction;
    }

    public boolean isSuccess() {
        return _status == Status.SUCCESS;
    }

    public boolean isNoOp() {
        return _status == Status.NO_OP;
    }
}
