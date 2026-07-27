package com.long2know.sportlogger.services;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class RecordingWriterCoordinator {
    interface Scheduler {
        void scheduleAtFixedRate(
                Runnable task, long initialDelay, long period, TimeUnit unit);

        void shutdownNow();

        boolean awaitTermination(long timeoutMillis) throws InterruptedException;

        boolean isTerminated();
    }

    interface SchedulerFactory {
        Scheduler create();
    }

    interface TaskFactory {
        Runnable create(GenerationToken generation);
    }

    interface FailureListener {
        void onFailure(GenerationToken generation, RuntimeException exception);
    }

    enum StartStatus {
        STARTED,
        ALREADY_RUNNING,
        PREVIOUS_GENERATION_ACTIVE,
        START_FAILED
    }

    static final class GenerationToken {
        private final Object _owner;
        private final int _activityId;
        private final long _generation;
        private final AtomicBoolean _active = new AtomicBoolean(true);
        private volatile RuntimeException _failure;

        private GenerationToken(Object owner, int activityId, long generation) {
            _owner = owner;
            _activityId = activityId;
            _generation = generation;
        }

        int getActivityId() {
            return _activityId;
        }

        long getGeneration() {
            return _generation;
        }

        private boolean belongsTo(Object owner) {
            return _owner == owner;
        }

        private boolean isActive() {
            return _active.get();
        }

        private void deactivate() {
            _active.set(false);
        }

        private void fail(RuntimeException exception) {
            _failure = exception;
            deactivate();
        }

        private boolean failed() {
            return _failure != null;
        }
    }

    private static final class ExecutorScheduler implements Scheduler {
        private final ScheduledExecutorService _executor =
                Executors.newSingleThreadScheduledExecutor();

        @Override
        public void scheduleAtFixedRate(
                Runnable task, long initialDelay, long period, TimeUnit unit) {
            _executor.scheduleAtFixedRate(task, initialDelay, period, unit);
        }

        @Override
        public void shutdownNow() {
            _executor.shutdownNow();
        }

        @Override
        public boolean awaitTermination(long timeoutMillis) throws InterruptedException {
            return _executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        @Override
        public boolean isTerminated() {
            return _executor.isTerminated();
        }
    }

    private static final class Generation {
        final GenerationToken token;
        final Scheduler scheduler;

        Generation(GenerationToken token, Scheduler scheduler) {
            this.token = token;
            this.scheduler = scheduler;
        }
    }

    private final SchedulerFactory _schedulerFactory;
    private long _nextGeneration;
    private Generation _current;

    RecordingWriterCoordinator() {
        this(ExecutorScheduler::new);
    }

    RecordingWriterCoordinator(SchedulerFactory schedulerFactory) {
        _schedulerFactory = schedulerFactory;
    }

    synchronized StartStatus start(Object owner, int activityId, TaskFactory taskFactory) {
        return start(owner, activityId, taskFactory, (generation, exception) -> { });
    }

    synchronized StartStatus start(
            Object owner,
            int activityId,
            TaskFactory taskFactory,
            FailureListener failureListener) {
        if (_current != null && _current.scheduler.isTerminated()) {
            boolean failed = _current.token.failed();
            _current = null;
            if (failed) {
                return StartStatus.START_FAILED;
            }
        }
        if (_current != null) {
            return _current.token.belongsTo(owner) && _current.token.isActive()
                    ? StartStatus.ALREADY_RUNNING
                    : StartStatus.PREVIOUS_GENERATION_ACTIVE;
        }

        Scheduler scheduler;
        try {
            scheduler = _schedulerFactory.create();
        } catch (RuntimeException exception) {
            return StartStatus.START_FAILED;
        }

        GenerationToken token =
                new GenerationToken(owner, activityId, ++_nextGeneration);
        Generation generation = new Generation(token, scheduler);
        _current = generation;
        try {
            Runnable task = taskFactory.create(token);
            scheduler.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    if (!token.isActive() || Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    try {
                        task.run();
                    } catch (RuntimeException exception) {
                        token.fail(exception);
                        try {
                            failureListener.onFailure(token, exception);
                        } finally {
                            scheduler.shutdownNow();
                        }
                    }
                }
            }, 0L, 1L, TimeUnit.SECONDS);
            return StartStatus.STARTED;
        } catch (RuntimeException exception) {
            token.deactivate();
            scheduler.shutdownNow();
            if (scheduler.isTerminated()) {
                _current = null;
            }
            return StartStatus.START_FAILED;
        }
    }

    LifecycleTermination fenceOwned(Object owner, long timeoutMillis) {
        return fence(owner, false, timeoutMillis);
    }

    LifecycleTermination fenceAny(long timeoutMillis) {
        return fence(null, true, timeoutMillis);
    }

    synchronized boolean isActive(GenerationToken token) {
        return _current != null
                && _current.token == token
                && token.isActive();
    }

    private LifecycleTermination fence(
            Object owner, boolean anyOwner, long timeoutMillis) {
        Generation generation;
        synchronized (this) {
            generation = _current;
            if (generation == null
                    || (!anyOwner && !generation.token.belongsTo(owner))) {
                return LifecycleTermination.TERMINATED;
            }
            if (generation.scheduler.isTerminated()) {
                _current = null;
                return generation.token.failed()
                        ? LifecycleTermination.FAILED
                        : LifecycleTermination.TERMINATED;
            }
            generation.token.deactivate();
            try {
                generation.scheduler.shutdownNow();
            } catch (RuntimeException exception) {
                return LifecycleTermination.FAILED;
            }
        }

        boolean terminated;
        try {
            terminated = generation.scheduler.awaitTermination(
                    Math.max(0L, timeoutMillis));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return LifecycleTermination.INTERRUPTED;
        } catch (RuntimeException exception) {
            return LifecycleTermination.FAILED;
        }

        synchronized (this) {
            if (_current == generation
                    && (terminated || generation.scheduler.isTerminated())) {
                _current = null;
                return generation.token.failed()
                        ? LifecycleTermination.FAILED
                        : LifecycleTermination.TERMINATED;
            }
            return _current == generation
                    ? LifecycleTermination.TIMED_OUT
                    : LifecycleTermination.TERMINATED;
        }
    }
}
