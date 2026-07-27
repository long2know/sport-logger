package com.long2know.sportlogger.services;

public final class RecordingOperationResult {
    public enum Operation {
        NONE,
        STARTUP,
        START,
        PAUSE,
        RESUME,
        STOP,
        DISCARD,
        RECOVER,
        PERMISSION_LOSS
    }

    public enum Status {
        ACCEPTED,
        OPERATION_PENDING,
        SERVICE_CLOSED,
        STALE_OPERATION,
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
        RECOVERY_IN_PROGRESS,
        TERMINAL_PERSISTENCE_FAILED,
        TERMINAL_COMPLETION_PENDING
    }

    public enum RecoveryAction {
        NONE,
        RETURN_TO_START,
        SHOW_PAUSED_CONTROLS,
        SHOW_RECOVERY_RETRY,
        SHOW_TERMINAL_RETRY
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
    private final Operation _operation;
    private final long _operationToken;
    private final long _terminalCompletionId;
    private final long _writerGeneration;

    private RecordingOperationResult(
            Status status,
            int activityId,
            RecoveryAction recoveryAction,
            RecoveryRetention recoveryRetention,
            Operation operation,
            long operationToken,
            long terminalCompletionId,
            long writerGeneration) {
        _status = status;
        _activityId = activityId;
        _recoveryAction = recoveryAction;
        _recoveryRetention = recoveryRetention;
        _operation = operation;
        _operationToken = operationToken;
        _terminalCompletionId = terminalCompletionId;
        _writerGeneration = writerGeneration;
    }

    public static RecordingOperationResult of(Status status, int activityId) {
        return new RecordingOperationResult(
                status,
                activityId,
                RecoveryAction.NONE,
                RecoveryRetention.NONE,
                Operation.NONE,
                0L,
                0L,
                0L);
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
                status,
                activityId,
                recoveryAction,
                recoveryRetention,
                Operation.NONE,
                0L,
                0L,
                0L);
    }

    static RecordingOperationResult success(int activityId) {
        return of(Status.SUCCESS, activityId);
    }

    static RecordingOperationResult noOp(int activityId) {
        return of(Status.NO_OP, activityId);
    }

    static RecordingOperationResult accepted(
            Operation operation, long operationToken, int activityId) {
        return operation(
                Status.ACCEPTED,
                operation,
                operationToken,
                activityId,
                RecoveryAction.NONE,
                RecoveryRetention.NONE);
    }

    static RecordingOperationResult pending(
            Operation operation, long operationToken, int activityId) {
        return operation(
                Status.OPERATION_PENDING,
                operation,
                operationToken,
                activityId,
                RecoveryAction.NONE,
                RecoveryRetention.NONE);
    }

    static RecordingOperationResult operation(
            Status status,
            Operation operation,
            long operationToken,
            int activityId,
            RecoveryAction recoveryAction,
            RecoveryRetention recoveryRetention) {
        return new RecordingOperationResult(
                status,
                activityId,
                recoveryAction,
                recoveryRetention,
                operation,
                operationToken,
                0L,
                0L);
    }

    static RecordingOperationResult forOperation(
            RecordingOperationResult result,
            Operation operation,
            long operationToken) {
        return new RecordingOperationResult(
                result.getStatus(),
                result.getActivityId(),
                result.getRecoveryAction(),
                result.getRecoveryRetention(),
                operation,
                operationToken,
                result.getTerminalCompletionId(),
                result.getWriterGeneration());
    }

    static RecordingOperationResult terminalStopSuccess(
            RecordingTerminalCompletion completion, long operationToken) {
        return new RecordingOperationResult(
                Status.SUCCESS,
                completion.getActivityId(),
                RecoveryAction.NONE,
                RecoveryRetention.NONE,
                Operation.STOP,
                operationToken,
                completion.getOperationId(),
                completion.getGeneration());
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

    public Operation getOperation() {
        return _operation;
    }

    public long getOperationToken() {
        return _operationToken;
    }

    public long getTerminalCompletionId() {
        return _terminalCompletionId;
    }

    public long getWriterGeneration() {
        return _writerGeneration;
    }

    public boolean hasTerminalCompletion() {
        return _terminalCompletionId > 0L
                && _writerGeneration > 0L
                && _operation == Operation.STOP
                && _status == Status.SUCCESS;
    }

    public boolean isAccepted() {
        return _status == Status.ACCEPTED;
    }

    public boolean isPending() {
        return _status == Status.OPERATION_PENDING;
    }

    public boolean isSuccess() {
        return _status == Status.SUCCESS;
    }

    public boolean isNoOp() {
        return _status == Status.NO_OP;
    }
}
