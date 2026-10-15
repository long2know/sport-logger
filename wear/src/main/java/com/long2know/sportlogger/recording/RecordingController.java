package com.long2know.sportlogger.recording;

import com.long2know.utilities.models.SportActivity;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RecordingController {
    public interface Clock {
        long currentTimeMillis();
    }

    public interface Listener {
        void onSnapshot(RecordingSnapshot snapshot);
    }

    public interface RetryPolicy {
        long delayMillis(int attempt);
    }

    private final RecordingJournal journal;
    private final RecordingStore store;
    private final RecordingExporter exporter;
    private final ScheduledExecutorService worker;
    private final Executor callbackExecutor;
    private final Clock clock;
    private final Listener listener;
    private final RetryPolicy retryPolicy;
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile RecordingSnapshot snapshot =
            new RecordingSnapshot(RecordingState.IDLE, 0, 0L, null);
    private PersistentRecording current = PersistentRecording.idle();
    private ScheduledFuture<?> sampleFuture;
    private ScheduledFuture<?> retryFuture;
    private boolean exportInFlight;
    private long exportToken;
    private int retryAttempt;
    private long lease;

    public RecordingController(
            RecordingJournal journal,
            RecordingStore store,
            RecordingExporter exporter,
            ScheduledExecutorService worker,
            Executor callbackExecutor,
            Clock clock,
            Listener listener,
            RetryPolicy retryPolicy) {
        this.journal = Objects.requireNonNull(journal);
        this.store = Objects.requireNonNull(store);
        this.exporter = Objects.requireNonNull(exporter);
        this.worker = Objects.requireNonNull(worker);
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor);
        this.clock = Objects.requireNonNull(clock);
        this.listener = Objects.requireNonNull(listener);
        this.retryPolicy = Objects.requireNonNull(retryPolicy);
    }

    public boolean recover() {
        return submit(this::recoverInternal);
    }

    public boolean start() {
        return submit(this::startInternal);
    }

    public boolean pause() {
        return submit(this::pauseInternal);
    }

    public boolean resume() {
        return submit(this::resumeInternal);
    }

    public boolean stop() {
        return submit(this::stopInternal);
    }

    public boolean discard() {
        return submit(this::discardInternal);
    }

    public RecordingSnapshot getSnapshot() {
        return snapshot;
    }

    public void closeAsync() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cancel(sampleFuture);
        cancel(retryFuture);
        worker.shutdown();
    }

    private boolean submit(Runnable command) {
        if (closed.get()) {
            return false;
        }
        try {
            worker.execute(() -> {
                if (!closed.get() && ensureLease() && journal.isCurrent(lease)) {
                    command.run();
                }
            });
            return true;
        } catch (RejectedExecutionException error) {
            return false;
        }
    }

    private boolean ensureLease() {
        if (lease != 0L) {
            return true;
        }
        try {
            lease = journal.acquireLease();
            return true;
        } catch (Exception error) {
            current = new PersistentRecording(
                    RecordingState.ERROR,
                    0,
                    0L,
                    0L,
                    0L,
                    0L,
                    failure(
                            RecordingError.Code.DURABILITY,
                            "Could not acquire recording ownership",
                            error));
            publish(current);
            return false;
        }
    }

    private void recoverInternal() {
        try {
            current = journal.read();
        } catch (Exception error) {
            current = new PersistentRecording(
                    RecordingState.ERROR,
                    0,
                    0L,
                    0L,
                    0L,
                    0L,
                    failure(RecordingError.Code.RECOVERY, "Recording recovery state is unreadable", error));
            publish(current);
            return;
        }

        publish(current);
        switch (current.getState()) {
            case RECORDING:
                scheduleSamples();
                break;
            case STOPPING:
            case EXPORT_PENDING:
                attemptFinalization();
                break;
            case DISCARDING:
                attemptDiscard();
                break;
            case STARTING:
                current = current.withState(
                        RecordingState.ERROR,
                        0L,
                        current.getAccumulatedMillis(),
                        current.getEndedAtMillis(),
                        new RecordingError(
                                RecordingError.Code.RECOVERY,
                                "Interrupted start requires explicit recovery"));
                publish(current);
                break;
            default:
                break;
        }
    }

    private void startInternal() {
        if (current.getState() != RecordingState.IDLE) {
            reject("Cannot start while recording state is " + current.getState());
            return;
        }

        publish(new RecordingSnapshot(RecordingState.STARTING, 0, 0L, null));
        long now = clock.currentTimeMillis();
        int activityId;
        try {
            activityId = store.createActivity(now);
        } catch (Exception error) {
            publish(new RecordingSnapshot(
                    RecordingState.IDLE,
                    0,
                    0L,
                    failure(RecordingError.Code.STORAGE, "Could not create activity", error)));
            return;
        }

        PersistentRecording started = new PersistentRecording(
                RecordingState.RECORDING,
                activityId,
                now,
                now,
                0L,
                0L,
                null);
        if (!persist(started)) {
            try {
                store.deleteActivity(activityId);
            } catch (Exception rollbackError) {
                publish(started.withError(failure(
                        RecordingError.Code.STORAGE,
                        "Start rollback failed",
                        rollbackError)));
            }
            return;
        }

        current = started;
        retryAttempt = 0;
        scheduleSamples();
        publish(current);
    }

    private void pauseInternal() {
        if (current.getState() != RecordingState.RECORDING) {
            reject("Cannot pause while recording state is " + current.getState());
            return;
        }
        long now = clock.currentTimeMillis();
        long accumulated = current.durationAt(now);
        PersistentRecording paused = current.withState(
                RecordingState.PAUSED,
                0L,
                accumulated,
                0L,
                null);
        if (!persist(paused)) {
            return;
        }
        current = paused;
        cancel(sampleFuture);
        sampleFuture = null;
        publish(current);
    }

    private void resumeInternal() {
        if (current.getState() != RecordingState.PAUSED) {
            reject("Cannot resume while recording state is " + current.getState());
            return;
        }
        PersistentRecording resumed = current.withState(
                RecordingState.RECORDING,
                clock.currentTimeMillis(),
                current.getAccumulatedMillis(),
                0L,
                null);
        if (!persist(resumed)) {
            return;
        }
        current = resumed;
        scheduleSamples();
        publish(current);
    }

    private void stopInternal() {
        RecordingState state = current.getState();
        if (state != RecordingState.RECORDING && state != RecordingState.PAUSED) {
            reject("Cannot stop while recording state is " + state);
            return;
        }
        long now = clock.currentTimeMillis();
        long accumulated = current.durationAt(now);
        PersistentRecording stopping = current.withState(
                RecordingState.STOPPING,
                0L,
                accumulated,
                now,
                null);
        if (!persist(stopping)) {
            return;
        }
        current = stopping;
        cancel(sampleFuture);
        sampleFuture = null;
        retryAttempt = 0;
        publish(current);
        attemptFinalization();
    }

    private void discardInternal() {
        RecordingState state = current.getState();
        if (state != RecordingState.RECORDING && state != RecordingState.PAUSED) {
            reject("Cannot discard while recording state is " + state);
            return;
        }
        long now = clock.currentTimeMillis();
        PersistentRecording discarding = current.withState(
                RecordingState.DISCARDING,
                0L,
                current.durationAt(now),
                now,
                null);
        if (!persist(discarding)) {
            return;
        }
        current = discarding;
        cancel(sampleFuture);
        sampleFuture = null;
        retryAttempt = 0;
        publish(current);
        attemptDiscard();
    }

    private void scheduleSamples() {
        cancel(sampleFuture);
        sampleFuture = worker.scheduleWithFixedDelay(this::writeSample, 0L, 1L, TimeUnit.SECONDS);
    }

    private void writeSample() {
        if (closed.get()
                || !journal.isCurrent(lease)
                || current.getState() != RecordingState.RECORDING) {
            return;
        }
        try {
            store.writeSample(current.getActivityId());
            if (closed.get() || !journal.isCurrent(lease)) {
                return;
            }
            if (current.getError() != null) {
                PersistentRecording recovered = current.withError(null);
                if (persist(recovered)) {
                    current = recovered;
                }
            }
        } catch (Exception error) {
            if (closed.get() || !journal.isCurrent(lease)) {
                return;
            }
            PersistentRecording failed = current.withError(
                    failure(RecordingError.Code.STORAGE, "Sample persistence failed", error));
            if (persist(failed)) {
                current = failed;
            }
        }
        publish(current);
    }

    private void attemptFinalization() {
        if (closed.get() || !journal.isCurrent(lease) || exportInFlight) {
            return;
        }
        try {
            if (current.getState() == RecordingState.STOPPING) {
                store.finishActivity(current.getActivityId(), current.getEndedAtMillis());
                if (closed.get() || !journal.isCurrent(lease)) {
                    return;
                }
                PersistentRecording exportPending = current.withState(
                        RecordingState.EXPORT_PENDING,
                        0L,
                        current.getAccumulatedMillis(),
                        current.getEndedAtMillis(),
                        null);
                if (!persist(exportPending)) {
                    scheduleFinalizationRetry();
                    return;
                }
                current = exportPending;
                publish(current);
            }
            if (current.getState() != RecordingState.EXPORT_PENDING) {
                return;
            }
            SportActivity activity = store.loadActivity(current.getActivityId());
            if (closed.get() || !journal.isCurrent(lease)) {
                return;
            }
            exportInFlight = true;
            long token = ++exportToken;
            try {
                exporter.export(activity, new RecordingExporter.Callback() {
                    @Override
                    public void onSuccess() {
                        handleExportCallback(token, null);
                    }

                    @Override
                    public void onFailure(Throwable error) {
                        handleExportCallback(token, error);
                    }
                });
            } catch (RuntimeException error) {
                exportInFlight = false;
                current = current.withError(
                        failure(RecordingError.Code.EXPORT, "Activity export failed", error));
                persistBestEffort(current);
                publish(current);
                scheduleFinalizationRetry();
            }
        } catch (Exception error) {
            if (closed.get() || !journal.isCurrent(lease)) {
                return;
            }
            current = current.withError(
                    failure(RecordingError.Code.STORAGE, "Activity finalization failed", error));
            persistBestEffort(current);
            publish(current);
            scheduleFinalizationRetry();
        }
    }

    private void handleExportCallback(long token, Throwable error) {
        if (closed.get()) {
            return;
        }
        try {
            worker.execute(() -> {
                if (closed.get()
                        || token != exportToken
                        || !exportInFlight
                        || !journal.isCurrent(lease)
                        || current.getState() != RecordingState.EXPORT_PENDING) {
                    return;
                }
                exportInFlight = false;
                if (error == null) {
                    PersistentRecording idle = PersistentRecording.idle();
                    if (persist(idle)) {
                        current = idle;
                        retryAttempt = 0;
                        publish(current);
                    } else {
                        scheduleFinalizationRetry();
                    }
                } else {
                    current = current.withError(
                            failure(RecordingError.Code.EXPORT, "Activity export failed", error));
                    persistBestEffort(current);
                    publish(current);
                    scheduleFinalizationRetry();
                }
            });
        } catch (RejectedExecutionException ignored) {
            // A replacement service will recover the durable EXPORT_PENDING state.
        }
    }

    private void scheduleFinalizationRetry() {
        if (closed.get() || !journal.isCurrent(lease)) {
            return;
        }
        cancel(retryFuture);
        long delay = Math.max(0L, retryPolicy.delayMillis(++retryAttempt));
        retryFuture = worker.schedule(this::attemptFinalization, delay, TimeUnit.MILLISECONDS);
    }

    private void attemptDiscard() {
        if (closed.get()
                || !journal.isCurrent(lease)
                || current.getState() != RecordingState.DISCARDING) {
            return;
        }
        try {
            store.deleteActivity(current.getActivityId());
            if (closed.get() || !journal.isCurrent(lease)) {
                return;
            }
            PersistentRecording idle = PersistentRecording.idle();
            if (persist(idle)) {
                current = idle;
                retryAttempt = 0;
                publish(current);
            } else {
                scheduleDiscardRetry();
            }
        } catch (Exception error) {
            if (closed.get() || !journal.isCurrent(lease)) {
                return;
            }
            current = current.withError(
                    failure(RecordingError.Code.STORAGE, "Activity discard failed", error));
            persistBestEffort(current);
            publish(current);
            scheduleDiscardRetry();
        }
    }

    private void scheduleDiscardRetry() {
        if (closed.get() || !journal.isCurrent(lease)) {
            return;
        }
        cancel(retryFuture);
        long delay = Math.max(0L, retryPolicy.delayMillis(++retryAttempt));
        retryFuture = worker.schedule(this::attemptDiscard, delay, TimeUnit.MILLISECONDS);
    }

    private boolean persist(PersistentRecording next) {
        try {
            journal.write(lease, next);
            return true;
        } catch (Exception error) {
            RecordingError recordingError =
                    failure(RecordingError.Code.DURABILITY, "Recording state was not persisted", error);
            publish(new RecordingSnapshot(
                    current.getState(),
                    current.getActivityId(),
                    current.durationAt(clock.currentTimeMillis()),
                    recordingError));
            return false;
        }
    }

    private void persistBestEffort(PersistentRecording next) {
        try {
            journal.write(lease, next);
        } catch (Exception error) {
            RecordingError durability =
                    failure(RecordingError.Code.DURABILITY, "Recording error was not persisted", error);
            publish(new RecordingSnapshot(
                    next.getState(),
                    next.getActivityId(),
                    next.durationAt(clock.currentTimeMillis()),
                    durability));
        }
    }

    private void reject(String message) {
        publish(new RecordingSnapshot(
                current.getState(),
                current.getActivityId(),
                current.durationAt(clock.currentTimeMillis()),
                new RecordingError(RecordingError.Code.COMMAND_REJECTED, message)));
    }

    private void publish(PersistentRecording recording) {
        publish(new RecordingSnapshot(
                recording.getState(),
                recording.getActivityId(),
                recording.durationAt(clock.currentTimeMillis()),
                recording.getError()));
    }

    private void publish(RecordingSnapshot next) {
        snapshot = next;
        callbackExecutor.execute(() -> listener.onSnapshot(next));
    }

    private static RecordingError failure(
            RecordingError.Code code,
            String message,
            Throwable error) {
        String detail = error == null ? "" : error.getMessage();
        if (detail == null || detail.trim().isEmpty()) {
            detail = error == null ? "" : error.getClass().getSimpleName();
        }
        return new RecordingError(code, detail.isEmpty() ? message : message + ": " + detail);
    }

    private static void cancel(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }
}
