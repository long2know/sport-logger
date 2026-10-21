package com.long2know.sportlogger.acquisition.healthservices;

import android.content.Context;
import android.os.Build;

import androidx.health.services.client.ExerciseClient;
import androidx.health.services.client.ExerciseUpdateCallback;
import androidx.health.services.client.HealthServices;
import androidx.health.services.client.HealthServicesClient;
import androidx.health.services.client.MeasureClient;
import androidx.health.services.client.PassiveMonitoringClient;
import androidx.health.services.client.data.Availability;
import androidx.health.services.client.data.DataType;
import androidx.health.services.client.data.DataTypeAvailability;
import androidx.health.services.client.data.ExerciseCapabilities;
import androidx.health.services.client.data.ExerciseConfig;
import androidx.health.services.client.data.ExerciseInfo;
import androidx.health.services.client.data.ExerciseLapSummary;
import androidx.health.services.client.data.ExerciseState;
import androidx.health.services.client.data.ExerciseTrackedStatus;
import androidx.health.services.client.data.ExerciseType;
import androidx.health.services.client.data.ExerciseTypeCapabilities;
import androidx.health.services.client.data.ExerciseUpdate;
import androidx.health.services.client.data.LocationAvailability;
import androidx.health.services.client.data.MeasureCapabilities;
import androidx.health.services.client.data.PassiveMonitoringCapabilities;

import com.google.common.util.concurrent.ListenableFuture;
import com.long2know.sportlogger.acquisition.ExerciseMetric;
import com.long2know.sportlogger.acquisition.MeasurementCapability;
import com.long2know.sportlogger.acquisition.MeasurementProvenance;
import com.long2know.sportlogger.acquisition.MeasurementSample;
import com.long2know.sportlogger.acquisition.RecordingPermissionPolicy;
import com.long2know.sportlogger.acquisition.RawHardwareProbe;
import com.long2know.sportlogger.acquisition.RouteStatus;
import com.long2know.sportlogger.recording.RecordingSessionTerminator;
import com.long2know.sportlogger.recording.RecordingActivityTypePolicy;
import com.long2know.utilities.contract.proto.ActivityType;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public final class HealthServicesExerciseManager implements RecordingSessionTerminator {
    public interface Listener {
        void onSnapshot(HealthServicesExerciseSnapshot snapshot);

        void onSample(MeasurementSample<?> sample);

        void onRouteStatus(RouteStatus status);

        void onFailure(String message, Throwable error);
    }

    interface PlatformClient {
        ListenableFuture<ExerciseCapabilities> getCapabilitiesAsync();

        ListenableFuture<MeasureCapabilities> getMeasureCapabilitiesAsync();

        ListenableFuture<PassiveMonitoringCapabilities> getPassiveCapabilitiesAsync();

        ListenableFuture<ExerciseInfo> getCurrentExerciseInfoAsync();

        ListenableFuture<Void> startExerciseAsync(
                ExerciseType exerciseType,
                Set<DataType<?, ?>> dataTypes,
                boolean gpsEnabled);

        ListenableFuture<Void> pauseExerciseAsync();

        ListenableFuture<Void> resumeExerciseAsync();

        ListenableFuture<Void> flushAsync();

        ListenableFuture<Void> endExerciseAsync();

        void setUpdateCallback(
                Executor executor,
                PlatformUpdateListener listener);

        ListenableFuture<Void> clearUpdateCallbackAsync();
    }

    interface PlatformUpdateListener {
        void onRegistered();

        void onRegistrationFailed(Throwable error);

        void onExerciseUpdate(PlatformUpdate update);

        void onAvailabilityChanged(
                DataType<?, ?> dataType,
                Availability availability);
    }

    enum PlatformExerciseState {
        ACTIVE,
        PAUSED,
        ENDING,
        ENDED
    }

    static final class PlatformUpdate {
        private final java.util.List<MeasurementSample<?>> samples;
        private final PlatformExerciseState state;
        private final int endReason;

        PlatformUpdate(
                java.util.List<MeasurementSample<?>> samples,
                PlatformExerciseState state,
                int endReason) {
            this.samples = Collections.unmodifiableList(
                    new java.util.ArrayList<>(samples));
            this.state = Objects.requireNonNull(state);
            this.endReason = endReason;
        }
    }

    private static final long FINAL_UPDATE_TIMEOUT_MILLIS = 5_000L;

    private final PlatformClient client;
    private final ScheduledExecutorService worker;
    private final RecordingPermissionPolicy.Checker permissionChecker;
    private final BooleanSupplier locationEnabled;
    private final Listener listener;
    private final HealthServicesSessionJournal sessionJournal;
    private final Map<ExerciseMetric, MeasurementCapability.RawHardwareState>
            rawHardwareStates;
    private final PlatformUpdateListener updateListener = new ManagerUpdateListener();
    private final EnumMap<ExerciseMetric, MeasurementCapability> capabilities =
            new EnumMap<>(ExerciseMetric.class);
    private final EnumSet<ExerciseMetric> configuredMetrics =
            EnumSet.noneOf(ExerciseMetric.class);
    private final Set<DataType<?, ?>> configuredDataTypes = new HashSet<>();
    private Set<DataType<?, ?>> configuredSupportedDataTypes = Collections.emptySet();
    private ExerciseType configuredExerciseType = ExerciseType.RUNNING;
    private ActivityType configuredActivityType = ActivityType.ACTIVITY_TYPE_RUNNING;

    private volatile HealthServicesExerciseSnapshot snapshot =
            HealthServicesExerciseSnapshot.uninitialized();
    private HealthServicesCapabilityDimensions capabilityDimensions =
            HealthServicesCapabilityDimensions.empty();
    private ExerciseCapabilities discoveredExerciseCapabilities;
    private MeasureCapabilities discoveredMeasureCapabilities;
    private boolean initialized;
    private boolean initializationStarted;
    private boolean callbackRegistered;
    private boolean healthServicesUsable;
    private boolean closed;
    private int activeActivityId;
    private PendingStart pendingStart;
    private PendingEndRequest pendingEndRequest;
    private PendingTermination pendingTermination;
    private ScheduledFuture<?> terminationTimeout;

    public HealthServicesExerciseManager(
            Context context,
            ScheduledExecutorService worker,
            RecordingPermissionPolicy.Checker permissionChecker,
            BooleanSupplier locationEnabled,
            Listener listener) {
        this(
                new AndroidPlatformClient(
                        HealthServices.getClient(context.getApplicationContext())),
                worker,
                permissionChecker,
                locationEnabled,
                RawHardwareProbe.probe(context),
                new SharedPreferencesHealthServicesSessionJournal(context),
                listener);
    }

    HealthServicesExerciseManager(
            PlatformClient client,
            ScheduledExecutorService worker,
            RecordingPermissionPolicy.Checker permissionChecker,
            Listener listener) {
        this(
                client,
                worker,
                permissionChecker,
                () -> true,
                Collections.emptyMap(),
                new InMemorySessionJournal(),
                listener);
    }

    HealthServicesExerciseManager(
            PlatformClient client,
            ScheduledExecutorService worker,
            RecordingPermissionPolicy.Checker permissionChecker,
            BooleanSupplier locationEnabled,
            Map<ExerciseMetric, MeasurementCapability.RawHardwareState> rawHardwareStates,
            HealthServicesSessionJournal sessionJournal,
            Listener listener) {
        this.client = Objects.requireNonNull(client);
        this.worker = Objects.requireNonNull(worker);
        this.permissionChecker = Objects.requireNonNull(permissionChecker);
        this.locationEnabled = Objects.requireNonNull(locationEnabled);
        this.rawHardwareStates = new EnumMap<>(ExerciseMetric.class);
        this.rawHardwareStates.putAll(Objects.requireNonNull(rawHardwareStates));
        this.sessionJournal = Objects.requireNonNull(sessionJournal);
        this.activeActivityId = sessionJournal.activeActivityId();
        this.listener = Objects.requireNonNull(listener);
    }

    public void initializeAsync() {
        execute(this::initializeInternal);
    }

    public void startOrRecoverAsync(
            int activityId,
            ActivityType activityType,
            boolean recovery,
            boolean pausedRecovery) {
        if (activityId <= 0) {
            return;
        }
        ActivityType supported =
                RecordingActivityTypePolicy.requireSupported(activityType);
        execute(() -> {
            pendingStart = new PendingStart(
                    activityId,
                    supported,
                    recovery,
                    pausedRecovery);
            if (!initialized) {
                initializeInternal();
                return;
            }
            consumePendingStart();
        });
    }

    public void startOrRecoverAsync(
            int activityId,
            boolean recovery,
            boolean pausedRecovery) {
        startOrRecoverAsync(
                activityId,
                ActivityType.ACTIVITY_TYPE_RUNNING,
                recovery,
                pausedRecovery);
    }

    public void pauseAsync() {
        execute(() -> changePausedState(true));
    }

    public void resumeAsync() {
        execute(() -> changePausedState(false));
    }

    public HealthServicesExerciseSnapshot getSnapshot() {
        return snapshot;
    }

    @Override
    public void endSession(
            int activityId,
            boolean preserveFinalData,
            com.long2know.sportlogger.recording.RecordingSnapshot finalSnapshot,
            RecordingSessionTerminator.Callback callback) {
        Objects.requireNonNull(callback);
        execute(() -> endSessionInternal(activityId, preserveFinalData, callback));
    }

    public void closeAsync() {
        execute(() -> {
            if (closed) {
                return;
            }
            closed = true;
            cancelTerminationTimeout();
            publish(
                    HealthServicesExerciseSnapshot.State.CLOSED,
                    snapshot.getOwnership(),
                    Collections.emptySet(),
                    "");
            if (!callbackRegistered) {
                worker.shutdown();
                return;
            }
            ListenableFuture<Void> clearFuture =
                    client.clearUpdateCallbackAsync();
            clearFuture.addListener(worker::shutdown, Runnable::run);
        });
    }

    private void initializeInternal() {
        if (closed || initializationStarted) {
            return;
        }
        initializationStarted = true;
        try {
            client.setUpdateCallback(worker, updateListener);
            callbackRegistered = true;
        } catch (RuntimeException error) {
            failInitialization("Health Services listener registration failed", error);
            return;
        }
        listen(
                client.getCapabilitiesAsync(),
                this::handleCapabilities,
                error -> failInitialization(
                        "Health Services capabilities are unavailable",
                        error));
    }

    private void handleCapabilities(ExerciseCapabilities exerciseCapabilities) {
        discoveredExerciseCapabilities = exerciseCapabilities;
        ExerciseTypeCapabilities runningCapabilities = exerciseCapabilities
                .getTypeToCapabilities()
                .get(ExerciseType.RUNNING);
        Set<DataType<?, ?>> supportedDataTypes = runningCapabilities == null
                ? Collections.emptySet()
                : runningCapabilities.getSupportedDataTypes();
        configuredSupportedDataTypes = Collections.unmodifiableSet(
                new HashSet<>(supportedDataTypes));
        rebuildCapabilities(supportedDataTypes);
        configuredMetrics.clear();
        configuredMetrics.addAll(selectConfiguredMetrics());
        configuredDataTypes.clear();
        for (ExerciseMetric metric : configuredMetrics) {
            DataType<?, ?> dataType = HealthServicesMetricMapper.dataType(metric);
            if (dataType != null) {
                configuredDataTypes.add(dataType);
            }
        }
        healthServicesUsable = runningCapabilities != null && !configuredDataTypes.isEmpty();
        listen(
                client.getMeasureCapabilitiesAsync(),
                measureCapabilities -> {
                    discoveredMeasureCapabilities = measureCapabilities;
                    queryPassiveCapabilities();
                },
                error -> {
                    listener.onFailure(
                            "Health Services one-off measure capabilities are unavailable",
                            error);
                    queryPassiveCapabilities();
                });
    }

    private void queryPassiveCapabilities() {
        listen(
                client.getPassiveCapabilitiesAsync(),
                this::completeCapabilityDiscovery,
                error -> {
                    listener.onFailure(
                            "Health Services passive capabilities are unavailable",
                            error);
                    completeCapabilityDiscovery(null);
                });
    }

    private void completeCapabilityDiscovery(
            PassiveMonitoringCapabilities passiveCapabilities) {
        capabilityDimensions = new HealthServicesCapabilityDimensions(
                discoveredExerciseCapabilities,
                discoveredMeasureCapabilities,
                passiveCapabilities);
        listen(
                client.getCurrentExerciseInfoAsync(),
                info -> {
                    initialized = true;
                    HealthServicesExerciseSnapshot.Ownership ownership = ownership(info);
                    HealthServicesExerciseSnapshot.State state =
                            ownership == HealthServicesExerciseSnapshot.Ownership.OWNED_BY_OTHER_APP
                                    ? HealthServicesExerciseSnapshot.State.BLOCKED_BY_OTHER_APP
                                    : HealthServicesExerciseSnapshot.State.READY;
                    publish(state, ownership, Collections.emptySet(), "");
                    consumePendingEndRequest();
                    consumePendingStart();
                },
                error -> {
                    initialized = true;
                    publish(
                            healthServicesUsable
                                    ? HealthServicesExerciseSnapshot.State.READY
                                    : HealthServicesExerciseSnapshot.State.RAW_FALLBACK,
                            HealthServicesExerciseSnapshot.Ownership.UNKNOWN,
                            Collections.emptySet(),
                            describe(error));
                    listener.onFailure(
                            "Could not determine Health Services exercise ownership",
                            error);
                    consumePendingEndRequest();
                    consumePendingStart();
                });
    }

    private void consumePendingStart() {
        PendingStart start = pendingStart;
        pendingStart = null;
        if (start == null || closed) {
            return;
        }
        configureForActivity(start.activityType);
        if (!healthServicesUsable) {
            publish(
                    HealthServicesExerciseSnapshot.State.RAW_FALLBACK,
                    snapshot.getOwnership(),
                    Collections.emptySet(),
                    "Health Services is unavailable for "
                            + start.activityType
                            + " on this watch");
            return;
        }
        listen(
                client.getCurrentExerciseInfoAsync(),
                info -> handleStartOwnership(start, info),
                error -> {
                    publish(
                            HealthServicesExerciseSnapshot.State.RAW_FALLBACK,
                            HealthServicesExerciseSnapshot.Ownership.UNKNOWN,
                            Collections.emptySet(),
                            describe(error));
                    listener.onFailure(
                            "Could not verify Health Services exercise ownership",
                            error);
                });
    }

    private void handleStartOwnership(PendingStart start, ExerciseInfo info) {
        HealthServicesExerciseSnapshot.Ownership ownership = ownership(info);
        if (ownership == HealthServicesExerciseSnapshot.Ownership.OWNED_BY_OTHER_APP) {
            publish(
                    HealthServicesExerciseSnapshot.State.BLOCKED_BY_OTHER_APP,
                    ownership,
                    Collections.emptySet(),
                    "Another app owns the active Health Services exercise");
            listener.onFailure(
                    "Another app owns the active Health Services exercise",
                    null);
            return;
        }
        if (ownership == HealthServicesExerciseSnapshot.Ownership.OWNED_BY_THIS_APP) {
            if (!configuredExerciseType.equals(info.getExerciseType())) {
                publish(
                        HealthServicesExerciseSnapshot.State.RAW_FALLBACK,
                        ownership,
                        Collections.emptySet(),
                        "Owned Health Services exercise type does not match "
                                + start.activityType);
                listener.onFailure(
                        "Owned Health Services exercise type does not match recovered activity",
                        null);
                return;
            }
            activeActivityId = start.activityId;
            if (!persistOwnedActivity(start.activityId)) {
                abandonUndurableOwnedSession();
                return;
            }
            publish(
                    start.pausedRecovery
                            ? HealthServicesExerciseSnapshot.State.PAUSED
                            : HealthServicesExerciseSnapshot.State.ACTIVE,
                    ownership,
                    configuredMetrics,
                    "Reattached to the owned Health Services exercise");
            return;
        }
        if (start.recovery) {
            publish(
                    HealthServicesExerciseSnapshot.State.RAW_FALLBACK,
                    ownership,
                    Collections.emptySet(),
                    "No owned Health Services exercise survived process recovery");
            return;
        }
        if (ownership == HealthServicesExerciseSnapshot.Ownership.UNKNOWN) {
            publish(
                    HealthServicesExerciseSnapshot.State.RAW_FALLBACK,
                    ownership,
                    Collections.emptySet(),
                    "Ownership is unknown; a new Health Services exercise was not started");
            return;
        }
        startNewExercise(start.activityId);
    }

    private void startNewExercise(int activityId) {
        boolean gpsEnabled = configuredMetrics.contains(ExerciseMetric.LOCATION)
                || configuredMetrics.contains(ExerciseMetric.ABSOLUTE_ELEVATION);
        publish(
                HealthServicesExerciseSnapshot.State.STARTING,
                HealthServicesExerciseSnapshot.Ownership.NONE,
                Collections.emptySet(),
                "");
        listen(
                client.startExerciseAsync(
                        configuredExerciseType,
                        Collections.unmodifiableSet(
                                new HashSet<>(configuredDataTypes)),
                        gpsEnabled),
                ignored -> {
                    activeActivityId = activityId;
                    if (!persistOwnedActivity(activityId)) {
                        abandonUndurableOwnedSession();
                        return;
                    }
                    publish(
                            HealthServicesExerciseSnapshot.State.ACTIVE,
                            HealthServicesExerciseSnapshot.Ownership.OWNED_BY_THIS_APP,
                            configuredMetrics,
                            "");
                },
                error -> {
                    publish(
                            HealthServicesExerciseSnapshot.State.RAW_FALLBACK,
                            HealthServicesExerciseSnapshot.Ownership.NONE,
                            Collections.emptySet(),
                            describe(error));
                    listener.onFailure("Health Services exercise start failed", error);
                });
    }

    private void changePausedState(boolean paused) {
        if (closed || activeActivityId <= 0) {
            return;
        }
        listen(
                client.getCurrentExerciseInfoAsync(),
                info -> {
                    HealthServicesExerciseSnapshot.Ownership ownership = ownership(info);
                    if (ownership
                            != HealthServicesExerciseSnapshot.Ownership.OWNED_BY_THIS_APP) {
                        if (ownership
                                == HealthServicesExerciseSnapshot.Ownership.OWNED_BY_OTHER_APP) {
                            publish(
                                    HealthServicesExerciseSnapshot.State.BLOCKED_BY_OTHER_APP,
                                    ownership,
                                    Collections.emptySet(),
                                    "Pause or resume was not sent to another app's exercise");
                        } else {
                            publish(
                                    HealthServicesExerciseSnapshot.State.RAW_FALLBACK,
                                    ownership,
                                    Collections.emptySet(),
                                    "The owned Health Services exercise is no longer active");
                        }
                        return;
                    }
                    ListenableFuture<Void> future = paused
                            ? client.pauseExerciseAsync()
                            : client.resumeExerciseAsync();
                    listen(
                            future,
                            ignored -> publish(
                                    paused
                                            ? HealthServicesExerciseSnapshot.State.PAUSED
                                            : HealthServicesExerciseSnapshot.State.ACTIVE,
                                    ownership,
                                    configuredMetrics,
                                    ""),
                            error -> listener.onFailure(
                                    paused
                                            ? "Health Services pause failed"
                                            : "Health Services resume failed",
                                    error));
                },
                error -> listener.onFailure(
                        "Could not verify ownership before pause or resume",
                        error));
    }

    private void endSessionInternal(
            int activityId,
            boolean preserveFinalData,
            RecordingSessionTerminator.Callback callback) {
        if (closed) {
            callback.onFailure(new IllegalStateException(
                    "Health Services manager is closed"));
            return;
        }
        if (!initialized) {
            if (pendingEndRequest != null || pendingTermination != null) {
                callback.onFailure(new IllegalStateException(
                        "Health Services termination is already in progress"));
                return;
            }
            pendingEndRequest =
                    new PendingEndRequest(activityId, preserveFinalData, callback);
            initializeInternal();
            return;
        }
        if (!healthServicesUsable) {
            if (preserveFinalData && sessionJournal.activeActivityId() == activityId) {
                callback.onFailure(new IllegalStateException(
                        "Final Health Services data is unavailable"));
            } else {
                clearOwnedActivity();
                callback.onComplete();
            }
            return;
        }
        if (pendingTermination != null) {
            callback.onFailure(new IllegalStateException(
                    "Health Services termination is already in progress"));
            return;
        }
        listen(
                client.getCurrentExerciseInfoAsync(),
                info -> {
                    HealthServicesExerciseSnapshot.Ownership ownership = ownership(info);
                    if (ownership
                            != HealthServicesExerciseSnapshot.Ownership.OWNED_BY_THIS_APP) {
                        if (ownership
                                == HealthServicesExerciseSnapshot.Ownership.OWNED_BY_OTHER_APP) {
                            publish(
                                    HealthServicesExerciseSnapshot.State.BLOCKED_BY_OTHER_APP,
                                    ownership,
                                    Collections.emptySet(),
                                    "Another app's exercise was left untouched");
                        }
                        if (preserveFinalData
                                && sessionJournal.activeActivityId() == activityId) {
                            callback.onFailure(new IllegalStateException(
                                    "The owned Health Services session ended without a final update"));
                        } else {
                            activeActivityId = 0;
                            clearOwnedActivity();
                            callback.onComplete();
                        }
                        return;
                    }
                    pendingTermination =
                            new PendingTermination(activityId, preserveFinalData, callback);
                    publish(
                            HealthServicesExerciseSnapshot.State.ENDING,
                            ownership,
                            configuredMetrics,
                            "");
                    if (preserveFinalData) {
                        listen(
                                client.flushAsync(),
                                ignored -> issueEndExercise(),
                                error -> {
                                    listener.onFailure(
                                            "Health Services flush failed before end",
                                            error);
                                    issueEndExercise();
                                });
                    } else {
                        issueEndExercise();
                    }
                },
                error -> callback.onFailure(error));
    }

    private void consumePendingEndRequest() {
        PendingEndRequest request = pendingEndRequest;
        pendingEndRequest = null;
        if (request != null) {
            endSessionInternal(
                    request.activityId,
                    request.preserveFinalData,
                    request.callback);
        }
    }

    private void issueEndExercise() {
        PendingTermination pending = pendingTermination;
        if (pending == null) {
            return;
        }
        listen(
                client.endExerciseAsync(),
                ignored -> {
                    cancelTerminationTimeout();
                    terminationTimeout = worker.schedule(
                            () -> failPendingTermination(new IllegalStateException(
                                    "Timed out waiting for the final Health Services update")),
                            FINAL_UPDATE_TIMEOUT_MILLIS,
                            TimeUnit.MILLISECONDS);
                },
                this::failPendingTermination);
    }

    private void handleExerciseUpdate(PlatformUpdate update) {
        for (MeasurementSample<?> sample : update.samples) {
            listener.onSample(sample);
        }

        if (update.state == PlatformExerciseState.ENDED) {
            PendingTermination pending = pendingTermination;
            activeActivityId = 0;
            clearOwnedActivity();
            publish(
                    HealthServicesExerciseSnapshot.State.READY,
                    HealthServicesExerciseSnapshot.Ownership.NONE,
                    Collections.emptySet(),
                    "Health Services exercise ended: "
                            + update.endReason);
            if (pending != null) {
                cancelTerminationTimeout();
                pendingTermination = null;
                pending.callback.onComplete();
            }
        } else if (update.state == PlatformExerciseState.PAUSED) {
            publish(
                    HealthServicesExerciseSnapshot.State.PAUSED,
                    HealthServicesExerciseSnapshot.Ownership.OWNED_BY_THIS_APP,
                    configuredMetrics,
                    "");
        } else if (update.state == PlatformExerciseState.ENDING) {
            publish(
                    HealthServicesExerciseSnapshot.State.ENDING,
                    HealthServicesExerciseSnapshot.Ownership.OWNED_BY_THIS_APP,
                    configuredMetrics,
                    "");
        } else {
            publish(
                    HealthServicesExerciseSnapshot.State.ACTIVE,
                    HealthServicesExerciseSnapshot.Ownership.OWNED_BY_THIS_APP,
                    configuredMetrics,
                    "");
        }
    }

    private void handleAvailability(DataType<?, ?> dataType, Availability availability) {
        ExerciseMetric metric = HealthServicesMetricMapper.metric(dataType);
        if (metric == null) {
            return;
        }
        MeasurementCapability previous = capabilities.get(metric);
        if (previous != null) {
            capabilities.put(metric, new MeasurementCapability(
                    metric,
                    previous.getExerciseSupport(),
                    previous.getPermissionState(),
                    previous.getRawHardwareState(),
                    previous.getVendorState(),
                    liveAvailability(availability),
                    previous.getActiveProvenance()));
            publish(
                    snapshot.getState(),
                    snapshot.getOwnership(),
                    snapshot.getActiveHealthServicesMetrics(),
                    snapshot.getDetail());
        }
        if (metric == ExerciseMetric.LOCATION) {
            listener.onRouteStatus(routeStatus(availability));
        }
    }

    private void failPendingTermination(Throwable error) {
        cancelTerminationTimeout();
        PendingTermination pending = pendingTermination;
        pendingTermination = null;
        if (pending != null) {
            pending.callback.onFailure(error);
        }
    }

    private boolean persistOwnedActivity(int activityId) {
        try {
            sessionJournal.markActive(activityId);
            return true;
        } catch (RuntimeException error) {
            listener.onFailure(
                    "Health Services ownership state is not durable",
                    error);
            return false;
        }
    }

    private void abandonUndurableOwnedSession() {
        activeActivityId = 0;
        publish(
                HealthServicesExerciseSnapshot.State.ERROR,
                HealthServicesExerciseSnapshot.Ownership.OWNED_BY_THIS_APP,
                Collections.emptySet(),
                "Health Services ownership was not durable; falling back to raw acquisition");
        listen(
                client.endExerciseAsync(),
                ignored -> publish(
                        HealthServicesExerciseSnapshot.State.RAW_FALLBACK,
                        HealthServicesExerciseSnapshot.Ownership.NONE,
                        Collections.emptySet(),
                        "Undurable Health Services exercise was ended"),
                error -> listener.onFailure(
                        "Undurable Health Services exercise could not be ended",
                        error));
    }

    private void clearOwnedActivity() {
        try {
            sessionJournal.clear();
        } catch (RuntimeException error) {
            listener.onFailure(
                    "Health Services ownership state could not be cleared",
                    error);
        }
    }

    private void cancelTerminationTimeout() {
        if (terminationTimeout != null) {
            terminationTimeout.cancel(false);
            terminationTimeout = null;
        }
    }

    private void rebuildCapabilities(Set<DataType<?, ?>> supportedDataTypes) {
        capabilities.clear();
        for (ExerciseMetric metric : ExerciseMetric.values()) {
            DataType<?, ?> dataType = HealthServicesMetricMapper.dataType(metric);
            MeasurementCapability.Support support;
            if (dataType == null && !metric.isCoreMetric()) {
                support = MeasurementCapability.Support.UNKNOWN;
            } else if (dataType != null && supportedDataTypes.contains(dataType)) {
                support = MeasurementCapability.Support.SUPPORTED;
            } else {
                support = MeasurementCapability.Support.UNSUPPORTED;
            }
            capabilities.put(metric, new MeasurementCapability(
                    metric,
                    support,
                    permissionState(metric),
                    rawHardwareStates.getOrDefault(
                            metric,
                            MeasurementCapability.RawHardwareState.UNKNOWN),
                    metric.isCoreMetric()
                            ? MeasurementCapability.VendorState.UNAVAILABLE
                            : MeasurementCapability.VendorState.UNKNOWN,
                    MeasurementCapability.LiveAvailability.UNKNOWN,
                    Collections.emptySet()));
        }
    }

    private Set<ExerciseMetric> selectConfiguredMetrics() {
        EnumSet<ExerciseMetric> selected = EnumSet.noneOf(ExerciseMetric.class);
        for (MeasurementCapability capability : capabilities.values()) {
            if (capability.getExerciseSupport()
                    == MeasurementCapability.Support.SUPPORTED
                    && (capability.getPermissionState()
                    == MeasurementCapability.PermissionState.GRANTED
                    || capability.getPermissionState()
                    == MeasurementCapability.PermissionState.NOT_REQUIRED)
                    && (capability.getMetric().getPermissionDomain()
                    != ExerciseMetric.PermissionDomain.FINE_LOCATION
                    || locationEnabled.getAsBoolean())) {
                selected.add(capability.getMetric());
            }
        }
        preferTotal(selected, ExerciseMetric.STEPS, ExerciseMetric.STEPS_TOTAL);
        preferTotal(selected, ExerciseMetric.DISTANCE, ExerciseMetric.DISTANCE_TOTAL);
        preferTotal(
                selected,
                ExerciseMetric.ELEVATION_GAIN,
                ExerciseMetric.ELEVATION_GAIN_TOTAL);
        preferTotal(
                selected,
                ExerciseMetric.ELEVATION_LOSS,
                ExerciseMetric.ELEVATION_LOSS_TOTAL);
        preferTotal(selected, ExerciseMetric.CALORIES, ExerciseMetric.CALORIES_TOTAL);
        preferTotal(selected, ExerciseMetric.FLOORS, ExerciseMetric.FLOORS_TOTAL);
        preferTotal(
                selected,
                ExerciseMetric.SWIMMING_STROKES,
                ExerciseMetric.SWIMMING_STROKES_TOTAL);
        preferTotal(
                selected,
                ExerciseMetric.SWIMMING_LAP_COUNT,
                ExerciseMetric.SWIMMING_LAP_COUNT_TOTAL);
        preferTotal(selected, ExerciseMetric.REP_COUNT, ExerciseMetric.REP_COUNT_TOTAL);
        return selected;
    }

    private void reconfigureMetrics() {
        rebuildCapabilities(configuredSupportedDataTypes);
        configuredMetrics.clear();
        configuredMetrics.addAll(selectConfiguredMetrics());
        configuredDataTypes.clear();
        for (ExerciseMetric metric : configuredMetrics) {
            DataType<?, ?> dataType = HealthServicesMetricMapper.dataType(metric);
            if (dataType != null) {
                configuredDataTypes.add(dataType);
            }
        }
        healthServicesUsable = !configuredDataTypes.isEmpty();
    }

    private void configureForActivity(ActivityType activityType) {
        configuredActivityType =
                RecordingActivityTypePolicy.requireSupported(activityType);
        configuredExerciseType = healthServicesType(configuredActivityType);
        ExerciseTypeCapabilities typeCapabilities =
                discoveredExerciseCapabilities == null
                        ? null
                        : discoveredExerciseCapabilities
                        .getTypeToCapabilities()
                        .get(configuredExerciseType);
        configuredSupportedDataTypes = typeCapabilities == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new HashSet<>(
                        typeCapabilities.getSupportedDataTypes()));
        reconfigureMetrics();
    }

    private static ExerciseType healthServicesType(ActivityType activityType) {
        switch (activityType) {
            case ACTIVITY_TYPE_RUNNING:
                return ExerciseType.RUNNING;
            case ACTIVITY_TYPE_WALKING:
                return ExerciseType.WALKING;
            case ACTIVITY_TYPE_CYCLING:
                return ExerciseType.BIKING;
            case ACTIVITY_TYPE_HIKING:
                return ExerciseType.HIKING;
            case ACTIVITY_TYPE_SWIMMING:
                return ExerciseType.SWIMMING_POOL;
            case ACTIVITY_TYPE_STRENGTH:
                return ExerciseType.STRENGTH_TRAINING;
            case ACTIVITY_TYPE_OTHER:
                return ExerciseType.WORKOUT;
            default:
                throw new IllegalArgumentException(
                        "Unsupported Health Services activity type " + activityType);
        }
    }

    private void publish(
            HealthServicesExerciseSnapshot.State state,
            HealthServicesExerciseSnapshot.Ownership ownership,
            Set<ExerciseMetric> activeMetrics,
            String detail) {
        Set<ExerciseMetric> active = activeMetrics.isEmpty()
                ? Collections.emptySet()
                : EnumSet.copyOf(activeMetrics);
        EnumMap<ExerciseMetric, MeasurementCapability> publishedCapabilities =
                new EnumMap<>(ExerciseMetric.class);
        for (Map.Entry<ExerciseMetric, MeasurementCapability> entry
                : capabilities.entrySet()) {
            MeasurementCapability value = entry.getValue();
            Set<MeasurementProvenance> provenance =
                    active.contains(entry.getKey())
                            ? EnumSet.of(MeasurementProvenance.HEALTH_SERVICES)
                            : Collections.emptySet();
            publishedCapabilities.put(entry.getKey(), new MeasurementCapability(
                    entry.getKey(),
                    value.getExerciseSupport(),
                    value.getPermissionState(),
                    value.getRawHardwareState(),
                    value.getVendorState(),
                    value.getLiveAvailability(),
                    provenance));
        }
        snapshot = new HealthServicesExerciseSnapshot(
                state,
                ownership,
                publishedCapabilities,
                active,
                capabilityDimensions,
                detail);
        listener.onSnapshot(snapshot);
    }

    private MeasurementCapability.PermissionState permissionState(ExerciseMetric metric) {
        switch (metric.getPermissionDomain()) {
            case NONE:
                return MeasurementCapability.PermissionState.NOT_REQUIRED;
            case ACTIVITY_RECOGNITION:
                return Build.VERSION.SDK_INT < 29
                        || permissionChecker.isGranted(
                                RecordingPermissionPolicy.ACTIVITY_RECOGNITION)
                        ? MeasurementCapability.PermissionState.GRANTED
                        : MeasurementCapability.PermissionState.DENIED;
            case HEART_RATE:
                return permissionChecker.isGranted(
                        RecordingPermissionPolicy.activeHeartRatePermission(
                                Build.VERSION.SDK_INT))
                        ? MeasurementCapability.PermissionState.GRANTED
                        : MeasurementCapability.PermissionState.DENIED;
            case FINE_LOCATION:
                return permissionChecker.isGranted(
                        RecordingPermissionPolicy.ACCESS_FINE_LOCATION)
                        ? MeasurementCapability.PermissionState.GRANTED
                        : MeasurementCapability.PermissionState.DENIED;
            case DEVICE_DEFINED:
            case VENDOR:
            default:
                return MeasurementCapability.PermissionState.UNKNOWN;
        }
    }

    private void failInitialization(String message, Throwable error) {
        initialized = true;
        healthServicesUsable = false;
        publish(
                HealthServicesExerciseSnapshot.State.RAW_FALLBACK,
                HealthServicesExerciseSnapshot.Ownership.UNKNOWN,
                Collections.emptySet(),
                describe(error));
        listener.onFailure(message, error);
        PendingEndRequest endRequest = pendingEndRequest;
        pendingEndRequest = null;
        if (endRequest != null) {
            endRequest.callback.onFailure(error);
        }
        consumePendingStart();
    }

    private static HealthServicesExerciseSnapshot.Ownership ownership(ExerciseInfo info) {
        if (info == null) {
            return HealthServicesExerciseSnapshot.Ownership.UNKNOWN;
        }
        switch (info.getExerciseTrackedStatus()) {
            case ExerciseTrackedStatus.NO_EXERCISE_IN_PROGRESS:
                return HealthServicesExerciseSnapshot.Ownership.NONE;
            case ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS:
                return HealthServicesExerciseSnapshot.Ownership.OWNED_BY_THIS_APP;
            case ExerciseTrackedStatus.OTHER_APP_IN_PROGRESS:
                return HealthServicesExerciseSnapshot.Ownership.OWNED_BY_OTHER_APP;
            default:
                return HealthServicesExerciseSnapshot.Ownership.UNKNOWN;
        }
    }

    private static MeasurementCapability.LiveAvailability liveAvailability(
            Availability availability) {
        if (availability instanceof LocationAvailability) {
            LocationAvailability location = (LocationAvailability) availability;
            if (location.equals(LocationAvailability.ACQUIRING)) {
                return MeasurementCapability.LiveAvailability.ACQUIRING;
            }
            if (location.equals(LocationAvailability.NO_GNSS)) {
                return MeasurementCapability.LiveAvailability.NO_GNSS;
            }
            if (location.equals(LocationAvailability.ACQUIRED_TETHERED)
                    || location.equals(LocationAvailability.ACQUIRED_UNTETHERED)) {
                return MeasurementCapability.LiveAvailability.AVAILABLE;
            }
            if (location.equals(LocationAvailability.UNAVAILABLE)) {
                return MeasurementCapability.LiveAvailability.UNAVAILABLE;
            }
            return MeasurementCapability.LiveAvailability.UNKNOWN;
        }
        if (availability instanceof DataTypeAvailability) {
            DataTypeAvailability data = (DataTypeAvailability) availability;
            if (data.equals(DataTypeAvailability.AVAILABLE)) {
                return MeasurementCapability.LiveAvailability.AVAILABLE;
            }
            if (data.equals(DataTypeAvailability.ACQUIRING)) {
                return MeasurementCapability.LiveAvailability.ACQUIRING;
            }
            if (data.equals(DataTypeAvailability.UNAVAILABLE_DEVICE_OFF_BODY)) {
                return MeasurementCapability.LiveAvailability.UNAVAILABLE_DEVICE_OFF_BODY;
            }
            if (data.equals(DataTypeAvailability.UNAVAILABLE)) {
                return MeasurementCapability.LiveAvailability.UNAVAILABLE;
            }
        }
        return MeasurementCapability.LiveAvailability.UNKNOWN;
    }

    private static RouteStatus routeStatus(Availability availability) {
        if (!(availability instanceof LocationAvailability)) {
            return RouteStatus.UNKNOWN;
        }
        LocationAvailability location = (LocationAvailability) availability;
        if (location.equals(LocationAvailability.ACQUIRING)) {
            return RouteStatus.ACQUIRING;
        }
        if (location.equals(LocationAvailability.NO_GNSS)) {
            return RouteStatus.NO_GNSS;
        }
        if (location.equals(LocationAvailability.ACQUIRED_TETHERED)
                || location.equals(LocationAvailability.ACQUIRED_UNTETHERED)) {
            return RouteStatus.AVAILABLE;
        }
        if (location.equals(LocationAvailability.UNAVAILABLE)) {
            return RouteStatus.UNAVAILABLE;
        }
        return RouteStatus.UNKNOWN;
    }

    private static void preferTotal(
            Set<ExerciseMetric> selected,
            ExerciseMetric delta,
            ExerciseMetric total) {
        if (selected.contains(total)) {
            selected.remove(delta);
        }
    }

    private <T> void listen(
            ListenableFuture<T> future,
            Success<T> success,
            Failure failure) {
        future.addListener(() -> {
            if (closed) {
                return;
            }
            try {
                success.accept(future.get());
            } catch (ExecutionException error) {
                failure.accept(error.getCause() == null ? error : error.getCause());
            } catch (Exception error) {
                failure.accept(error);
            }
        }, worker);
    }

    private void execute(Runnable command) {
        if (closed) {
            return;
        }
        try {
            worker.execute(() -> {
                if (!closed) {
                    command.run();
                }
            });
        } catch (RejectedExecutionException ignored) {
            // The manager is already closing.
        }
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "";
        }
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }

    private interface Success<T> {
        void accept(T value);
    }

    private interface Failure {
        void accept(Throwable error);
    }

    private static final class PendingStart {
        private final int activityId;
        private final ActivityType activityType;
        private final boolean recovery;
        private final boolean pausedRecovery;

        private PendingStart(
                int activityId,
                ActivityType activityType,
                boolean recovery,
                boolean pausedRecovery) {
            this.activityId = activityId;
            this.activityType = activityType;
            this.recovery = recovery;
            this.pausedRecovery = pausedRecovery;
        }
    }

    private static final class PendingTermination {
        private final int activityId;
        private final boolean preserveFinalData;
        private final RecordingSessionTerminator.Callback callback;

        private PendingTermination(
                int activityId,
                boolean preserveFinalData,
                RecordingSessionTerminator.Callback callback) {
            this.activityId = activityId;
            this.preserveFinalData = preserveFinalData;
            this.callback = callback;
        }
    }

    private static final class PendingEndRequest {
        private final int activityId;
        private final boolean preserveFinalData;
        private final RecordingSessionTerminator.Callback callback;

        private PendingEndRequest(
                int activityId,
                boolean preserveFinalData,
                RecordingSessionTerminator.Callback callback) {
            this.activityId = activityId;
            this.preserveFinalData = preserveFinalData;
            this.callback = callback;
        }
    }

    private static final class InMemorySessionJournal
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

    private final class ManagerUpdateListener implements PlatformUpdateListener {
        @Override
        public void onRegistered() {
        }

        @Override
        public void onRegistrationFailed(Throwable throwable) {
            execute(() -> failInitialization(
                    "Health Services listener registration failed",
                    throwable));
        }

        @Override
        public void onExerciseUpdate(PlatformUpdate update) {
            execute(() -> handleExerciseUpdate(update));
        }

        @Override
        public void onAvailabilityChanged(
                DataType<?, ?> dataType,
                Availability availability) {
            execute(() -> handleAvailability(dataType, availability));
        }
    }

    private static final class AndroidPlatformClient implements PlatformClient {
        private final ExerciseClient exerciseClient;
        private final MeasureClient measureClient;
        private final PassiveMonitoringClient passiveClient;
        private final HealthServicesDataPointMapper dataPointMapper =
                new HealthServicesDataPointMapper();
        private ExerciseUpdateCallback callback;

        private AndroidPlatformClient(HealthServicesClient client) {
            this.exerciseClient = client.getExerciseClient();
            this.measureClient = client.getMeasureClient();
            this.passiveClient = client.getPassiveMonitoringClient();
        }

        @Override
        public ListenableFuture<ExerciseCapabilities> getCapabilitiesAsync() {
            return exerciseClient.getCapabilitiesAsync();
        }

        @Override
        public ListenableFuture<MeasureCapabilities> getMeasureCapabilitiesAsync() {
            return measureClient.getCapabilitiesAsync();
        }

        @Override
        public ListenableFuture<PassiveMonitoringCapabilities>
                getPassiveCapabilitiesAsync() {
            return passiveClient.getCapabilitiesAsync();
        }

        @Override
        public ListenableFuture<ExerciseInfo> getCurrentExerciseInfoAsync() {
            return exerciseClient.getCurrentExerciseInfoAsync();
        }

        @Override
        public ListenableFuture<Void> startExerciseAsync(
                ExerciseType exerciseType,
                Set<DataType<?, ?>> dataTypes,
                boolean gpsEnabled) {
            ExerciseConfig config = ExerciseConfig.builder(exerciseType)
                    .setDataTypes(dataTypes)
                    .setIsAutoPauseAndResumeEnabled(false)
                    .setIsGpsEnabled(gpsEnabled)
                    .build();
            return exerciseClient.startExerciseAsync(config);
        }

        @Override
        public ListenableFuture<Void> pauseExerciseAsync() {
            return exerciseClient.pauseExerciseAsync();
        }

        @Override
        public ListenableFuture<Void> resumeExerciseAsync() {
            return exerciseClient.resumeExerciseAsync();
        }

        @Override
        public ListenableFuture<Void> flushAsync() {
            return exerciseClient.flushAsync();
        }

        @Override
        public ListenableFuture<Void> endExerciseAsync() {
            return exerciseClient.endExerciseAsync();
        }

        @Override
        public void setUpdateCallback(
                Executor executor,
                PlatformUpdateListener listener) {
            callback = new ExerciseUpdateCallback() {
                @Override
                public void onRegistered() {
                    listener.onRegistered();
                }

                @Override
                public void onRegistrationFailed(Throwable throwable) {
                    listener.onRegistrationFailed(throwable);
                }

                @Override
                public void onExerciseUpdateReceived(ExerciseUpdate update) {
                    ExerciseState state = update.getExerciseStateInfo().getState();
                    PlatformExerciseState platformState;
                    if (state.isEnded()) {
                        platformState = PlatformExerciseState.ENDED;
                    } else if (state.isPaused()) {
                        platformState = PlatformExerciseState.PAUSED;
                    } else if (state.isEnding()) {
                        platformState = PlatformExerciseState.ENDING;
                    } else {
                        platformState = PlatformExerciseState.ACTIVE;
                    }
                    listener.onExerciseUpdate(new PlatformUpdate(
                            dataPointMapper.map(update),
                            platformState,
                            update.getExerciseStateInfo().getEndReason()));
                }

                @Override
                public void onLapSummaryReceived(ExerciseLapSummary lapSummary) {
                }

                @Override
                public void onAvailabilityChanged(
                        DataType<?, ?> dataType,
                        Availability availability) {
                    listener.onAvailabilityChanged(dataType, availability);
                }
            };
            exerciseClient.setUpdateCallback(executor, callback);
        }

        @Override
        public ListenableFuture<Void> clearUpdateCallbackAsync() {
            return exerciseClient.clearUpdateCallbackAsync(callback);
        }
    }
}
