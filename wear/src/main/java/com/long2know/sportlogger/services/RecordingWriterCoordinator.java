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

    interface Task extends Runnable, AutoCloseable {
        @Override
        default void close() {
        }
    }

    interface TaskFactory {
        Task create(GenerationToken generation);
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
            if (owner == null || activityId <= 0 || generation <= 0L) {
                throw new IllegalArgumentException(
                        "Writer ownership requires an owner, activity, and positive generation.");
            }
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
        final Task task;
        final AtomicBoolean resourcesClosed = new AtomicBoolean(false);

        Generation(GenerationToken token, Scheduler scheduler, Task task) {
            this.token = token;
            this.scheduler = scheduler;
            this.task = task;
        }

        void closeResources() {
            if (!resourcesClosed.compareAndSet(false, true)) {
                return;
            }
            try {
                task.close();
            } catch (RuntimeException exception) {
                // The generation is already quiescent; cleanup remains best effort.
            }
        }
    }

    private static final class Reservation {
        final GenerationToken token;

        Reservation(GenerationToken token) {
            this.token = token;
        }
    }

    private final SchedulerFactory _schedulerFactory;
    private long _nextGeneration;
    private Generation _current;
    private Reservation _reservation;

    RecordingWriterCoordinator() {
        this(ExecutorScheduler::new);
    }

    RecordingWriterCoordinator(SchedulerFactory schedulerFactory) {
        _schedulerFactory = schedulerFactory;
    }

    StartStatus start(Object owner, int activityId, TaskFactory taskFactory) {
        return start(
                owner,
                activityId,
                taskFactory,
                generation -> true,
                (generation, exception) -> { });
    }

    StartStatus start(
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

    StartStatus start(
            Object owner,
            int activityId,
            TaskFactory taskFactory,
            GenerationClaim generationClaim,
            FailureListener failureListener) {
        return start(
                owner,
                activityId,
                0L,
                taskFactory,
                generationClaim,
                failureListener);
    }

    StartStatus start(
            Object owner,
            int activityId,
            long reservedGeneration,
            TaskFactory taskFactory,
            GenerationClaim generationClaim,
            FailureListener failureListener) {
        if (owner == null
                || activityId <= 0
                || reservedGeneration < 0L
                || taskFactory == null
                || generationClaim == null
                || failureListener == null) {
            return StartStatus.START_FAILED;
        }
        final Reservation reservation;
        while (true) {
            Generation completed = null;
            boolean failed = false;
            synchronized (this) {
                if (_current != null && _current.scheduler.isTerminated()) {
                    completed = _current;
                    failed = completed.token.failed();
                    _current = null;
                } else if (_current != null) {
                    return _current.token.belongsTo(owner)
                                    && _current.token.isActive()
                            ? StartStatus.ALREADY_RUNNING
                            : StartStatus.PREVIOUS_GENERATION_ACTIVE;
                } else if (_reservation != null) {
                    return _reservation.token.belongsTo(owner)
                                    && _reservation.token.isActive()
                            ? StartStatus.ALREADY_RUNNING
                            : StartStatus.PREVIOUS_GENERATION_ACTIVE;
                } else {
                    long generation = reservedGeneration > 0L
                            ? reservedGeneration
                            : nextGenerationLocked();
                    if (reservedGeneration > _nextGeneration) {
                        _nextGeneration = reservedGeneration;
                    }
                    GenerationToken token =
                            new GenerationToken(
                                    owner, activityId, generation);
                    reservation = new Reservation(token);
                    _reservation = reservation;
                    break;
                }
            }
            completed.closeResources();
            if (failed) {
                return StartStatus.START_FAILED;
            }
        }

        boolean claimed;
        try {
            claimed = generationClaim.claim(reservation.token);
        } catch (RuntimeException exception) {
            cancelReservation(reservation);
            return StartStatus.START_FAILED;
        }
        if (!claimed || !reservationOwns(reservation)) {
            cancelReservation(reservation);
            return StartStatus.START_FAILED;
        }

        Scheduler scheduler = null;
        Task task = null;
        try {
            scheduler = _schedulerFactory.create();
        } catch (RuntimeException exception) {
            cancelReservation(reservation);
            return StartStatus.START_FAILED;
        }
        if (!reservationOwns(reservation)) {
            closePrepared(reservation.token, scheduler, null);
            return StartStatus.START_FAILED;
        }
        try {
            task = taskFactory.create(reservation.token);
        } catch (RuntimeException exception) {
            cancelReservation(reservation);
            closePrepared(reservation.token, scheduler, task);
            return StartStatus.START_FAILED;
        }
        if (task == null) {
            cancelReservation(reservation);
            closePrepared(reservation.token, scheduler, null);
            return StartStatus.START_FAILED;
        }

        final Scheduler preparedScheduler = scheduler;
        final Task preparedTask = task;
        Generation generation =
                new Generation(
                        reservation.token, preparedScheduler, preparedTask);
        boolean scheduled = false;
        synchronized (this) {
            if (_reservation == reservation
                    && reservation.token.isActive()
                    && _current == null) {
                _reservation = null;
                _current = generation;
                try {
                    preparedScheduler.scheduleAtFixedRate(new Runnable() {
                        @Override
                        public void run() {
                            if (!reservation.token.isActive()
                                    || Thread.currentThread().isInterrupted()) {
                                return;
                            }
                            try {
                                preparedTask.run();
                            } catch (RuntimeException exception) {
                                reservation.token.fail(exception);
                                try {
                                    failureListener.onFailure(
                                            reservation.token, exception);
                                } finally {
                                    preparedScheduler.shutdownNow();
                                }
                            }
                        }
                    }, 0L, 1L, TimeUnit.SECONDS);
                    scheduled = true;
                } catch (RuntimeException exception) {
                    reservation.token.deactivate();
                    _current = null;
                }
            }
        }
        if (!scheduled) {
            cancelReservation(reservation);
            closePrepared(
                    reservation.token, preparedScheduler, preparedTask);
            return StartStatus.START_FAILED;
        }
        return StartStatus.STARTED;
    }

    synchronized long allocateGeneration() {
        return nextGenerationLocked();
    }

    synchronized void restoreGenerationFloor(long generation) {
        if (generation > _nextGeneration) {
            _nextGeneration = generation;
        }
    }

    private long nextGenerationLocked() {
        if (_nextGeneration == Long.MAX_VALUE) {
            throw new IllegalStateException(
                    "Writer generation space is exhausted.");
        }
        return ++_nextGeneration;
    }

    synchronized long generationFor(Object owner, int activityId) {
        if (_current != null
                && _current.token.belongsTo(owner)
                && _current.token.getActivityId() == activityId) {
            return _current.token.getGeneration();
        }
        if (_reservation != null
                && _reservation.token.belongsTo(owner)
                && _reservation.token.getActivityId() == activityId) {
            return _reservation.token.getGeneration();
        }
        return 0L;
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
        Generation generation = null;
        synchronized (this) {
            if (_current != null) {
                if (!matches(
                        _current.token,
                        owner,
                        anyOwner,
                        activityId,
                        generationNumber,
                        exactGeneration)) {
                    return !exactGeneration;
                }
                generation = _current;
                generation.token.deactivate();
            } else if (_reservation != null) {
                if (!matches(
                        _reservation.token,
                        owner,
                        anyOwner,
                        activityId,
                        generationNumber,
                        exactGeneration)) {
                    return !exactGeneration;
                }
                _reservation.token.deactivate();
                _reservation = null;
                return true;
            }
        }
        if (generation == null) {
            return true;
        }
        try {
            generation.scheduler.shutdownNow();
            return true;
        } catch (RuntimeException exception) {
            return false;
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
        boolean alreadyTerminated = false;
        synchronized (this) {
            if (fenceClaim != null && !fenceClaim.claim()) {
                return LifecycleTermination.TERMINATED;
            }
            generation = _current;
            if (generation == null) {
                if (_reservation == null) {
                    return LifecycleTermination.TERMINATED;
                }
                if (!matches(
                        _reservation.token,
                        owner,
                        anyOwner,
                        activityId,
                        generationNumber,
                        exactGeneration)) {
                    return exactGeneration
                            ? LifecycleTermination.TIMED_OUT
                            : LifecycleTermination.TERMINATED;
                }
                _reservation.token.deactivate();
                _reservation = null;
                return LifecycleTermination.TERMINATED;
            }
            if (!matches(
                    generation.token,
                    owner,
                    anyOwner,
                    activityId,
                    generationNumber,
                    exactGeneration)) {
                return exactGeneration
                        ? LifecycleTermination.TIMED_OUT
                        : LifecycleTermination.TERMINATED;
            }
            if (generation.scheduler.isTerminated()) {
                _current = null;
                alreadyTerminated = true;
            } else {
                generation.token.deactivate();
            }
        }
        if (alreadyTerminated) {
            generation.closeResources();
            return generation.token.failed()
                    ? LifecycleTermination.TERMINATED_WITH_FAILURE
                    : LifecycleTermination.TERMINATED;
        }
        try {
            generation.scheduler.shutdownNow();
        } catch (RuntimeException exception) {
            return LifecycleTermination.FAILED;
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

        LifecycleTermination result;
        synchronized (this) {
            if (_current == generation
                    && (terminated || generation.scheduler.isTerminated())) {
                _current = null;
                result = generation.token.failed()
                        ? LifecycleTermination.TERMINATED_WITH_FAILURE
                        : LifecycleTermination.TERMINATED;
            } else if (_current == generation) {
                result = LifecycleTermination.TIMED_OUT;
            } else {
                result = generation.token.failed()
                        ? LifecycleTermination.TERMINATED_WITH_FAILURE
                        : LifecycleTermination.TERMINATED;
            }
        }
        if (result == LifecycleTermination.TERMINATED
                || result
                        == LifecycleTermination.TERMINATED_WITH_FAILURE) {
            generation.closeResources();
        }
        return result;
    }

    private synchronized boolean reservationOwns(Reservation reservation) {
        return _reservation == reservation && reservation.token.isActive();
    }

    private synchronized void cancelReservation(Reservation reservation) {
        reservation.token.deactivate();
        if (_reservation == reservation) {
            _reservation = null;
        }
    }

    private static void closePrepared(
            GenerationToken token, Scheduler scheduler, Task task) {
        token.deactivate();
        if (scheduler != null) {
            try {
                scheduler.shutdownNow();
            } catch (RuntimeException exception) {
                // The unpublished scheduler cannot retain coordinator ownership.
            }
        }
        if (task != null) {
            try {
                task.close();
            } catch (RuntimeException exception) {
                // Construction failed or was fenced; cleanup remains best effort.
            }
        }
    }

    private static boolean matches(
            GenerationToken token,
            Object owner,
            boolean anyOwner,
            int activityId,
            long generationNumber,
            boolean exactGeneration) {
        if (exactGeneration) {
            return token.getActivityId() == activityId
                    && token.getGeneration() == generationNumber;
        }
        return anyOwner || token.belongsTo(owner);
    }
}
