package com.long2know.sportlogger.services;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

final class RecordingOperationDispatcher {
    interface Executor {
        void execute(Runnable task);

        void shutdownNow();
    }

    enum BeginStatus {
        ACCEPTED,
        PENDING,
        CLOSED
    }

    enum DispatchStatus {
        SCHEDULED,
        STALE,
        REJECTED
    }

    static final class Token {
        private final long _serviceGeneration;
        private final long _operationToken;
        private final RecordingOperationResult.Operation _operation;
        private final int _activityId;
        private final long _writerGeneration;

        private Token(
                long serviceGeneration,
                long operationToken,
                RecordingOperationResult.Operation operation,
                int activityId,
                long writerGeneration) {
            _serviceGeneration = serviceGeneration;
            _operationToken = operationToken;
            _operation = operation;
            _activityId = activityId;
            _writerGeneration = writerGeneration;
        }

        long getServiceGeneration() {
            return _serviceGeneration;
        }

        long getOperationToken() {
            return _operationToken;
        }

        RecordingOperationResult.Operation getOperation() {
            return _operation;
        }

        int getActivityId() {
            return _activityId;
        }

        long getWriterGeneration() {
            return _writerGeneration;
        }
    }

    static final class BeginResult {
        private final BeginStatus _status;
        private final Token _token;

        private BeginResult(BeginStatus status, Token token) {
            _status = status;
            _token = token;
        }

        BeginStatus getStatus() {
            return _status;
        }

        Token getToken() {
            return _token;
        }
    }

    private static final class ExecutorServiceAdapter implements Executor {
        private final ExecutorService _executor;

        ExecutorServiceAdapter(ExecutorService executor) {
            _executor = executor;
        }

        @Override
        public void execute(Runnable task) {
            _executor.execute(task);
        }

        @Override
        public void shutdownNow() {
            _executor.shutdownNow();
        }
    }

    private final Executor _executor;
    private long _serviceGeneration = 1L;
    private long _nextOperationToken;
    private Token _active;
    private boolean _closed;

    RecordingOperationDispatcher(ExecutorService executor) {
        this(new ExecutorServiceAdapter(executor));
    }

    RecordingOperationDispatcher(Executor executor) {
        _executor = executor;
    }

    synchronized BeginResult begin(
            RecordingOperationResult.Operation operation,
            int activityId,
            long writerGeneration) {
        if (_closed) {
            return new BeginResult(BeginStatus.CLOSED, null);
        }
        if (_active != null) {
            return new BeginResult(BeginStatus.PENDING, _active);
        }
        _active = new Token(
                _serviceGeneration,
                ++_nextOperationToken,
                operation,
                activityId,
                writerGeneration);
        return new BeginResult(BeginStatus.ACCEPTED, _active);
    }

    synchronized boolean owns(Token token) {
        return token != null
                && !_closed
                && token == _active
                && token.getServiceGeneration() == _serviceGeneration;
    }

    synchronized boolean finish(Token token) {
        if (!owns(token)) {
            return false;
        }
        _active = null;
        return true;
    }

    synchronized Token invalidateActive() {
        Token invalidated = _active;
        _active = null;
        return invalidated;
    }

    synchronized Token getActive() {
        return _closed ? null : _active;
    }

    synchronized long getServiceGeneration() {
        return _serviceGeneration;
    }

    synchronized boolean isServiceGenerationCurrent(long serviceGeneration) {
        return !_closed && serviceGeneration == _serviceGeneration;
    }

    DispatchStatus tryExecute(Token token, Runnable task) {
        if (!owns(token)) {
            return DispatchStatus.STALE;
        }
        try {
            _executor.execute(new Runnable() {
                @Override
                public void run() {
                    if (owns(token)) {
                        task.run();
                    }
                }
            });
            return DispatchStatus.SCHEDULED;
        } catch (RejectedExecutionException exception) {
            return DispatchStatus.REJECTED;
        } catch (RuntimeException exception) {
            return DispatchStatus.REJECTED;
        }
    }

    DispatchStatus tryExecute(long serviceGeneration, Runnable task) {
        if (!isServiceGenerationCurrent(serviceGeneration)) {
            return DispatchStatus.STALE;
        }
        return executeGuarded(serviceGeneration, task);
    }

    boolean close() {
        synchronized (this) {
            if (_closed) {
                return true;
            }
            _closed = true;
            _active = null;
            _serviceGeneration++;
        }
        try {
            _executor.shutdownNow();
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    synchronized boolean isClosed() {
        return _closed;
    }

    private DispatchStatus executeGuarded(
            final long serviceGeneration, final Runnable task) {
        try {
            _executor.execute(new Runnable() {
                @Override
                public void run() {
                    if (isServiceGenerationCurrent(serviceGeneration)) {
                        task.run();
                    }
                }
            });
            return DispatchStatus.SCHEDULED;
        } catch (RejectedExecutionException exception) {
            return DispatchStatus.REJECTED;
        } catch (RuntimeException exception) {
            return DispatchStatus.REJECTED;
        }
    }
}
