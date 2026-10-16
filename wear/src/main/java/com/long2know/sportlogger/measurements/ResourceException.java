package com.long2know.sportlogger.measurements;

public final class ResourceException extends Exception {
    private final ResourceFailure failure;
    private final boolean retryable;

    public ResourceException(ResourceFailure failure, boolean retryable) {
        super(failure.getMessage());
        this.failure = failure;
        this.retryable = retryable;
    }

    public ResourceFailure getFailure() {
        return failure;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
