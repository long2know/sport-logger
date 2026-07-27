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

    interface GenerationClaim {
        boolean claim(GenerationToken generation);
    }

    interface FenceClaim {
        boolean claim();
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
        return start(
                owner,
                activityId,
                taskFactory,
                generation -> true,
                (generation, exception) -> { });
    }

    synchronized StartStatus start(
            Object owner,
            int activityId,
            TaskFactory taskFactory,
            FailureListener failureListener) {
        return start(
                owner,
                activityId,
                taskFactory,
                generation -> true,
                failureListener);
    }

    synchronized StartStatus start(
            Object owner,
            int activityId,
            TaskFactory taskFactory,
            GenerationClaim generationClaim,
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
            if (!generationClaim.claim(token)) {
                token.deactivate();
                scheduler.shutdownNow();
                _current = null;
                return StartStatus.START_FAILED;
            }
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

    synchronized void restoreGenerationFloor(long generation) {
        if (generation > _nextGeneration) {
            _nextGeneration = generation;
        }
    }

    synchronized long generationFor(Object owner, int activityId) {
        if (_current == null
                || !_current.token.belongsTo(owner)
                || _current.token.getActivityId() != activityId) {
            return 0L;
        }
        return _current.token.getGeneration();
    }

    LifecycleTermination fenceOwned(Object owner, long timeoutMillis) {
        return fence(owner, false, 0, 0L, false, null, timeoutMillis);
    }

    LifecycleTermination fenceAny(long timeoutMillis) {
        return fence(null, true, 0, 0L, false, null, timeoutMillis);
    }

    LifecycleTermination fenceAny(
            FenceClaim fenceClaim, long timeoutMillis) {
        return fence(
                null,
                true,
                0,
                0L,
                false,
                fenceClaim,
                timeoutMillis);
    }

    LifecycleTermination fenceGeneration(
            int activityId, long generation, long timeoutMillis) {
        return fence(
                null,
                false,
                activityId,
                generation,
                true,
                null,
                timeoutMillis);
    }

    boolean requestFenceOwned(Object owner) {
        return requestFence(owner, false, 0, 0L, false);
    }

    boolean requestFenceGeneration(int activityId, long generation) {
        return requestFence(
                null, false, activityId, generation, true);
    }

    synchronized boolean isActive(GenerationToken token) {
        return _current != null
                && _current.token == token
                && token.isActive();
    }

    private boolean requestFence(
            Object owner,
            boolean anyOwner,
            int activityId,
            long generationNumber,
            boolean exactGeneration) {
        synchronized (this) {
            Generation generation = _current;
            if (generation == null) {
                return true;
            }
            if (exactGeneration
                    && (generation.token.getActivityId() != activityId
                    || generation.token.getGeneration()
                            != generationNumber)) {
                return false;
            }
            if (!exactGeneration
                    && !anyOwner
                    && !generation.token.belongsTo(owner)) {
                return true;
            }
            generation.token.deactivate();
            try {
                generation.scheduler.shutdownNow();
                return true;
            } catch (RuntimeException exception) {
                return false;
            }
        }
    }

    private LifecycleTermination fence(
            Object owner,
            boolean anyOwner,
            int activityId,
            long generationNumber,
            boolean exactGeneration,
            FenceClaim fenceClaim,
            long timeoutMillis) {
        Generation generation;
        synchronized (this) {
            if (fenceClaim != null && !fenceClaim.claim()) {
                return LifecycleTermination.TERMINATED;
            }
            generation = _current;
            if (generation == null) {
                return LifecycleTermination.TERMINATED;
            }
            if (exactGeneration
                    && (generation.token.getActivityId() != activityId
                    || generation.token.getGeneration() != generationNumber)) {
                return LifecycleTermination.TIMED_OUT;
            }
            if (!exactGeneration
                    && !anyOwner
                    && !generation.token.belongsTo(owner)) {
                return LifecycleTermination.TERMINATED;
            }
            if (generation.scheduler.isTerminated()) {
                _current = null;
                return generation.token.failed()
                        ? LifecycleTermination.TERMINATED_WITH_FAILURE
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
                        ? LifecycleTermination.TERMINATED_WITH_FAILURE
                        : LifecycleTermination.TERMINATED;
            }
            if (_current == generation) {
                return LifecycleTermination.TIMED_OUT;
            }
            return generation.token.failed()
                    ? LifecycleTermination.TERMINATED_WITH_FAILURE
                    : LifecycleTermination.TERMINATED;
        }
    }
}
