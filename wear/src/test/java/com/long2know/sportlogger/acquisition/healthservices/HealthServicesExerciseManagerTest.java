package com.long2know.sportlogger.acquisition.healthservices;

import androidx.health.services.client.data.DataType;
import androidx.health.services.client.data.ExerciseCapabilities;
import androidx.health.services.client.data.ExerciseEndReason;
import androidx.health.services.client.data.ExerciseInfo;
import androidx.health.services.client.data.ExerciseTrackedStatus;
import androidx.health.services.client.data.ExerciseType;
import androidx.health.services.client.data.ExerciseTypeCapabilities;
import androidx.health.services.client.data.MeasureCapabilities;
import androidx.health.services.client.data.PassiveMonitoringCapabilities;

import com.google.common.util.concurrent.ListenableFuture;
import com.long2know.sportlogger.acquisition.ExerciseMetric;
import com.long2know.sportlogger.acquisition.MeasurementSample;
import com.long2know.sportlogger.acquisition.RouteStatus;
import com.long2know.sportlogger.recording.RecordingSnapshot;
import com.long2know.sportlogger.recording.RecordingState;
import com.long2know.sportlogger.recording.RecordingSessionTerminator;
import com.long2know.utilities.contract.proto.ActivityType;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class HealthServicesExerciseManagerTest {
    private final List<ScheduledThreadPoolExecutor> workers = new ArrayList<>();

    @After
    public void tearDown() {
        for (ScheduledThreadPoolExecutor worker : workers) {
            worker.shutdownNow();
        }
    }

    @Test
    public void anotherAppsExerciseIsNeverStartedPausedResumedOrEnded() throws Exception {
        FakePlatformClient client = new FakePlatformClient(capabilities(
                DataType.HEART_RATE_BPM,
                DataType.STEPS_TOTAL));
        client.status = ExerciseTrackedStatus.OTHER_APP_IN_PROGRESS;
        RecordingListener listener = new RecordingListener();
        HealthServicesExerciseManager manager = manager(client, listener);

        manager.initializeAsync();
        listener.awaitState(HealthServicesExerciseSnapshot.State.BLOCKED_BY_OTHER_APP);
        manager.startOrRecoverAsync(41, false, false);
        listener.awaitState(HealthServicesExerciseSnapshot.State.BLOCKED_BY_OTHER_APP);

        CountDownLatch ended = new CountDownLatch(1);
        manager.endSession(
                41,
                true,
                new RecordingSnapshot(RecordingState.STOPPING, 41, 0L, null),
                callback(ended));
        assertTrue(ended.await(1, TimeUnit.SECONDS));
        assertEquals(0, client.startCalls);
        assertEquals(0, client.pauseCalls);
        assertEquals(0, client.resumeCalls);
        assertEquals(0, client.endCalls);
    }

    @Test
    public void startUsesSupportedGrantedTotalsAndExcludesDeniedLocation() throws Exception {
        FakePlatformClient client = new FakePlatformClient(capabilities(
                DataType.HEART_RATE_BPM,
                DataType.STEPS,
                DataType.STEPS_TOTAL,
                DataType.LOCATION));
        client.status = ExerciseTrackedStatus.NO_EXERCISE_IN_PROGRESS;
        RecordingListener listener = new RecordingListener();
        Set<String> granted = new HashSet<>();
        granted.add("android.permission.health.READ_HEART_RATE");
        granted.add("android.permission.BODY_SENSORS");
        granted.add("android.permission.ACTIVITY_RECOGNITION");
        HealthServicesExerciseManager manager =
                manager(client, listener, granted);

        manager.initializeAsync();
        listener.awaitState(HealthServicesExerciseSnapshot.State.READY);
        manager.startOrRecoverAsync(42, false, false);
        HealthServicesExerciseSnapshot active =
                listener.awaitState(HealthServicesExerciseSnapshot.State.ACTIVE);

        assertEquals(1, client.startCalls);
        assertNotNull(client.lastDataTypes);
        assertTrue(client.lastDataTypes.contains(DataType.HEART_RATE_BPM));
        assertTrue(client.lastDataTypes.contains(DataType.STEPS_TOTAL));
        assertFalse(client.lastDataTypes.contains(DataType.STEPS));
        assertFalse(client.lastDataTypes.contains(DataType.LOCATION));
        assertFalse(client.lastGpsEnabled);
        assertTrue(
                active.getActiveHealthServicesMetrics()
                        .contains(ExerciseMetric.STEPS_TOTAL));
    }

    @Test
    public void disabledLocationRemovesRouteTypesWithoutBlockingHealthExercise()
            throws Exception {
        FakePlatformClient client = new FakePlatformClient(capabilities(
                DataType.HEART_RATE_BPM,
                DataType.LOCATION));
        client.status = ExerciseTrackedStatus.NO_EXERCISE_IN_PROGRESS;
        RecordingListener listener = new RecordingListener();
        Set<String> granted = new HashSet<>();
        granted.add("android.permission.health.READ_HEART_RATE");
        granted.add("android.permission.BODY_SENSORS");
        granted.add("android.permission.ACCESS_FINE_LOCATION");
        HealthServicesExerciseManager manager =
                manager(client, listener, granted, () -> false);

        manager.initializeAsync();
        listener.awaitState(HealthServicesExerciseSnapshot.State.READY);
        manager.startOrRecoverAsync(46, false, false);
        listener.awaitState(HealthServicesExerciseSnapshot.State.ACTIVE);

        assertTrue(client.lastDataTypes.contains(DataType.HEART_RATE_BPM));
        assertFalse(client.lastDataTypes.contains(DataType.LOCATION));
        assertFalse(client.lastGpsEnabled);
    }

    @Test
    public void canonicalCyclingSelectionUsesBikingCapabilitiesAndConfig()
            throws Exception {
        FakePlatformClient client = new FakePlatformClient(capabilities(
                ExerciseType.BIKING,
                DataType.HEART_RATE_BPM,
                DataType.SPEED));
        client.status = ExerciseTrackedStatus.NO_EXERCISE_IN_PROGRESS;
        RecordingListener listener = new RecordingListener();
        HealthServicesExerciseManager manager = manager(client, listener);

        manager.initializeAsync();
        listener.awaitState(HealthServicesExerciseSnapshot.State.READY);
        manager.startOrRecoverAsync(
                47,
                ActivityType.ACTIVITY_TYPE_CYCLING,
                false,
                false);
        listener.awaitState(HealthServicesExerciseSnapshot.State.ACTIVE);

        assertEquals(ExerciseType.BIKING, client.lastExerciseType);
        assertTrue(client.lastDataTypes.contains(DataType.HEART_RATE_BPM));
        assertTrue(client.lastDataTypes.contains(DataType.SPEED));
    }

    @Test
    public void recoveryWithoutOwnedPlatformSessionFallsBackWithoutStartingNewOne()
            throws Exception {
        FakePlatformClient client = new FakePlatformClient(capabilities(
                DataType.HEART_RATE_BPM));
        client.status = ExerciseTrackedStatus.NO_EXERCISE_IN_PROGRESS;
        RecordingListener listener = new RecordingListener();
        HealthServicesExerciseManager manager = manager(client, listener);

        manager.initializeAsync();
        listener.awaitState(HealthServicesExerciseSnapshot.State.READY);
        manager.startOrRecoverAsync(43, true, false);
        HealthServicesExerciseSnapshot fallback =
                listener.awaitState(HealthServicesExerciseSnapshot.State.RAW_FALLBACK);

        assertEquals(0, client.startCalls);
        assertTrue(fallback.getDetail().contains("process recovery"));
    }

    @Test
    public void exerciseMeasurePassiveAndPassiveGoalCapabilitiesStaySeparate()
            throws Exception {
        FakePlatformClient client = new FakePlatformClient(capabilities(
                DataType.HEART_RATE_BPM,
                DataType.STEPS_TOTAL));
        client.measureCapabilities = new MeasureCapabilities(
                Collections.singleton(DataType.HEART_RATE_BPM));
        client.passiveCapabilities = new PassiveMonitoringCapabilities(
                Collections.singleton(DataType.STEPS),
                Collections.singleton(DataType.STEPS_TOTAL),
                Collections.emptySet(),
                Collections.emptySet());
        client.status = ExerciseTrackedStatus.NO_EXERCISE_IN_PROGRESS;
        RecordingListener listener = new RecordingListener();
        HealthServicesExerciseManager manager = manager(client, listener);

        manager.initializeAsync();
        HealthServicesExerciseSnapshot ready =
                listener.awaitState(HealthServicesExerciseSnapshot.State.READY);

        assertTrue(
                ready.getCapabilityDimensions()
                        .getExerciseMetrics(ExerciseType.RUNNING)
                        .contains(ExerciseMetric.STEPS_TOTAL));
        assertTrue(
                ready.getCapabilityDimensions()
                        .getMeasureMetrics()
                        .contains(ExerciseMetric.HEART_RATE_BPM));
        assertTrue(
                ready.getCapabilityDimensions()
                        .getPassiveMetrics()
                        .contains(ExerciseMetric.STEPS));
        assertTrue(
                ready.getCapabilityDimensions()
                        .getPassiveGoalMetrics()
                        .contains(ExerciseMetric.STEPS_TOTAL));
    }

    @Test
    public void stopWaitsForFinalEndedUpdateBeforeCompleting() throws Exception {
        FakePlatformClient client = new FakePlatformClient(capabilities(
                DataType.HEART_RATE_BPM));
        client.status = ExerciseTrackedStatus.NO_EXERCISE_IN_PROGRESS;
        RecordingListener listener = new RecordingListener();
        HealthServicesExerciseManager manager = manager(client, listener);
        manager.initializeAsync();
        listener.awaitState(HealthServicesExerciseSnapshot.State.READY);
        manager.startOrRecoverAsync(44, false, false);
        listener.awaitState(HealthServicesExerciseSnapshot.State.ACTIVE);

        CountDownLatch ended = new CountDownLatch(1);
        manager.endSession(
                44,
                true,
                new RecordingSnapshot(RecordingState.STOPPING, 44, 0L, null),
                callback(ended));
        listener.awaitState(HealthServicesExerciseSnapshot.State.ENDING);

        assertEquals(1, client.flushCalls);
        assertTrue(waitUntil(() -> client.endCalls == 1, 1_000L));
        assertFalse(ended.await(50, TimeUnit.MILLISECONDS));

        client.emitEndedUpdate();
        assertTrue(ended.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void missingFinalUpdateBlocksExportAfterProcessRecoveryButAllowsDiscard()
            throws Exception {
        FakePlatformClient client = new FakePlatformClient(capabilities(
                DataType.HEART_RATE_BPM));
        client.status = ExerciseTrackedStatus.NO_EXERCISE_IN_PROGRESS;
        TestSessionJournal journal = new TestSessionJournal();
        journal.markActive(45);
        RecordingListener listener = new RecordingListener();
        HealthServicesExerciseManager manager = manager(client, listener, journal);
        manager.initializeAsync();
        listener.awaitState(HealthServicesExerciseSnapshot.State.READY);

        CountDownLatch failed = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean();
        manager.endSession(
                45,
                true,
                new RecordingSnapshot(RecordingState.STOPPING, 45, 0L, null),
                new RecordingSessionTerminator.Callback() {
            @Override
            public void onComplete() {
                completed.set(true);
            }

            @Override
            public void onFailure(Throwable error) {
                failed.countDown();
            }
                });

        assertTrue(failed.await(1, TimeUnit.SECONDS));
        assertFalse(completed.get());
        assertEquals(45, journal.activeActivityId());

        CountDownLatch discarded = new CountDownLatch(1);
        manager.endSession(
                45,
                false,
                new RecordingSnapshot(RecordingState.DISCARDING, 45, 0L, null),
                callback(discarded));
        assertTrue(discarded.await(1, TimeUnit.SECONDS));
        assertEquals(0, journal.activeActivityId());
    }

    private HealthServicesExerciseManager manager(
            FakePlatformClient client,
            RecordingListener listener) {
        Set<String> granted = new HashSet<>();
        granted.add("android.permission.health.READ_HEART_RATE");
        granted.add("android.permission.BODY_SENSORS");
        granted.add("android.permission.ACTIVITY_RECOGNITION");
        granted.add("android.permission.ACCESS_FINE_LOCATION");
        return manager(client, listener, granted);
    }

    private HealthServicesExerciseManager manager(
            FakePlatformClient client,
            RecordingListener listener,
            HealthServicesSessionJournal journal) {
        Set<String> granted = new HashSet<>();
        granted.add("android.permission.health.READ_HEART_RATE");
        granted.add("android.permission.BODY_SENSORS");
        granted.add("android.permission.ACTIVITY_RECOGNITION");
        ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1);
        worker.setRemoveOnCancelPolicy(true);
        workers.add(worker);
        return new HealthServicesExerciseManager(
                client,
                worker,
                granted::contains,
                () -> true,
                Collections.emptyMap(),
                journal,
                listener);
    }

    private HealthServicesExerciseManager manager(
            FakePlatformClient client,
            RecordingListener listener,
            Set<String> granted) {
        return manager(client, listener, granted, () -> true);
    }

    private HealthServicesExerciseManager manager(
            FakePlatformClient client,
            RecordingListener listener,
            Set<String> granted,
            BooleanSupplier locationEnabled) {
        ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1);
        worker.setRemoveOnCancelPolicy(true);
        workers.add(worker);
        return new HealthServicesExerciseManager(
                client,
                worker,
                granted::contains,
                locationEnabled,
                Collections.emptyMap(),
                new TestSessionJournal(),
                listener);
    }

    private static RecordingSessionTerminator.Callback callback(
            CountDownLatch ended) {
        return new RecordingSessionTerminator.Callback() {
            @Override
            public void onComplete() {
                ended.countDown();
            }

            @Override
            public void onFailure(Throwable error) {
                throw new AssertionError(error);
            }
        };
    }

    private static boolean waitUntil(Check check, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (check.evaluate()) {
                return true;
            }
            Thread.sleep(5L);
        }
        return check.evaluate();
    }

    private interface Check {
        boolean evaluate();
    }

    private static ExerciseCapabilities capabilities(DataType<?, ?>... dataTypes) {
        return capabilities(ExerciseType.RUNNING, dataTypes);
    }

    private static ExerciseCapabilities capabilities(
            ExerciseType exerciseType,
            DataType<?, ?>... dataTypes) {
        Set<DataType<?, ?>> supported = new HashSet<>();
        Collections.addAll(supported, dataTypes);
        ExerciseTypeCapabilities running = new ExerciseTypeCapabilities(
                supported,
                Collections.emptyMap(),
                Collections.emptyMap(),
                false);
        Map<ExerciseType, ExerciseTypeCapabilities> byType = new HashMap<>();
        byType.put(exerciseType, running);
        return new ExerciseCapabilities(byType);
    }

    private static final class RecordingListener
            implements HealthServicesExerciseManager.Listener {
        private final List<HealthServicesExerciseSnapshot> snapshots =
                Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onSnapshot(HealthServicesExerciseSnapshot snapshot) {
            snapshots.add(snapshot);
        }

        @Override
        public void onSample(MeasurementSample<?> sample) {
        }

        @Override
        public void onRouteStatus(RouteStatus status) {
        }

        @Override
        public void onFailure(String message, Throwable error) {
        }

        private HealthServicesExerciseSnapshot awaitState(
                HealthServicesExerciseSnapshot.State state) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
            while (System.nanoTime() < deadline) {
                synchronized (snapshots) {
                    for (HealthServicesExerciseSnapshot snapshot : snapshots) {
                        if (snapshot.getState() == state) {
                            snapshots.remove(snapshot);
                            return snapshot;
                        }
                    }
                }
                Thread.sleep(5L);
            }
            throw new AssertionError(
                    "Did not observe Health Services state "
                            + state
                            + "; observed="
                            + snapshots);
        }
    }

    private static final class FakePlatformClient
            implements HealthServicesExerciseManager.PlatformClient {
        private final ExerciseCapabilities capabilities;
        private MeasureCapabilities measureCapabilities =
                new MeasureCapabilities(Collections.emptySet());
        private PassiveMonitoringCapabilities passiveCapabilities =
                new PassiveMonitoringCapabilities(
                        Collections.emptySet(),
                        Collections.emptySet(),
                        Collections.emptySet(),
                        Collections.emptySet());
        private int status;
        private int startCalls;
        private int pauseCalls;
        private int resumeCalls;
        private int flushCalls;
        private int endCalls;
        private ExerciseType exerciseType = ExerciseType.RUNNING;
        private Set<DataType<?, ?>> lastDataTypes;
        private boolean lastGpsEnabled;
        private ExerciseType lastExerciseType;
        private Executor callbackExecutor;
        private HealthServicesExerciseManager.PlatformUpdateListener callback;

        private FakePlatformClient(ExerciseCapabilities capabilities) {
            this.capabilities = capabilities;
        }

        @Override
        public ListenableFuture<ExerciseCapabilities> getCapabilitiesAsync() {
            return ImmediateFuture.success(capabilities);
        }

        @Override
        public ListenableFuture<MeasureCapabilities> getMeasureCapabilitiesAsync() {
            return ImmediateFuture.success(measureCapabilities);
        }

        @Override
        public ListenableFuture<PassiveMonitoringCapabilities>
                getPassiveCapabilitiesAsync() {
            return ImmediateFuture.success(passiveCapabilities);
        }

        @Override
        public ListenableFuture<ExerciseInfo> getCurrentExerciseInfoAsync() {
            return ImmediateFuture.success(
                    new ExerciseInfo(status, exerciseType));
        }

        @Override
        public ListenableFuture<Void> startExerciseAsync(
                ExerciseType exerciseType,
                Set<DataType<?, ?>> dataTypes,
                boolean gpsEnabled) {
            startCalls++;
            lastExerciseType = exerciseType;
            this.exerciseType = exerciseType;
            lastDataTypes = new HashSet<>(dataTypes);
            lastGpsEnabled = gpsEnabled;
            status = ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS;
            return ImmediateFuture.success(null);
        }

        @Override
        public ListenableFuture<Void> pauseExerciseAsync() {
            pauseCalls++;
            return ImmediateFuture.success(null);
        }

        @Override
        public ListenableFuture<Void> resumeExerciseAsync() {
            resumeCalls++;
            return ImmediateFuture.success(null);
        }

        @Override
        public ListenableFuture<Void> flushAsync() {
            flushCalls++;
            return ImmediateFuture.success(null);
        }

        @Override
        public ListenableFuture<Void> endExerciseAsync() {
            endCalls++;
            return ImmediateFuture.success(null);
        }

        @Override
        public void setUpdateCallback(
                Executor executor,
                HealthServicesExerciseManager.PlatformUpdateListener callback) {
            callbackExecutor = executor;
            this.callback = callback;
            executor.execute(callback::onRegistered);
        }

        @Override
        public ListenableFuture<Void> clearUpdateCallbackAsync() {
            return ImmediateFuture.success(null);
        }

        private void emitEndedUpdate() {
            status = ExerciseTrackedStatus.NO_EXERCISE_IN_PROGRESS;
            HealthServicesExerciseManager.PlatformUpdate update =
                    new HealthServicesExerciseManager.PlatformUpdate(
                            Collections.emptyList(),
                            HealthServicesExerciseManager.PlatformExerciseState.ENDED,
                            ExerciseEndReason.USER_END);
            callbackExecutor.execute(() -> callback.onExerciseUpdate(update));
        }
    }

    private static final class ImmediateFuture<T> implements ListenableFuture<T> {
        private final T value;
        private final Throwable error;

        private ImmediateFuture(T value, Throwable error) {
            this.value = value;
            this.error = error;
        }

        private static <T> ImmediateFuture<T> success(T value) {
            return new ImmediateFuture<>(value, null);
        }

        @Override
        public void addListener(Runnable listener, Executor executor) {
            executor.execute(listener);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public T get() throws ExecutionException {
            if (error != null) {
                throw new ExecutionException(error);
            }
            return value;
        }

        @Override
        public T get(long timeout, TimeUnit unit)
                throws ExecutionException, TimeoutException {
            return get();
        }
    }

    private static final class TestSessionJournal
            implements HealthServicesSessionJournal {
        private int activityId;

        @Override
        public int activeActivityId() {
            return activityId;
        }

        @Override
        public void markActive(int activityId) {
            this.activityId = activityId;
        }

        @Override
        public void clear() {
            activityId = 0;
        }
    }
}
