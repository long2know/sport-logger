package com.long2know.sportlogger.services;

public final class RecordingTerminalCompletion {
    public enum Type {
        STOP_EXPORT
    }

    public enum Result {
        SUCCESS
    }

    private final long _operationId;
    private final int _activityId;
    private final long _generation;
    private final Type _type;
    private final Result _result;

    RecordingTerminalCompletion(
            long operationId,
            int activityId,
            long generation,
            Type type,
            Result result) {
        _operationId = operationId;
        _activityId = activityId;
        _generation = generation;
        _type = type;
        _result = result;
    }

    public long getOperationId() {
        return _operationId;
    }

    public int getActivityId() {
        return _activityId;
    }

    public long getGeneration() {
        return _generation;
    }

    public Type getType() {
        return _type;
    }

    public Result getResult() {
        return _result;
    }
}
