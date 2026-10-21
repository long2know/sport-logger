package com.long2know.sportlogger.recording;

import com.long2know.utilities.models.SportActivity;
import com.long2know.utilities.contract.proto.ActivityType;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class RecordingController {
    private static final String LEGACY_PAUSED_LATE_SAMPLE_ERROR =
            "Canonical sample persistence failed: "
                    + "Samples can only be appended while recording or ending";

    private enum ExportResult {
        QUEUED,
        ACKNOWLEDGED,
        FAILED
    }

    public interface Clock {
        long currentTimeMillis();

        default long elapsedRealtimeMillis() {
            return currentTimeMillis();
        }

        default int bootCount() {
            return -1;
        }
    }

    public interface Listener {
        void onSnapshot(RecordingSnapshot snapshot);
    }

    public interface RetryPolicy {
        long delayMillis(int attempt);
    }

    private final RecordingJournal journal;
    private final RecordingStore legacyStore;
    private final RecordingStore canonicalStore;
    private final RecordingExporter exporter;
    private final RecordingSessionTerminator sessionTerminator;
    private final RecordingLapRecorder lapRecorder;
    private final RecordingAuthority newRecordingAuthority;
    private final ScheduledExecutorService worker;
    private final Executor callbackExecutor;
    private final Clock clock;
    private final Listener listener;
    private final RetryPolicy retryPolicy;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong callbackGeneration = new AtomicLong();

    private volatile RecordingSnapshot snapshot =
            new RecordingSnapshot(RecordingState.IDLE, 0, 0L, null);
    private PersistentRecording current = PersistentRecording.idle();
    private ScheduledFuture<?> sampleFuture;
    private ScheduledFuture<?> retryFuture;
    private boolean exportInFlight;
    private boolean terminationInFlight;
    private boolean lapInFlight;
    private long lapToken;
    private long exportToken;
    private long terminationToken;
    private int endedSessionActivityId;
    private int finalSampleActivityId;
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
        this(
                journal,
                store,
                store,
                exporter,
                RecordingSessionTerminator.NO_OP,
                RecordingLapRecorder.UNAVAILABLE,
                RecordingAuthority.LEGACY_COMPATIBILITY,
                worker,
                callbackExecutor,
                clock,
                listener,
                retryPolicy);
    }

    public RecordingController(
            RecordingJournal journal,
            RecordingStore store,
            RecordingExporter exporter,
            RecordingSessionTerminator sessionTerminator,
            ScheduledExecutorService worker,
            Executor callbackExecutor,
            Clock clock,
            Listener listener,
            RetryPolicy retryPolicy) {
        this(
                journal,
                store,
                store,
                exporter,
                sessionTerminator,
                RecordingLapRecorder.UNAVAILABLE,
                RecordingAuthority.LEGACY_COMPATIBILITY,
                worker,
                callbackExecutor,
                clock,
                listener,
                retryPolicy);
    }

    public RecordingController(
            RecordingJournal journal,
            RecordingStore legacyStore,
            RecordingStore canonicalStore,
            RecordingExporter exporter,
            RecordingSessionTerminator sessionTerminator,
            RecordingAuthority newRecordingAuthority,
            ScheduledExecutorService worker,
            Executor callbackExecutor,
            Clock clock,
            Listener listener,
            RetryPolicy retryPolicy) {
        this(
                journal,
                legacyStore,
                canonicalStore,
                exporter,
                sessionTerminator,
                RecordingLapRecorder.UNAVAILABLE,
                newRecordingAuthority,
                worker,
                callbackExecutor,
                clock,
                listener,
                retryPolicy);
    }

    public RecordingController(
            RecordingJournal journal,
            RecordingStore legacyStore,
            RecordingStore canonicalStore,
            RecordingExporter exporter,
            RecordingSessionTerminator sessionTerminator,
            RecordingLapRecorder lapRecorder,
            RecordingAuthority newRecordingAuthority,
            ScheduledExecutorService worker,
            Executor callbackExecutor,
            Clock clock,
            Listener listener,
            RetryPolicy retryPolicy) {
        this.journal = Objects.requireNonNull(journal);
        this.legacyStore = Objects.requireNonNull(legacyStore);
        this.canonicalStore = Objects.requireNonNull(canonicalStore);
        this.exporter = Objects.requireNonNull(exporter);
        this.sessionTerminator = Objects.requireNonNull(sessionTerminator);
        this.lapRecorder = Objects.requireNonNull(lapRecorder);
        this.newRecordingAuthority = Objects.requireNonNull(newRecordingAuthority);
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
        return start(ActivityType.ACTIVITY_TYPE_RUNNING);
    }

    public boolean start(ActivityType activityType) {
        ActivityType supported =
                RecordingActivityTypePolicy.requireSupported(activityType);
        return submit(() -> startInternal(supported));
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

    public boolean interrupt(RecordingError error) {
        Objects.requireNonNull(error);
        return submit(() -> interruptInternal(error));
    }

    public CompletableFuture<ManualLapResult> recordManualLap() {
        CompletableFuture<ManualLapResult> result = new CompletableFuture<>();
        if (closed.get()) {
            result.complete(ManualLapResult.rejected(
                    ManualLapResult.Status.FAILED,
                    snapshot,
                    "Recording controller is closed"));
            return result;
        }
        try {
            worker.execute(() -> {
                if (closed.get() || !ensureLease() || !journal.isCurrent(lease)) {
                    result.complete(ManualLapResult.rejected(
                            ManualLapResult.Status.FAILED,
                            snapshot,
                            "Recording ownership is unavailable"));
                    return;
                }
                recordManualLapInternal(result);
            });
        } catch (RejectedExecutionException error) {
            result.complete(ManualLapResult.rejected(
                    ManualLapResult.Status.FAILED,
                    snapshot,
                    "Recording worker is unavailable"));
        }
        return result;
    }

    public boolean restoreLapState(
            int activityId,
            RecordingLapRecorder.LapState lapState) {
        Objects.requireNonNull(lapState);
        return submit(() -> restoreLapStateInternal(activityId, lapState));
    }

    public RecordingSnapshot getSnapshot() {
        return snapshot;
    }

    public void closeAsync() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        callbackGeneration.incrementAndGet();
        cancel(sampleFuture);
        cancel(retryFuture);
        terminationToken++;
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

        if (isLegacyPausedLateSampleError(current.getError())) {
            PersistentRecording recovered = current.withError(null);
            if (!persist(recovered)) {
                return;
            }
            current = recovered;
        }

        if ((current.getState() == RecordingState.RECORDING
                || current.getState() == RecordingState.PAUSED)
                && current.isBootInterrupted(
                clock.elapsedRealtimeMillis(),
                clock.bootCount())) {
            PersistentRecording interrupted = current.withState(
                    RecordingState.INTERRUPTED,
                    0L,
                    0L,
                    0L,
                    current.getAccumulatedMillis(),
                    current.getAccumulatedElapsedMillis(),
                    0L,
                    -1,
                    new RecordingError(
                            RecordingError.Code.BOOT_INTERRUPTION,
                            "Recording was interrupted by a device restart"));
            if (persist(interrupted)) {
                current = interrupted;
                publish(current);
            }
            return;
        }

        switch (current.getState()) {
            case RECORDING:
            case PAUSED:
                if (current.getElapsedSinceElapsedRealtimeMillis() == 0L
                        || (current.getState() == RecordingState.RECORDING
                        && current.getActiveSinceElapsedRealtimeMillis() == 0L)) {
                    PersistentRecording checkpoint = current.checkpoint(
                            clock.currentTimeMillis(),
                            clock.elapsedRealtimeMillis(),
                            clock.bootCount());
                    if (!persist(checkpoint)) {
                        return;
                    }
                    current = checkpoint;
                }
                publish(current);
                if (current.getState() == RecordingState.RECORDING) {
                    scheduleSamples();
                }
                break;
            case STOPPING:
            case EXPORT_PENDING:
            case EXPORT_QUEUED:
                publish(current);
                attemptFinalization();
                break;
            case DISCARDING:
                publish(current);
                attemptDiscard();
                break;
            case STARTING:
                current = current.withState(
                        RecordingState.ERROR,
                        0L,
                        0L,
                        0L,
                        current.getAccumulatedMillis(),
                        current.getAccumulatedElapsedMillis(),
                        current.getEndedAtMillis(),
                        -1,
                        new RecordingError(
                                RecordingError.Code.RECOVERY,
                                "Interrupted start requires explicit recovery"));
                persistBestEffort(current);
                publish(current);
                break;
            default:
                publish(current);
                break;
        }
    }

    private void startInternal(ActivityType activityType) {
        RecordingState previousState = current.getState();
        boolean replacingQueued = previousState == RecordingState.EXPORT_QUEUED;
        if (previousState != RecordingState.IDLE && !replacingQueued) {
            reject("Cannot start while recording state is " + current.getState());
            return;
        }

        if (replacingQueued) {
            cancel(retryFuture);
            retryFuture = null;
            exportInFlight = false;
            exportToken++;
        }
        terminationInFlight = false;
        lapInFlight = false;
        lapToken++;
        terminationToken++;
        endedSessionActivityId = 0;
        finalSampleActivityId = 0;
        publish(new RecordingSnapshot(
                RecordingState.STARTING,
                0,
                activityType,
                0L,
                0L,
                0L,
                0L,
                0L,
                -1,
                null));
        long now = clock.currentTimeMillis();
        RecordingAuthority authority = newRecordingAuthority;
        RecordingStore startStore = storeFor(authority);
        int activityId;
        try {
            activityId = startStore.createActivity(now);
        } catch (Exception error) {
            publish(new RecordingSnapshot(
                    current.getState(),
                    current.getActivityId(),
                    durationAtCurrentTime(),
                    failure(RecordingError.Code.STORAGE, "Could not create activity", error)));
            if (replacingQueued) {
                scheduleFinalizationRetry();
            }
            return;
        }

        long elapsedNow = clock.elapsedRealtimeMillis();
        PersistentRecording started = new PersistentRecording(
                RecordingState.RECORDING,
                activityId,
                activityType,
                authority,
                now,
                now,
                elapsedNow,
                elapsedNow,
                0L,
                0L,
                0L,
                clock.bootCount(),
                null);
        if (!persist(started)) {
            try {
                startStore.deleteActivity(activityId);
            } catch (Exception rollbackError) {
                publish(new RecordingSnapshot(
                        current.getState(),
                        current.getActivityId(),
                        durationAtCurrentTime(),
                        failure(
                                RecordingError.Code.STORAGE,
                                "Start rollback failed",
                                rollbackError)));
            }
            if (replacingQueued) {
                scheduleFinalizationRetry();
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
        long wallNow = clock.currentTimeMillis();
        long elapsedNow = clock.elapsedRealtimeMillis();
        long accumulated = current.durationAt(wallNow, elapsedNow, clock.bootCount());
        long accumulatedElapsed =
                current.elapsedDurationAt(wallNow, elapsedNow, clock.bootCount());
        PersistentRecording paused = current.withState(
                RecordingState.PAUSED,
                0L,
                0L,
                elapsedNow,
                accumulated,
                accumulatedElapsed,
                0L,
                clock.bootCount(),
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
        if (current.getState() != RecordingState.PAUSED
                && current.getState() != RecordingState.INTERRUPTED) {
            reject("Cannot resume while recording state is " + current.getState());
            return;
        }
        long wallNow = clock.currentTimeMillis();
        long elapsedNow = clock.elapsedRealtimeMillis();
        long accumulatedElapsed = current.elapsedDurationAt(
                wallNow,
                elapsedNow,
                clock.bootCount());
        PersistentRecording resumed = current.withState(
                RecordingState.RECORDING,
                wallNow,
                elapsedNow,
                elapsedNow,
                current.getAccumulatedMillis(),
                accumulatedElapsed,
                0L,
                clock.bootCount(),
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
        if (state != RecordingState.RECORDING
                && state != RecordingState.PAUSED
                && state != RecordingState.INTERRUPTED) {
            reject("Cannot stop while recording state is " + state);
            return;
        }
        long wallNow = clock.currentTimeMillis();
        long elapsedNow = clock.elapsedRealtimeMillis();
        long accumulated = current.durationAt(wallNow, elapsedNow, clock.bootCount());
        long accumulatedElapsed =
                current.elapsedDurationAt(wallNow, elapsedNow, clock.bootCount());
        PersistentRecording stopping = current.withState(
                RecordingState.STOPPING,
                0L,
                0L,
                0L,
                accumulated,
                accumulatedElapsed,
                endTimestamp(accumulatedElapsed),
                -1,
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
        if (state != RecordingState.RECORDING
                && state != RecordingState.PAUSED
                && state != RecordingState.INTERRUPTED) {
            reject("Cannot discard while recording state is " + state);
            return;
        }
        long wallNow = clock.currentTimeMillis();
        long elapsedNow = clock.elapsedRealtimeMillis();
        long accumulated = current.durationAt(wallNow, elapsedNow, clock.bootCount());
        long accumulatedElapsed =
                current.elapsedDurationAt(wallNow, elapsedNow, clock.bootCount());
        PersistentRecording discarding = current.withState(
                RecordingState.DISCARDING,
                0L,
                0L,
                0L,
                accumulated,
                accumulatedElapsed,
                endTimestamp(accumulatedElapsed),
                -1,
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

    private void interruptInternal(RecordingError error) {
        RecordingState state = current.getState();
        if (state != RecordingState.RECORDING && state != RecordingState.PAUSED) {
            return;
        }
        long wallNow = clock.currentTimeMillis();
        long elapsedNow = clock.elapsedRealtimeMillis();
        PersistentRecording interrupted = current.withState(
                RecordingState.INTERRUPTED,
                0L,
                0L,
                0L,
                current.durationAt(wallNow, elapsedNow, clock.bootCount()),
                current.elapsedDurationAt(wallNow, elapsedNow, clock.bootCount()),
                0L,
                -1,
                error);
        if (!persist(interrupted)) {
            return;
        }
        current = interrupted;
        cancel(sampleFuture);
        sampleFuture = null;
        publish(current);
    }

    private void recordManualLapInternal(
            CompletableFuture<ManualLapResult> result) {
        if (current.getState() != RecordingState.RECORDING) {
            result.complete(ManualLapResult.rejected(
                    ManualLapResult.Status.REJECTED_NOT_RECORDING,
                    snapshot(current, current.getError()),
                    "Manual laps require an active recording"));
            return;
        }
        if (current.getAuthority() != RecordingAuthority.CANONICAL) {
            result.complete(ManualLapResult.rejected(
                    ManualLapResult.Status.REJECTED_LEGACY_AUTHORITY,
                    snapshot(current, current.getError()),
                    "Manual laps require canonical recording authority"));
            return;
        }
        if (lapInFlight) {
            result.complete(ManualLapResult.rejected(
                    ManualLapResult.Status.REJECTED_BUSY,
                    snapshot(current, current.getError()),
                    "A manual lap is already being persisted"));
            return;
        }
        long wallNow = clock.currentTimeMillis();
        long elapsedNow = clock.elapsedRealtimeMillis();
        long activeDuration =
                current.durationAt(wallNow, elapsedNow, clock.bootCount());
        long elapsedDuration =
                current.elapsedDurationAt(wallNow, elapsedNow, clock.bootCount());
        RecordingLapRecorder.Boundary boundary =
                new RecordingLapRecorder.Boundary(
                        current.getActivityId(),
                        endTimestamp(elapsedDuration),
                        elapsedDuration,
                        activeDuration);
        lapInFlight = true;
        long operationToken = ++lapToken;
        int activityId = current.getActivityId();
        try {
            lapRecorder.recordManualLap(
                    boundary,
                    new RecordingLapRecorder.Callback() {
                        @Override
                        public void onPersisted(RecordingLapRecorder.LapState lapState) {
                            handleLapCallback(
                                    activityId,
                                    operationToken,
                                    lapState,
                                    null,
                                    result);
                        }

                        @Override
                        public void onFailure(Throwable error) {
                            handleLapCallback(
                                    activityId,
                                    operationToken,
                                    null,
                                    error,
                                    result);
                        }
                    });
        } catch (RuntimeException error) {
            handleLapCallback(
                    activityId,
                    operationToken,
                    null,
                    error,
                    result);
        }
    }

    private void handleLapCallback(
            int activityId,
            long operationToken,
            RecordingLapRecorder.LapState lapState,
            Throwable error,
            CompletableFuture<ManualLapResult> result) {
        try {
            worker.execute(() -> {
                if (operationToken != lapToken || !lapInFlight) {
                    result.complete(ManualLapResult.rejected(
                            ManualLapResult.Status.FAILED,
                            snapshot,
                            "Manual lap callback is stale"));
                    return;
                }
                lapInFlight = false;
                if (closed.get()
                        || current.getActivityId() != activityId
                        || !journal.isCurrent(lease)) {
                    result.complete(ManualLapResult.rejected(
                            ManualLapResult.Status.FAILED,
                            snapshot,
                            "Manual lap callback is stale"));
                    return;
                }
                if (error != null) {
                    result.complete(ManualLapResult.rejected(
                            ManualLapResult.Status.FAILED,
                            snapshot(current, current.getError()),
                            message(error)));
                    return;
                }
                PersistentRecording updated = current.withLapState(
                        lapState.getCurrentLapIndex(),
                        lapState.getLastLapElapsedDurationMillis(),
                        lapState.getLastLapActiveDurationMillis());
                if (!persist(updated)) {
                    result.complete(ManualLapResult.rejected(
                            ManualLapResult.Status.FAILED,
                            snapshot,
                            "Manual lap state was not persisted"));
                    return;
                }
                current = updated;
                publish(current);
                result.complete(ManualLapResult.accepted(
                        snapshot(current, current.getError())));
            });
        } catch (RejectedExecutionException rejected) {
            result.complete(ManualLapResult.rejected(
                    ManualLapResult.Status.FAILED,
                    snapshot,
                    "Recording worker is unavailable"));
        }
    }

    private void restoreLapStateInternal(
            int activityId,
            RecordingLapRecorder.LapState lapState) {
        if (current.getActivityId() != activityId
                || current.getAuthority() != RecordingAuthority.CANONICAL
                || current.getState() == RecordingState.IDLE) {
            return;
        }
        PersistentRecording restored = current.withLapState(
                lapState.getCurrentLapIndex(),
                lapState.getLastLapElapsedDurationMillis(),
                lapState.getLastLapActiveDurationMillis());
        if (persist(restored)) {
            current = restored;
            publish(current);
        }
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
            storeFor(current.getAuthority()).writeSample(current.getActivityId());
            if (closed.get() || !journal.isCurrent(lease)) {
                return;
            }
            PersistentRecording checkpoint = current.checkpoint(
                    clock.currentTimeMillis(),
                    clock.elapsedRealtimeMillis(),
                    clock.bootCount()).withError(null);
            if (!persist(checkpoint)) {
                return;
            }
            current = checkpoint;
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
            RecordingStore recordingStore = storeFor(current.getAuthority());
            if (current.getState() == RecordingState.STOPPING) {
                if (endedSessionActivityId != current.getActivityId()) {
                    requestSessionEnd(true);
                    return;
                }
                if (finalSampleActivityId != current.getActivityId()) {
                    recordingStore.writeSample(current.getActivityId());
                    finalSampleActivityId = current.getActivityId();
                }
                recordingStore.finishActivity(
                        current.getActivityId(),
                        current.getEndedAtMillis());
                if (closed.get() || !journal.isCurrent(lease)) {
                    return;
                }
                RecordingState completedState =
                        current.getAuthority() == RecordingAuthority.CANONICAL
                                ? RecordingState.EXPORT_QUEUED
                                : RecordingState.EXPORT_PENDING;
                PersistentRecording exportPending = current.withState(
                        completedState,
                        0L,
                        0L,
                        0L,
                        current.getAccumulatedMillis(),
                        current.getAccumulatedElapsedMillis(),
                        current.getEndedAtMillis(),
                        -1,
                        null);
                if (!persist(exportPending)) {
                    scheduleFinalizationRetry();
                    return;
                }
                current = exportPending;
                publish(current);
            }
            if (current.getAuthority() == RecordingAuthority.CANONICAL) {
                if (current.getState() == RecordingState.EXPORT_PENDING) {
                    PersistentRecording queued = current.withState(
                            RecordingState.EXPORT_QUEUED,
                            0L,
                            0L,
                            0L,
                            current.getAccumulatedMillis(),
                            current.getAccumulatedElapsedMillis(),
                            current.getEndedAtMillis(),
                            -1,
                            current.getError());
                    if (persist(queued)) {
                        current = queued;
                        publish(current);
                    } else {
                        scheduleFinalizationRetry();
                    }
                }
                return;
            }
            if (current.getState() != RecordingState.EXPORT_PENDING
                    && current.getState() != RecordingState.EXPORT_QUEUED) {
                return;
            }
            SportActivity activity = recordingStore.loadActivity(current.getActivityId());
            if (closed.get() || !journal.isCurrent(lease)) {
                return;
            }
            exportInFlight = true;
            long token = ++exportToken;
            try {
                exporter.export(activity, new RecordingExporter.Callback() {
                    @Override
                    public void onQueued() {
                        handleExportCallback(token, ExportResult.QUEUED, null);
                    }

                    @Override
                    public void onAcknowledged() {
                        handleExportCallback(token, ExportResult.ACKNOWLEDGED, null);
                    }

                    @Override
                    public void onFailure(Throwable error) {
                        handleExportCallback(token, ExportResult.FAILED, error);
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

    private void handleExportCallback(
            long token,
            ExportResult result,
            Throwable error) {
        if (closed.get()) {
            return;
        }
        try {
            worker.execute(() -> {
                if (closed.get()
                        || token != exportToken
                        || !exportInFlight
                        || !journal.isCurrent(lease)
                        || (current.getState() != RecordingState.EXPORT_PENDING
                        && current.getState() != RecordingState.EXPORT_QUEUED)) {
                    return;
                }
                exportInFlight = false;
                if (result == ExportResult.ACKNOWLEDGED) {
                    PersistentRecording idle = PersistentRecording.idle();
                    if (persist(idle)) {
                        current = idle;
                        retryAttempt = 0;
                        publish(current);
                    } else {
                        scheduleFinalizationRetry();
                    }
                } else if (result == ExportResult.QUEUED) {
                    PersistentRecording queued = current.withState(
                            RecordingState.EXPORT_QUEUED,
                            0L,
                            0L,
                            0L,
                            current.getAccumulatedMillis(),
                            current.getAccumulatedElapsedMillis(),
                            current.getEndedAtMillis(),
                            -1,
                            null);
                    if (persist(queued)) {
                        current = queued;
                        publish(current);
                    }
                    scheduleFinalizationRetry();
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
            RecordingStore recordingStore = storeFor(current.getAuthority());
            if (endedSessionActivityId != current.getActivityId()) {
                requestSessionEnd(false);
                return;
            }
            recordingStore.deleteActivity(current.getActivityId());
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

    private void requestSessionEnd(boolean preserveFinalData) {
        if (terminationInFlight
                || closed.get()
                || !journal.isCurrent(lease)) {
            return;
        }
        terminationInFlight = true;
        long token = ++terminationToken;
        int activityId = current.getActivityId();
        try {
            sessionTerminator.endSession(
                    activityId,
                    preserveFinalData,
                    snapshot(current, current.getError()),
                    new RecordingSessionTerminator.Callback() {
                        @Override
                        public void onComplete() {
                            handleSessionEndCallback(token, activityId, null);
                        }

                        @Override
                        public void onFailure(Throwable error) {
                            handleSessionEndCallback(token, activityId, error);
                        }
                    });
        } catch (RuntimeException error) {
            handleSessionEndCallback(token, activityId, error);
        }
    }

    private void handleSessionEndCallback(
            long token,
            int activityId,
            Throwable error) {
        if (closed.get()) {
            return;
        }
        try {
            worker.execute(() -> {
                if (closed.get()
                        || token != terminationToken
                        || !terminationInFlight
                        || !journal.isCurrent(lease)
                        || current.getActivityId() != activityId) {
                    return;
                }
                terminationInFlight = false;
                if (error != null) {
                    current = current.withError(failure(
                            RecordingError.Code.ACQUISITION,
                            "Exercise acquisition could not be ended",
                            error));
                    persistBestEffort(current);
                    publish(current);
                    if (current.getState() == RecordingState.DISCARDING) {
                        scheduleDiscardRetry();
                    } else {
                        scheduleFinalizationRetry();
                    }
                    return;
                }
                endedSessionActivityId = activityId;
                if (current.getError() != null
                        && current.getError().getCode() == RecordingError.Code.ACQUISITION) {
                    PersistentRecording recovered = current.withError(null);
                    if (persist(recovered)) {
                        current = recovered;
                        publish(current);
                    }
                }
                if (current.getState() == RecordingState.DISCARDING) {
                    attemptDiscard();
                } else {
                    attemptFinalization();
                }
            });
        } catch (RejectedExecutionException ignored) {
            // A replacement controller will retry the durable terminal state.
        }
    }

    private boolean persist(PersistentRecording next) {
        try {
            journal.write(lease, next);
            return true;
        } catch (Exception error) {
            RecordingError recordingError =
                    failure(RecordingError.Code.DURABILITY, "Recording state was not persisted", error);
            publish(snapshot(current, recordingError));
            return false;
        }
    }

    private void persistBestEffort(PersistentRecording next) {
        try {
            journal.write(lease, next);
        } catch (Exception error) {
            RecordingError durability =
                    failure(RecordingError.Code.DURABILITY, "Recording error was not persisted", error);
            publish(snapshot(next, durability));
        }
    }

    private void reject(String message) {
        publish(snapshot(
                current,
                new RecordingError(RecordingError.Code.COMMAND_REJECTED, message)));
    }

    private void publish(PersistentRecording recording) {
        publish(snapshot(recording, recording.getError()));
    }

    private void publish(RecordingSnapshot next) {
        if (closed.get()) {
            return;
        }
        long generation = callbackGeneration.get();
        Runnable stage = () -> {
            if (closed.get() || callbackGeneration.get() != generation) {
                return;
            }
            snapshot = next;
            callbackExecutor.execute(() -> {
                if (closed.get() || callbackGeneration.get() != generation) {
                    return;
                }
                if (lease == 0L) {
                    listener.onSnapshot(next);
                } else {
                    journal.runIfCurrent(lease, () -> {
                        if (!closed.get() && callbackGeneration.get() == generation) {
                            listener.onSnapshot(next);
                        }
                    });
                }
            });
        };
        if (lease == 0L) {
            stage.run();
        } else {
            journal.runIfCurrent(lease, stage);
        }
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

    private static String message(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error == null ? "Unknown error" : error.getClass().getSimpleName()
                : message;
    }

    private static boolean isLegacyPausedLateSampleError(
            RecordingError error) {
        return error != null
                && error.getCode() == RecordingError.Code.STORAGE
                && LEGACY_PAUSED_LATE_SAMPLE_ERROR.equals(error.getMessage());
    }

    private static void cancel(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    private long durationAtCurrentTime() {
        return current.durationAt(
                clock.currentTimeMillis(),
                clock.elapsedRealtimeMillis(),
                clock.bootCount());
    }

    private RecordingSnapshot snapshot(
            PersistentRecording recording,
            RecordingError error) {
        long wallNow = clock.currentTimeMillis();
        long elapsedNow = clock.elapsedRealtimeMillis();
        int currentBootCount = clock.bootCount();
        return new RecordingSnapshot(
                recording.getState(),
                recording.getActivityId(),
                recording.getActivityType(),
                recording.getAuthority(),
                recording.getStartedAtMillis(),
                recording.durationAt(wallNow, elapsedNow, currentBootCount),
                recording.elapsedDurationAt(wallNow, elapsedNow, currentBootCount),
                recording.getActiveSinceElapsedRealtimeMillis(),
                recording.getElapsedSinceElapsedRealtimeMillis(),
                recording.getBootCount(),
                error,
                recording.getCurrentLapIndex(),
                recording.getLastLapElapsedDurationMillis(),
                recording.getLastLapActiveDurationMillis());
    }

    private RecordingStore storeFor(RecordingAuthority authority) {
        return authority == RecordingAuthority.CANONICAL
                ? canonicalStore
                : legacyStore;
    }

    private long endTimestamp(long elapsedDurationMillis) {
        long startedAt = current.getStartedAtMillis();
        if (elapsedDurationMillis > 0L
                && startedAt > Long.MAX_VALUE - elapsedDurationMillis) {
            return Long.MAX_VALUE;
        }
        return startedAt + elapsedDurationMillis;
    }
}
