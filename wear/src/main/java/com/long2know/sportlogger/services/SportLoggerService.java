package com.long2know.sportlogger.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.long2know.sportlogger.MainActivity;
import com.long2know.sportlogger.R;
import com.long2know.sportlogger.acquisition.LegacyMeasurementBridge;
import com.long2know.sportlogger.acquisition.ExerciseMetric;
import com.long2know.sportlogger.acquisition.MeasurementSample;
import com.long2know.sportlogger.acquisition.RecordingPermissionPolicy;
import com.long2know.sportlogger.acquisition.RouteStatus;
import com.long2know.sportlogger.acquisition.healthservices.HealthServicesExerciseManager;
import com.long2know.sportlogger.acquisition.healthservices.HealthServicesExerciseSnapshot;
import com.long2know.sportlogger.measurements.AndroidLocationSource;
import com.long2know.sportlogger.measurements.AndroidPressureSource;
import com.long2know.sportlogger.measurements.AndroidSensorSource;
import com.long2know.sportlogger.measurements.MeasurementEvent;
import com.long2know.sportlogger.measurements.ResourceFailure;
import com.long2know.sportlogger.measurements.ResourceOwner;
import com.long2know.sportlogger.persistence.watch.LegacyWatchMigrator;
import com.long2know.sportlogger.persistence.watch.WatchActivityRepository;
import com.long2know.sportlogger.recording.AndroidRecordingClock;
import com.long2know.sportlogger.recording.RecordingController;
import com.long2know.sportlogger.recording.RecordingError;
import com.long2know.sportlogger.recording.RecordingSnapshot;
import com.long2know.sportlogger.recording.RecordingState;
import com.long2know.sportlogger.recording.RecordingSessionTerminator;
import com.long2know.sportlogger.recording.SharedPreferencesRecordingJournal;
import com.long2know.sportlogger.recording.SqlRecordingStore;
import com.long2know.sportlogger.recording.WearDataLayerExporter;
import com.long2know.sportlogger.recording.canonical.CanonicalRecordingSession;
import com.long2know.utilities.models.SharedData;

import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class SportLoggerService extends Service {
    private static final String TAG = "SportLoggerService";
    private static final String CHANNEL_ID = "sport_logger_recording";
    private static final int NOTIFICATION_ID = 1;
    private static final long MEASUREMENT_UI_DELAY_MILLIS = 200L;
    private static final long IDLE_STOP_DELAY_MILLIS = 2_000L;

    private final IBinder binder = new LocalBinder();
    private final ServiceClientRegistry<ISportLoggerServiceClient> clients =
            new ServiceClientRegistry<>();
    private final AtomicBoolean measurementDispatchScheduled = new AtomicBoolean();
    private final AtomicReference<RouteStatus> routeStatus =
            new AtomicReference<>(RouteStatus.UNKNOWN);
    private final Runnable idleStop = this::stopIfIdleNow;

    private Handler mainHandler;
    private NotificationManager notificationManager;
    private RecordingController recordingController;
    private ResourceOwner sensorOwner;
    private ResourceOwner locationOwner;
    private ResourceOwner pressureOwner;
    private LegacyMeasurementBridge measurementBridge;
    private HealthServicesExerciseManager healthServicesManager;
    private CanonicalRecordingSession canonicalRecordingSession;
    private ForegroundServiceController foregroundController;
    private boolean foreground;
    private boolean locationStatusErrorReported;
    private RecordingState lastAcquisitionState;
    private int lastAcquisitionActivityId;
    private String lastAcquisitionError = "";

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        notificationManager = getSystemService(NotificationManager.class);
        createNotificationChannel();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            foregroundController = createForegroundController();
        }

        ScheduledThreadPoolExecutor recordingWorker =
                new ScheduledThreadPoolExecutor(1, namedThreadFactory("recording"));
        recordingWorker.setRemoveOnCancelPolicy(true);
        recordingWorker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        Executor mainExecutor = command -> mainHandler.post(command);

        ResourceOwner.Listener measurementListener = new ResourceOwner.Listener() {
            @Override
            public void onEvent(MeasurementEvent event) {
                applyMeasurement(event);
            }

            @Override
            public void onFailure(ResourceFailure failure) {
                reportResourceFailure(failure);
            }
        };
        sensorOwner = new ResourceOwner(
                "sensors",
                new AndroidSensorSource(this),
                newMeasurementWorker("sensors"),
                measurementListener,
                SportLoggerService::retryDelayMillis);
        locationOwner = new ResourceOwner(
                "location",
                new AndroidLocationSource(this),
                newMeasurementWorker("location"),
                measurementListener,
                SportLoggerService::retryDelayMillis);
        pressureOwner = new ResourceOwner(
                "pressure",
                new AndroidPressureSource(this),
                newMeasurementWorker("pressure"),
                measurementListener,
                SportLoggerService::retryDelayMillis);
        try {
            canonicalRecordingSession = new CanonicalRecordingSession(
                    this,
                    this::reportCanonicalFailure);
            measurementBridge = new LegacyMeasurementBridge(
                    canonicalRecordingSession::onAcceptedSample);
            healthServicesManager = new HealthServicesExerciseManager(
                    this,
                    newMeasurementWorker("health-services"),
                    this::isPermissionGranted,
                    this::isLocationEnabled,
                    new HealthServicesExerciseManager.Listener() {
                        @Override
                        public void onSnapshot(
                                HealthServicesExerciseSnapshot snapshot) {
                            canonicalRecordingSession.updateCapabilities(
                                    snapshot.getCapabilities());
                            mainHandler.post(() ->
                                    handleHealthServicesSnapshot(snapshot));
                        }

                        @Override
                        public void onSample(MeasurementSample<?> sample) {
                            applyHealthServicesSample(sample);
                        }

                        @Override
                        public void onRouteStatus(RouteStatus status) {
                            updateRouteStatus(status);
                        }

                        @Override
                        public void onFailure(String message, Throwable error) {
                            reportAcquisitionFailure(message, error);
                        }
                    });
            recordingController = new RecordingController(
                    new SharedPreferencesRecordingJournal(this),
                    new SqlRecordingStore(this),
                    new WearDataLayerExporter(
                            this,
                            getString(R.string.wear_path),
                            recordingWorker),
                    this::terminateRecordingSession,
                    recordingWorker,
                    mainExecutor,
                    new AndroidRecordingClock(this),
                    this::handleRecordingSnapshot,
                    SportLoggerService::retryDelayMillis);
        } catch (Exception error) {
            Log.e(TAG, "Could not initialize recording controller", error);
            stopSelf();
            return;
        }

        recordingWorker.execute(this::migrateLegacyWatchData);
        healthServicesManager.initializeAsync();
        recordingController.recover();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        scheduleStopIfIdle();
        return false;
    }

    @Override
    public void onDestroy() {
        if (sensorOwner != null) {
            sensorOwner.stopAsync();
        }
        if (locationOwner != null) {
            locationOwner.stopAsync();
        }
        if (pressureOwner != null) {
            pressureOwner.stopAsync();
        }
        if (healthServicesManager != null) {
            healthServicesManager.closeAsync();
        }
        if (canonicalRecordingSession != null) {
            canonicalRecordingSession.close();
        }
        if (recordingController != null) {
            recordingController.closeAsync();
        }
        measurementDispatchScheduled.set(false);
        mainHandler.removeCallbacksAndMessages(null);
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && foregroundController != null) {
            foregroundController.clear();
        }
        super.onDestroy();
    }

    public void registerClient(ISportLoggerServiceClient client) {
        mainHandler.removeCallbacks(idleStop);
        clients.register(client);
        if (recordingController != null) {
            client.onRecordingStateChanged(recordingController.getSnapshot());
        }
        if (healthServicesManager != null) {
            client.onAcquisitionStateChanged(healthServicesManager.getSnapshot());
        }
        client.onRouteStatusChanged(routeStatus.get());
        client.onLoggerUpdate(SharedData.getInstance());
    }

    public void unregisterClient(ISportLoggerServiceClient client) {
        clients.unregister(client);
        scheduleStopIfIdle();
    }

    public boolean startNewActivity() {
        if (healthServicesManager != null
                && healthServicesManager.getSnapshot().getOwnership()
                == HealthServicesExerciseSnapshot.Ownership.OWNED_BY_OTHER_APP) {
            reportError(
                    RecordingError.Code.ACQUISITION.name(),
                    "Another app owns the active Health Services exercise");
            return false;
        }
        if (recordingController == null || !ensureForeground()) {
            return false;
        }
        mainHandler.removeCallbacks(idleStop);
        boolean accepted = recordingController.start();
        if (!accepted) {
            reportError(
                    RecordingError.Code.COMMAND_REJECTED.name(),
                    "Recording worker is unavailable");
        }
        return accepted;
    }

    public boolean pauseActivity() {
        return submit(recordingController == null ? false : recordingController.pause());
    }

    public boolean resumeActivity() {
        if (healthServicesManager != null
                && healthServicesManager.getSnapshot().getOwnership()
                == HealthServicesExerciseSnapshot.Ownership.OWNED_BY_OTHER_APP) {
            reportError(
                    RecordingError.Code.ACQUISITION.name(),
                    "Another app owns the active Health Services exercise");
            return false;
        }
        if (!ensureForeground()) {
            return false;
        }
        return submit(recordingController != null && recordingController.resume());
    }

    public boolean stopActivity() {
        return submit(recordingController != null && recordingController.stop());
    }

    public boolean discardActivity() {
        return submit(recordingController != null && recordingController.discard());
    }

    public void refreshAcquisitionState() {
        RecordingPermissionPolicy.RoutePermission permission =
                RecordingPermissionPolicy.routePermission(this::isPermissionGranted);
        if (permission == RecordingPermissionPolicy.RoutePermission.DENIED) {
            updateRouteStatus(RouteStatus.PERMISSION_DENIED);
        } else if (permission == RecordingPermissionPolicy.RoutePermission.APPROXIMATE_ONLY) {
            updateRouteStatus(RouteStatus.APPROXIMATE_ONLY);
        } else if (!isLocationEnabled()) {
            updateRouteStatus(RouteStatus.LOCATION_DISABLED);
        }

        if (recordingController != null
                && recordingController.getSnapshot().getState() == RecordingState.RECORDING) {
            if (sensorOwner != null) {
                sensorOwner.refreshAsync();
            }
            if (locationOwner != null) {
                locationOwner.refreshAsync();
            }
        }
        if (recordingController != null
                && recordingController.getSnapshot().isActive()) {
            ensureForeground();
        }
    }

    private boolean submit(boolean accepted) {
        if (!accepted) {
            reportError(
                    RecordingError.Code.COMMAND_REJECTED.name(),
                    "Recording worker is unavailable");
        }
        return accepted;
    }

    private void handleRecordingSnapshot(RecordingSnapshot snapshot) {
        RecordingState state = snapshot.getState();
        SharedData sharedData = SharedData.getInstance();
        if (state == RecordingState.RECORDING
                && snapshot.getActivityId() > 0
                && sharedData.getActivityId() != snapshot.getActivityId()) {
            sharedData.resetMeasurements();
            measurementBridge.beginSession();
        }
        if (canonicalRecordingSession != null) {
            canonicalRecordingSession.onRecordingSnapshot(snapshot);
        }
        synchronizeAcquisition(snapshot);
        boolean recording = state == RecordingState.STARTING
                || state == RecordingState.RECORDING
                || state == RecordingState.PAUSED
                || state == RecordingState.INTERRUPTED
                || state == RecordingState.STOPPING
                || state == RecordingState.DISCARDING
                || state == RecordingState.EXPORT_PENDING;
        boolean paused = state == RecordingState.PAUSED
                || state == RecordingState.INTERRUPTED
                || state == RecordingState.STOPPING
                || state == RecordingState.DISCARDING
                || state == RecordingState.EXPORT_PENDING;
        sharedData.setRecordingState(
                recording,
                paused,
                snapshot.getActivityId(),
                formatDuration(snapshot.getDurationMillis()));

        if (snapshot.isActive() && state != RecordingState.STARTING) {
            mainHandler.removeCallbacks(idleStop);
            ensureForeground();
        } else if (state == RecordingState.IDLE
                || state == RecordingState.EXPORT_QUEUED
                || state == RecordingState.INTERRUPTED) {
            if (foreground) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                foreground = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                        && foregroundController != null) {
                    foregroundController.clear();
                }
            }
            scheduleStopIfIdle();
        }

        clients.dispatch(client -> client.onRecordingStateChanged(snapshot));
        if (snapshot.getError() != null) {
            reportError(
                    snapshot.getError().getCode().name(),
                    snapshot.getError().getMessage());
        }
    }

    private void applyMeasurement(MeasurementEvent event) {
        if (event.getKind() == MeasurementEvent.Kind.ROUTE_STATUS) {
            updateRouteStatus(event.getRouteStatus());
            return;
        }
        if (measurementBridge.applyRaw(event)) {
            scheduleMeasurementDispatch();
        }
    }

    private void applyHealthServicesSample(MeasurementSample<?> sample) {
        if (measurementBridge.applyHealthServices(sample)) {
            scheduleMeasurementDispatch();
        }
    }

    private void scheduleMeasurementDispatch() {
        if (!measurementDispatchScheduled.compareAndSet(false, true)) {
            return;
        }
        mainHandler.postDelayed(() -> {
            measurementDispatchScheduled.set(false);
            clients.dispatch(client -> client.onLoggerUpdate(SharedData.getInstance()));
        }, MEASUREMENT_UI_DELAY_MILLIS);
    }

    private void reportResourceFailure(ResourceFailure failure) {
        Log.w(TAG, failure.getResource() + ": " + failure.getMessage());
        if (failure.getResource().contains("location")) {
            RouteStatus currentStatus = routeStatus.get();
            if (failure.getCode() == ResourceFailure.Code.PERMISSION_DENIED
                    && currentStatus != RouteStatus.APPROXIMATE_ONLY) {
                updateRouteStatus(RouteStatus.PERMISSION_DENIED);
            } else if (currentStatus == RouteStatus.UNKNOWN
                    || currentStatus == RouteStatus.AVAILABLE
                    || currentStatus == RouteStatus.ACQUIRING) {
                updateRouteStatus(RouteStatus.UNAVAILABLE);
            }
            return;
        }
        if (failure.getCode() == ResourceFailure.Code.UNAVAILABLE
                || failure.getCode() == ResourceFailure.Code.PERMISSION_DENIED) {
            return;
        }
        mainHandler.post(() -> reportError(
                failure.getCode().name(),
                failure.getResource() + ": " + failure.getMessage()));
    }

    private void reportError(String code, String message) {
        Log.e(TAG, code + ": " + message);
        clients.dispatch(client -> client.onLoggerError(code, message));
    }

    private boolean ensureForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                boolean promoted = foregroundController.ensure(
                        hasLocationPermission(),
                        isLocationEnabled(),
                        hasHealthPermission());
                foreground = foregroundController.isForeground();
                return promoted;
            }
            if (foreground) {
                return true;
            }
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && hasLocationPermission()
                    && isLocationEnabled()) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && hasHealthPermission()) {
                startForeground(NOTIFICATION_ID, notification);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            foreground = true;
            return true;
        } catch (RuntimeException error) {
            reportError(
                    RecordingError.Code.FOREGROUND.name(),
                    "Could not start recording foreground service: " + error.getMessage());
            return false;
        }
    }

    @RequiresApi(34)
    private ForegroundServiceController createForegroundController() {
        return new ForegroundServiceController(
                types -> startForeground(NOTIFICATION_ID, buildNotification(), types),
                (message, error) -> reportError(
                        RecordingError.Code.FOREGROUND.name(),
                        describe(message, error)));
    }

    private boolean isLocationEnabled() {
        LocationManager locationManager = getSystemService(LocationManager.class);
        if (locationManager == null) {
            reportLocationStatusError(
                    "Location service is unavailable; continuing without location foreground type");
            return false;
        }
        try {
            boolean enabled;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                enabled = locationManager.isLocationEnabled();
            } else {
                enabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                        || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            }
            locationStatusErrorReported = false;
            return enabled;
        } catch (RuntimeException error) {
            reportLocationStatusError(
                    describe("Could not determine whether location is enabled", error));
            return false;
        }
    }

    private void reportLocationStatusError(String message) {
        if (locationStatusErrorReported) {
            return;
        }
        locationStatusErrorReported = true;
        Log.w(TAG, message);
        updateRouteStatus(RouteStatus.UNAVAILABLE);
    }

    private boolean hasLocationPermission() {
        return RecordingPermissionPolicy.routePermission(this::isPermissionGranted)
                == RecordingPermissionPolicy.RoutePermission.PRECISE;
    }

    private boolean hasHealthPermission() {
        return RecordingPermissionPolicy.hasHealthForegroundPrerequisite(
                Build.VERSION.SDK_INT,
                this::isPermissionGranted);
    }

    private boolean isPermissionGranted(String permission) {
        return ContextCompat.checkSelfPermission(this, permission)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private Notification buildNotification() {
        Intent contentIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.recording_notification_title))
                .setContentText(getString(R.string.recording_notification_text))
                .setSmallIcon(R.drawable.ic_play_circle_outline_black_24dp)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingIntent)
                .setCategory(NotificationCompat.CATEGORY_WORKOUT)
                .build();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.recording_notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        notificationManager.createNotificationChannel(channel);
    }

    private void scheduleStopIfIdle() {
        mainHandler.removeCallbacks(idleStop);
        mainHandler.postDelayed(idleStop, IDLE_STOP_DELAY_MILLIS);
    }

    private void stopIfIdleNow() {
        if (recordingController == null) {
            stopSelf();
            return;
        }
        RecordingState state = recordingController.getSnapshot().getState();
        if ((state == RecordingState.IDLE
                || state == RecordingState.EXPORT_QUEUED
                || state == RecordingState.INTERRUPTED)
                && clients.isEmpty()) {
            stopSelf();
        }
    }

    private void synchronizeAcquisition(RecordingSnapshot snapshot) {
        if (sensorOwner == null
                || locationOwner == null
                || healthServicesManager == null) {
            return;
        }
        RecordingState state = snapshot.getState();
        int activityId = snapshot.getActivityId();
        RecordingState previousState = lastAcquisitionState;
        int previousActivityId = lastAcquisitionActivityId;

        if (state == RecordingState.RECORDING) {
            if (previousState == RecordingState.PAUSED
                    || previousState == RecordingState.INTERRUPTED) {
                healthServicesManager.resumeAsync();
            } else if (previousState != RecordingState.RECORDING
                    || previousActivityId != activityId) {
                healthServicesManager.startOrRecoverAsync(
                        activityId,
                        previousState == null,
                        false);
            }
        } else if (state == RecordingState.PAUSED) {
            if (previousState == null) {
                healthServicesManager.startOrRecoverAsync(
                        activityId,
                        true,
                        true);
            } else if (previousState == RecordingState.RECORDING) {
                healthServicesManager.pauseAsync();
            }
        }
        lastAcquisitionState = state;
        lastAcquisitionActivityId = activityId;
        updateRawFallbackOwners(state);
    }

    private void handleHealthServicesSnapshot(
            HealthServicesExerciseSnapshot snapshot) {
        measurementBridge.configure(snapshot.getActiveHealthServicesMetrics());
        clients.dispatch(client -> client.onAcquisitionStateChanged(snapshot));
        if ((snapshot.getState() == HealthServicesExerciseSnapshot.State.STARTING
                || snapshot.getState() == HealthServicesExerciseSnapshot.State.ACTIVE)
                && snapshot.getActiveHealthServicesMetrics()
                .contains(ExerciseMetric.LOCATION)
                && routeStatus.get() != RouteStatus.AVAILABLE) {
            updateRouteStatus(RouteStatus.ACQUIRING);
        }
        if (snapshot.getState() == HealthServicesExerciseSnapshot.State.ACTIVE
                || snapshot.getState() == HealthServicesExerciseSnapshot.State.READY) {
            lastAcquisitionError = "";
        }
        RecordingState state = recordingController == null
                ? RecordingState.IDLE
                : recordingController.getSnapshot().getState();
        updateRawFallbackOwners(state);
    }

    private void updateRawFallbackOwners(RecordingState state) {
        if (sensorOwner == null || locationOwner == null || pressureOwner == null) {
            return;
        }
        if (state != RecordingState.RECORDING) {
            sensorOwner.suspendAsync();
            locationOwner.suspendAsync();
            pressureOwner.suspendAsync();
            return;
        }
        if (measurementBridge.needsRawSensorSource()) {
            sensorOwner.startAsync();
        } else {
            sensorOwner.suspendAsync();
        }
        if (measurementBridge.needsRawLocationSource()) {
            locationOwner.startAsync();
        } else {
            locationOwner.suspendAsync();
        }
        pressureOwner.startAsync();
    }

    private void reportAcquisitionFailure(String message, Throwable error) {
        String detail = describe(message, error);
        Log.w(TAG, detail);
        mainHandler.post(() -> {
            if (detail.equals(lastAcquisitionError)) {
                return;
            }
            lastAcquisitionError = detail;
            if (recordingController != null
                    && recordingController.getSnapshot().isActive()) {
                reportError(RecordingError.Code.ACQUISITION.name(), detail);
            }
        });
    }

    private void reportCanonicalFailure(String message, Throwable error) {
        String detail = describe(message, error);
        Log.e(TAG, detail);
        RecordingController controller = recordingController;
        if (controller != null) {
            controller.interrupt(new RecordingError(
                    RecordingError.Code.STORAGE,
                    detail));
        }
    }

    private void migrateLegacyWatchData() {
        if (!LegacyWatchMigrator.defaultLegacyDatabase(this).isFile()) {
            return;
        }
        try (WatchActivityRepository repository = WatchActivityRepository.open(this)) {
            new LegacyWatchMigrator(repository).migrateDefault(
                    this,
                    System.currentTimeMillis());
        } catch (Exception error) {
            Log.e(TAG, "Legacy watch migration failed", error);
            mainHandler.post(() -> reportError(
                    RecordingError.Code.RECOVERY.name(),
                    describe("Legacy watch migration failed", error)));
        }
    }

    private void terminateRecordingSession(
            int activityId,
            boolean preserveFinalData,
            RecordingSnapshot finalSnapshot,
            RecordingSessionTerminator.Callback callback) {
        if (canonicalRecordingSession == null || healthServicesManager == null) {
            callback.onFailure(new IllegalStateException(
                    "Recording acquisition is not initialized"));
            return;
        }
        if (!preserveFinalData) {
            healthServicesManager.endSession(
                    activityId,
                    false,
                    finalSnapshot,
                    new RecordingSessionTerminator.Callback() {
                        @Override
                        public void onComplete() {
                            canonicalRecordingSession.discard(
                                    finalSnapshot,
                                    callback);
                        }

                        @Override
                        public void onFailure(Throwable error) {
                            callback.onFailure(error);
                        }
                    });
            return;
        }
        canonicalRecordingSession.reserveCompletion(
                finalSnapshot,
                new RecordingSessionTerminator.Callback() {
                    @Override
                    public void onComplete() {
                        healthServicesManager.endSession(
                                activityId,
                                true,
                                finalSnapshot,
                                new RecordingSessionTerminator.Callback() {
                                    @Override
                                    public void onComplete() {
                                        canonicalRecordingSession.finishCompletion(
                                                finalSnapshot,
                                                callback);
                                    }

                                    @Override
                                    public void onFailure(Throwable error) {
                                        callback.onFailure(error);
                                    }
                                });
                    }

                    @Override
                    public void onFailure(Throwable error) {
                        callback.onFailure(error);
                    }
                });
    }

    private void updateRouteStatus(RouteStatus next) {
        if (next == null) {
            return;
        }
        RouteStatus previous = routeStatus.getAndSet(next);
        if (previous == next) {
            return;
        }
        mainHandler.post(() -> {
            clients.dispatch(client -> client.onRouteStatusChanged(next));
            if (recordingController != null
                    && recordingController.getSnapshot().isActive()) {
                ensureForeground();
            }
        });
    }

    private static ScheduledThreadPoolExecutor newMeasurementWorker(String name) {
        ScheduledThreadPoolExecutor worker =
                new ScheduledThreadPoolExecutor(1, namedThreadFactory(name));
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return worker;
    }

    private static ThreadFactory namedThreadFactory(String role) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "sport-logger-" + role + "-" + sequence.incrementAndGet());
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        };
    }

    private static long retryDelayMillis(int attempt) {
        int exponent = Math.min(Math.max(0, attempt - 1), 5);
        return Math.min(30_000L, 1_000L << exponent);
    }

    private static String formatDuration(long durationMillis) {
        long totalSeconds = Math.max(0L, durationMillis) / 1_000L;
        long hours = totalSeconds / 3_600L;
        long minutes = (totalSeconds / 60L) % 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    private static String describe(String message, Throwable error) {
        if (error == null) {
            return message;
        }
        String detail = error.getMessage();
        if (detail == null || detail.trim().isEmpty()) {
            detail = error.getClass().getSimpleName();
        }
        return message + ": " + detail;
    }

    public final class LocalBinder extends Binder {
        public SportLoggerService getService() {
            return SportLoggerService.this;
        }
    }
}
