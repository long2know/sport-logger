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
        DATABASE_FAILED,
        RECOVERY_PERSISTENCE_FAILED,
        RECOVERY_IN_PROGRESS
    }

    public enum RecoveryAction {
        NONE,
        RETURN_TO_START,
        SHOW_PAUSED_CONTROLS,
        SHOW_RECOVERY_RETRY
    }

    public enum RecoveryRetention {
        NONE,
        DURABLE,
        CURRENT_PROCESS_ONLY
    }

    private final Status _status;
    private final int _activityId;
    private final RecoveryAction _recoveryAction;
    private final RecoveryRetention _recoveryRetention;

    private RecordingOperationResult(
            Status status,
            int activityId,
            RecoveryAction recoveryAction,
            RecoveryRetention recoveryRetention) {
        _status = status;
        _activityId = activityId;
        _recoveryAction = recoveryAction;
        _recoveryRetention = recoveryRetention;
    }

    public static RecordingOperationResult of(Status status, int activityId) {
        return new RecordingOperationResult(
                status,
                activityId,
                RecoveryAction.NONE,
                RecoveryRetention.NONE);
    }

    public static RecordingOperationResult recovery(
            Status status, int activityId, RecoveryAction recoveryAction) {
        RecoveryRetention retention =
                activityId > 0
                        && recoveryAction != RecoveryAction.RETURN_TO_START
                        ? RecoveryRetention.DURABLE
                        : RecoveryRetention.NONE;
        return recovery(status, activityId, recoveryAction, retention);
    }

    public static RecordingOperationResult recovery(
            Status status,
            int activityId,
            RecoveryAction recoveryAction,
            RecoveryRetention recoveryRetention) {
        return new RecordingOperationResult(
                status, activityId, recoveryAction, recoveryRetention);
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

    public RecoveryRetention getRecoveryRetention() {
        return _recoveryRetention;
    }

    public boolean isSuccess() {
        return _status == Status.SUCCESS;
    }

    public boolean isNoOp() {
        return _status == Status.NO_OP;
    }
}
