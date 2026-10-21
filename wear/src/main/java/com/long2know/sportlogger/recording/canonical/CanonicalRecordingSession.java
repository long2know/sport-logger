package com.long2know.sportlogger.recording.canonical;

import android.content.Context;
import android.os.SystemClock;

import com.long2know.sportlogger.acquisition.ExerciseMetric;
import com.long2know.sportlogger.acquisition.MeasurementCapability;
import com.long2know.sportlogger.acquisition.MeasurementSample;
import com.long2know.sportlogger.persistence.watch.WatchActivityRepository;
import com.long2know.sportlogger.recording.RecordingError;
import com.long2know.sportlogger.recording.RecordingSessionTerminator;
import com.long2know.sportlogger.recording.RecordingSnapshot;
import com.long2know.sportlogger.recording.RecordingState;
import com.long2know.sportlogger.sync.WatchSyncFinishHook;
import com.long2know.utilities.contract.CanonicalIds;
import com.long2know.utilities.contract.CanonicalTransfers;
import com.long2know.utilities.contract.proto.ActiveInterval;
import com.long2know.utilities.contract.proto.ActivityRecord;
import com.long2know.utilities.contract.proto.ActivitySummary;
import com.long2know.utilities.contract.proto.DoubleMeasurement;
import com.long2know.utilities.contract.proto.LongMeasurement;
import com.long2know.utilities.contract.proto.MeasurementStatus;
import com.long2know.utilities.contract.proto.SourceKind;
import com.long2know.utilities.contract.proto.SourceMetadata;
import com.long2know.utilities.contract.proto.TombstoneOrigin;
import com.long2know.utilities.contract.proto.TombstoneRecord;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;

public final class CanonicalRecordingSession implements AutoCloseable {
    public interface Listener {
        void onFatalError(String message, Throwable error);
    }

    private final Context context;
    private final WatchActivityRepository repository;
    private final CanonicalSessionJournal journal;
    private final CanonicalMeasurementMapper measurementMapper =
            new CanonicalMeasurementMapper();
    private final Listener listener;

    private volatile Map<ExerciseMetric, MeasurementCapability> capabilities =
            Collections.emptyMap();
    private volatile RecordingSnapshot latestSnapshot =
            new RecordingSnapshot(RecordingState.IDLE, 0, 0L, null);
    private String submittedSessionId;
    private String transitionedSessionId;
    private RecordingState transitionedState;
    private boolean acceptingSamples;
    private boolean closed;

    public CanonicalRecordingSession(Context context, Listener listener) {
        this(
                context,
                WatchActivityRepository.open(context),
                new CanonicalSessionJournal(context),
                listener);
    }

    CanonicalRecordingSession(
            Context context,
            WatchActivityRepository repository,
            CanonicalSessionJournal journal,
            Listener listener) {
        this.context = context.getApplicationContext();
        this.repository = Objects.requireNonNull(repository);
        this.journal = Objects.requireNonNull(journal);
        this.listener = Objects.requireNonNull(listener);
    }

    public void updateCapabilities(
            Map<ExerciseMetric, MeasurementCapability> values) {
        EnumMap<ExerciseMetric, MeasurementCapability> copy =
                new EnumMap<>(ExerciseMetric.class);
        copy.putAll(Objects.requireNonNull(values));
        capabilities = Collections.unmodifiableMap(copy);
    }

    public synchronized void onRecordingSnapshot(RecordingSnapshot snapshot) {
        if (closed) {
            return;
        }
        latestSnapshot = Objects.requireNonNull(snapshot);
        RecordingState state = snapshot.getState();
        if (snapshot.getActivityId() > 0
                && (state == RecordingState.RECORDING
                || state == RecordingState.PAUSED
                || state == RecordingState.INTERRUPTED
                || state == RecordingState.STOPPING
                || state == RecordingState.DISCARDING)) {
            try {
                CanonicalSessionJournal.State session =
                        journal.openOrCreate(snapshot, bootId(snapshot.getBootCount()));
                ensureStarted(session);
                session = journal.recordSnapshot(session.sessionId, snapshot);
                if (state == RecordingState.RECORDING) {
                    acceptingSamples = true;
                }
                submitTransitionIfNeeded(session, snapshot);
            } catch (RuntimeException error) {
                fatal("Canonical recording state could not be persisted", error);
            }
        } else if (state == RecordingState.EXPORT_PENDING
                || state == RecordingState.EXPORT_QUEUED
                || state == RecordingState.IDLE) {
            acceptingSamples = false;
            CanonicalSessionJournal.State session = journal.read();
            if (session != null && session.terminal) {
                try {
                    journal.clearIfTerminal(session.activityId);
                } catch (RuntimeException error) {
                    fatal("Canonical terminal state could not be cleared", error);
                    return;
                }
                submittedSessionId = null;
                transitionedSessionId = null;
                transitionedState = null;
            }
        }
    }

    public synchronized void onAcceptedSample(MeasurementSample<?> sample) {
        if (closed || !acceptingSamples || !measurementMapper.supports(sample.getMetric())) {
            return;
        }
        CanonicalSessionJournal.State session = journal.read();
        RecordingSnapshot snapshot = latestSnapshot;
        if (session == null
                || session.terminal
                || snapshot.getActivityId() != session.activityId) {
            return;
        }
        try {
            long sequence = journal.nextSequence(session.sessionId);
            long monotonicMs = sample.getSequenceNanos() / 1_000_000L;
            long nowElapsed = SystemClock.elapsedRealtime();
            long nowWall = System.currentTimeMillis();
            long derivedTimestamp = monotonicMs > 0L && monotonicMs <= nowElapsed
                    ? nowWall - (nowElapsed - monotonicMs)
                    : nowWall;
            long timestamp = sample.getTimestampUtcMillis() == null
                    ? derivedTimestamp
                    : sample.getTimestampUtcMillis();
            CanonicalMeasurementMapper.Timing timing =
                    new CanonicalMeasurementMapper.Timing(
                            sequence,
                            timestamp,
                            monotonicMs > 0L ? monotonicMs : null,
                            session.bootId,
                            snapshot.getElapsedDurationMillis(),
                            sample.getActiveDurationMillis() == null
                                    ? snapshot.getDurationMillis()
                                    : sample.getActiveDurationMillis());
            com.long2know.utilities.contract.proto.MeasurementSample canonical =
                    measurementMapper.map(
                            sample,
                            capabilities.get(sample.getMetric()),
                            timing);
            repository.writeSample(
                            session.sessionId,
                            session.leaseToken,
                            canonical)
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            fatal("Canonical sample persistence failed", unwrap(error));
                        }
                    });
        } catch (RuntimeException error) {
            fatal("Canonical sample persistence failed", error);
        }
    }

    public synchronized void reserveCompletion(
            RecordingSnapshot finalSnapshot,
            RecordingSessionTerminator.Callback callback) {
        Objects.requireNonNull(callback);
        if (closed) {
            callback.onFailure(new IllegalStateException(
                    "Canonical recording session is closed"));
            return;
        }
        latestSnapshot = Objects.requireNonNull(finalSnapshot);
        CanonicalSessionJournal.State session;
        try {
            session = journal.openOrCreate(
                    finalSnapshot,
                    bootId(finalSnapshot.getBootCount()));
            ensureStarted(session);
            session = journal.recordSnapshot(session.sessionId, finalSnapshot);
        } catch (RuntimeException error) {
            callback.onFailure(error);
            return;
        }
        CanonicalSessionJournal.State durableSession = session;
        repository.session(session.sessionId)
                .whenComplete((repositorySession, sessionError) -> {
                    if (sessionError != null) {
                        callback.onFailure(unwrap(sessionError));
                        return;
                    }
                    if (repositorySession.getState()
                            == WatchActivityRepository.LifecycleState.COMPLETED) {
                        markTerminalBestEffort(durableSession.sessionId);
                        WatchSyncFinishHook.requestRetry(context);
                        callback.onComplete();
                        return;
                    }
                    if (repositorySession.getState()
                            == WatchActivityRepository.LifecycleState.DISCARDED) {
                        callback.onFailure(new IllegalStateException(
                                "Discarded canonical session cannot be completed"));
                        return;
                    }
                    long revision = repositorySession.getCompletionRevision() != null
                            ? repositorySession.getCompletionRevision()
                            : durableSession.completionRevision != null
                            ? durableSession.completionRevision
                            : Math.max(1L, repositorySession.getWatchRevision() + 1L);
                    String transferId =
                            repositorySession.getCompletionTransferId() != null
                                    ? repositorySession.getCompletionTransferId()
                                    : durableSession.completionTransferId != null
                                    ? durableSession.completionTransferId
                                    : CanonicalIds.newTransferId();
                    CanonicalSessionJournal.State reserved;
                    try {
                        reserved = journal.reserveCompletion(
                                durableSession.sessionId,
                                revision,
                                transferId);
                    } catch (RuntimeException error) {
                        callback.onFailure(error);
                        return;
                    }
                    if (repositorySession.getState()
                            == WatchActivityRepository.LifecycleState.ENDING
                            && transferId.equals(
                            repositorySession.getCompletionTransferId())
                            && Long.valueOf(revision).equals(
                            repositorySession.getCompletionRevision())) {
                        callback.onComplete();
                        return;
                    }
                    repository.reserveCompletion(
                                    reserved.sessionId,
                                    reserved.leaseToken,
                                    revision,
                                    transferId,
                                    System.currentTimeMillis())
                            .whenComplete((ignored, reserveError) -> {
                                if (reserveError == null) {
                                    callback.onComplete();
                                } else {
                                    callback.onFailure(unwrap(reserveError));
                                }
                            });
                });
    }

    public synchronized void finishCompletion(
            RecordingSnapshot finalSnapshot,
            RecordingSessionTerminator.Callback callback) {
        Objects.requireNonNull(callback);
        acceptingSamples = false;
        latestSnapshot = Objects.requireNonNull(finalSnapshot);
        CanonicalSessionJournal.State session = journal.read();
        if (closed || session == null) {
            callback.onFailure(new IllegalStateException(
                    "Canonical completion has no durable session"));
            return;
        }
        repository.session(session.sessionId)
                .whenComplete((repositorySession, sessionError) -> {
                    if (sessionError != null) {
                        callback.onFailure(unwrap(sessionError));
                        return;
                    }
                    if (repositorySession.getState()
                            == WatchActivityRepository.LifecycleState.COMPLETED) {
                        markTerminalBestEffort(session.sessionId);
                        WatchSyncFinishHook.requestRetry(context);
                        callback.onComplete();
                        return;
                    }
                    repository.samples(session.sessionId)
                            .whenComplete((samples, samplesError) -> {
                                if (samplesError != null) {
                                    callback.onFailure(unwrap(samplesError));
                                    return;
                                }
                                ActivityRecord metadata;
                                try {
                                    metadata = finalMetadata(
                                            session,
                                            finalSnapshot,
                                            samples);
                                } catch (RuntimeException error) {
                                    callback.onFailure(error);
                                    return;
                                }
                                repository.finishSessionFromPersistedSamples(
                                                session.sessionId,
                                                session.leaseToken,
                                                metadata,
                                                System.currentTimeMillis())
                                        .whenComplete((completion, finishError) -> {
                                            if (finishError != null) {
                                                callback.onFailure(unwrap(finishError));
                                                return;
                                            }
                                            markTerminalBestEffort(session.sessionId);
                                            try {
                                                WatchSyncFinishHook.onCompletionCommitted(
                                                        context,
                                                        completion);
                                            } catch (RuntimeException hookError) {
                                                fatal(
                                                        "Canonical sync scheduling failed",
                                                        hookError);
                                                WatchSyncFinishHook.requestRetry(context);
                                            }
                                            callback.onComplete();
                                        });
                            });
                });
    }

    public synchronized void discard(
            RecordingSnapshot finalSnapshot,
            RecordingSessionTerminator.Callback callback) {
        Objects.requireNonNull(callback);
        acceptingSamples = false;
        latestSnapshot = Objects.requireNonNull(finalSnapshot);
        CanonicalSessionJournal.State session = journal.read();
        if (closed || session == null) {
            callback.onFailure(new IllegalStateException(
                    "Canonical discard has no durable session"));
            return;
        }
        repository.session(session.sessionId)
                .whenComplete((repositorySession, sessionError) -> {
                    if (sessionError != null) {
                        callback.onFailure(unwrap(sessionError));
                        return;
                    }
                    if (repositorySession.getState()
                            == WatchActivityRepository.LifecycleState.DISCARDED) {
                        markTerminalBestEffort(session.sessionId);
                        WatchSyncFinishHook.requestRetry(context);
                        callback.onComplete();
                        return;
                    }
                    long revision = session.discardRevision != null
                            ? session.discardRevision
                            : Math.max(1L, repositorySession.getWatchRevision() + 1L);
                    String transferId = session.discardTransferId != null
                            ? session.discardTransferId
                            : CanonicalIds.newTransferId();
                    CanonicalSessionJournal.State reserved;
                    try {
                        reserved = journal.reserveDiscard(
                                session.sessionId,
                                revision,
                                transferId);
                    } catch (RuntimeException error) {
                        callback.onFailure(error);
                        return;
                    }
                    TombstoneRecord tombstone = CanonicalTransfers.tombstone(
                            reserved.discardTransferId,
                            reserved.sessionId,
                            reserved.discardRevision,
                            repositorySession.getWatchRevision(),
                            TombstoneOrigin.TOMBSTONE_ORIGIN_WATCH,
                            System.currentTimeMillis());
                    repository.discardSession(
                                    reserved.sessionId,
                                    reserved.leaseToken,
                                    tombstone,
                                    System.currentTimeMillis())
                            .whenComplete((ignored, discardError) -> {
                                if (discardError != null) {
                                    callback.onFailure(unwrap(discardError));
                                    return;
                                }
                                markTerminalBestEffort(reserved.sessionId);
                                WatchSyncFinishHook.requestRetry(context);
                                callback.onComplete();
                            });
                });
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        repository.close();
    }

    private void ensureStarted(CanonicalSessionJournal.State session) {
        if (session.sessionId.equals(submittedSessionId)) {
            return;
        }
        submittedSessionId = session.sessionId;
        WatchActivityRepository.StartRequest.Builder request =
                WatchActivityRepository.StartRequest.builder(
                                session.sessionId,
                                session.leaseToken,
                                session.startedAtUtcMs)
                .activityType(session.activityType)
                        .timeZone(session.timeZoneId, session.utcOffsetMinutes)
                        .accumulatedActiveMs(session.accumulatedActiveAtStartMs);
        if (session.bootId != null && session.monotonicAnchorMs != null) {
            request.monotonicAnchor(
                    session.bootId,
                    session.monotonicAnchorMs);
        }
        repository.startSession(request.build())
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        fatal("Canonical session start failed", unwrap(error));
                    }
                });
    }

    private void submitTransitionIfNeeded(
            CanonicalSessionJournal.State session,
            RecordingSnapshot snapshot) {
        RecordingState state = snapshot.getState();
        WatchActivityRepository.LifecycleState canonicalState;
        String errorCode = null;
        String errorMessage = null;
        if (state == RecordingState.RECORDING) {
            canonicalState = WatchActivityRepository.LifecycleState.RECORDING;
        } else if (state == RecordingState.PAUSED) {
            canonicalState = WatchActivityRepository.LifecycleState.PAUSED;
        } else if (state == RecordingState.INTERRUPTED) {
            canonicalState = WatchActivityRepository.LifecycleState.ERROR;
            RecordingError error = snapshot.getError();
            errorCode = error == null ? RecordingError.Code.RECOVERY.name()
                    : error.getCode().name();
            errorMessage = error == null ? "Recording interrupted" : error.getMessage();
        } else {
            return;
        }
        if (session.sessionId.equals(transitionedSessionId)
                && state == transitionedState) {
            return;
        }
        transitionedSessionId = session.sessionId;
        transitionedState = state;
        Long activeSince = state == RecordingState.RECORDING
                && snapshot.getActiveSinceElapsedRealtimeMillis() > 0L
                ? snapshot.getActiveSinceElapsedRealtimeMillis()
                : null;
        repository.transition(
                        session.sessionId,
                        session.leaseToken,
                        canonicalState,
                        session.bootId,
                        activeSince,
                        snapshot.getDurationMillis(),
                        errorCode,
                        errorMessage,
                        System.currentTimeMillis())
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        fatal("Canonical lifecycle transition failed", unwrap(error));
                    }
                });
    }

    private static ActivityRecord finalMetadata(
            CanonicalSessionJournal.State session,
            RecordingSnapshot snapshot,
            List<com.long2know.utilities.contract.proto.MeasurementSample> samples) {
        long elapsed = Math.max(
                snapshot.getDurationMillis(),
                snapshot.getElapsedDurationMillis());
        ActivityRecord.Builder activity = ActivityRecord.newBuilder()
                .setSessionId(session.sessionId)
                .setWatchRevision(session.completionRevision)
                .setActivityType(session.activityType)
                .setStartTimeUtcMs(session.startedAtUtcMs)
                .setEndTimeUtcMs(saturatedAdd(session.startedAtUtcMs, elapsed))
                .setElapsedDurationMs(elapsed)
                .setActiveDurationMs(snapshot.getDurationMillis())
                .setSource(SourceMetadata.newBuilder()
                        .setKind(SourceKind.SOURCE_KIND_DERIVED)
                        .setProvider("sportlogger")
                        .setAppContractVersion(1));
        if (session.timeZoneId != null) {
            activity.setTimeZoneId(session.timeZoneId)
                    .setUtcOffsetMinutesAtStart(session.utcOffsetMinutes);
        }
        ActivitySummary summary = summary(samples);
        if (summary != null) {
            activity.setSummary(summary);
        }
        int index = 0;
        for (CanonicalSessionJournal.Interval interval : session.intervals) {
            activity.addActiveIntervals(ActiveInterval.newBuilder()
                    .setIndex(index++)
                    .setStartTimeUtcMs(saturatedAdd(
                            session.startedAtUtcMs,
                            interval.startElapsedMs))
                    .setEndTimeUtcMs(saturatedAdd(
                            session.startedAtUtcMs,
                            interval.endElapsedMs))
                    .setActiveDurationMs(interval.activeDurationMs));
        }
        return activity.build();
    }

    private static ActivitySummary summary(
            List<com.long2know.utilities.contract.proto.MeasurementSample> samples) {
        ActivitySummary.Builder summary = ActivitySummary.newBuilder()
                .setSampleCount(samples.size());
        DoubleMeasurement distance = null;
        DoubleMeasurement calories = null;
        LongMeasurement steps = null;
        DoubleMeasurement elevationGain = null;
        DoubleMeasurement elevationLoss = null;
        DoubleMeasurement floors = null;
        double heartRateSum = 0D;
        double heartRateMax = 0D;
        long heartRateCount = 0L;
        long routeCount = 0L;
        MeasurementStatus heartRateStatus = null;
        double distanceDelta = 0D;
        long stepsDelta = 0L;
        double caloriesDelta = 0D;
        double elevationGainDelta = 0D;
        double elevationLossDelta = 0D;
        double floorsDelta = 0D;

        for (com.long2know.utilities.contract.proto.MeasurementSample sample : samples) {
            if (sample.hasLocation() && sample.getLocation().hasValue()) {
                routeCount++;
            }
            if (sample.hasHeartRateBpm() && sample.getHeartRateBpm().hasValue()) {
                double value = sample.getHeartRateBpm().getValue();
                heartRateSum += value;
                heartRateMax = Math.max(heartRateMax, value);
                heartRateCount++;
                heartRateStatus = sample.getHeartRateBpm().getStatus();
            }
            if (sample.hasDistanceTotalM() && sample.getDistanceTotalM().hasValue()) {
                distance = sample.getDistanceTotalM();
            } else if (sample.hasDistanceM() && sample.getDistanceM().hasValue()) {
                distanceDelta += sample.getDistanceM().getValue();
                distance = DoubleMeasurement.newBuilder()
                        .setValue(distanceDelta)
                        .setStatus(sample.getDistanceM().getStatus())
                        .build();
            }
            if (sample.hasStepsTotal() && sample.getStepsTotal().hasValue()) {
                steps = sample.getStepsTotal();
            } else if (sample.hasSteps() && sample.getSteps().hasValue()) {
                stepsDelta += sample.getSteps().getValue();
                steps = LongMeasurement.newBuilder()
                        .setValue(stepsDelta)
                        .setStatus(sample.getSteps().getStatus())
                        .build();
            }
            if (sample.hasCaloriesTotalKcal()
                    && sample.getCaloriesTotalKcal().hasValue()) {
                calories = sample.getCaloriesTotalKcal();
            } else if (sample.hasCaloriesKcal() && sample.getCaloriesKcal().hasValue()) {
                caloriesDelta += sample.getCaloriesKcal().getValue();
                calories = DoubleMeasurement.newBuilder()
                        .setValue(caloriesDelta)
                        .setStatus(sample.getCaloriesKcal().getStatus())
                        .build();
            }
            if (sample.hasElevationGainTotalM()
                    && sample.getElevationGainTotalM().hasValue()) {
                elevationGain = sample.getElevationGainTotalM();
            } else if (sample.hasElevationGainM()
                    && sample.getElevationGainM().hasValue()) {
                elevationGainDelta += sample.getElevationGainM().getValue();
                elevationGain = DoubleMeasurement.newBuilder()
                        .setValue(elevationGainDelta)
                        .setStatus(sample.getElevationGainM().getStatus())
                        .build();
            }
            if (sample.hasElevationLossTotalM()
                    && sample.getElevationLossTotalM().hasValue()) {
                elevationLoss = sample.getElevationLossTotalM();
            } else if (sample.hasElevationLossM()
                    && sample.getElevationLossM().hasValue()) {
                elevationLossDelta += sample.getElevationLossM().getValue();
                elevationLoss = DoubleMeasurement.newBuilder()
                        .setValue(elevationLossDelta)
                        .setStatus(sample.getElevationLossM().getStatus())
                        .build();
            }
            if (sample.hasFloorsTotal() && sample.getFloorsTotal().hasValue()) {
                floors = sample.getFloorsTotal();
            } else if (sample.hasFloors() && sample.getFloors().hasValue()) {
                floorsDelta += sample.getFloors().getValue();
                floors = DoubleMeasurement.newBuilder()
                        .setValue(floorsDelta)
                        .setStatus(sample.getFloors().getStatus())
                        .build();
            }
        }

        summary.setRoutePointCount(routeCount)
                .setHeartRateSampleCount(heartRateCount);
        if (distance != null) {
            summary.setDistanceM(distance);
        }
        if (calories != null) {
            summary.setCaloriesKcal(calories);
        }
        if (steps != null) {
            summary.setSteps(steps);
        }
        if (elevationGain != null) {
            summary.setElevationGainM(elevationGain);
        }
        if (elevationLoss != null) {
            summary.setElevationLossM(elevationLoss);
        }
        if (floors != null) {
            summary.setFloors(floors);
        }
        if (heartRateCount > 0L && heartRateStatus != null) {
            summary.setAverageHeartRateBpm(DoubleMeasurement.newBuilder()
                            .setValue(heartRateSum / heartRateCount)
                            .setStatus(heartRateStatus))
                    .setMaximumHeartRateBpm(DoubleMeasurement.newBuilder()
                            .setValue(heartRateMax)
                            .setStatus(heartRateStatus));
        }
        return summary.build();
    }

    private void fatal(String message, Throwable error) {
        if (!closed) {
            listener.onFatalError(message, error);
        }
    }

    private void markTerminalBestEffort(String sessionId) {
        try {
            journal.markTerminal(sessionId);
        } catch (RuntimeException error) {
            fatal("Canonical terminal state was not mirrored to recovery state", error);
        }
    }

    private static String bootId(int bootCount) {
        return bootCount < 0 ? null : "android-boot-count-" + bootCount;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
