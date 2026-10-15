package com.long2know.sportlogger.services;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.long2know.sportlogger.MainActivity;
import com.long2know.sportlogger.R;
import com.long2know.sportlogger.measurements.AndroidLocationSource;
import com.long2know.sportlogger.measurements.AndroidSensorSource;
import com.long2know.sportlogger.measurements.MeasurementEvent;
import com.long2know.sportlogger.measurements.MeasurementRuntime;
import com.long2know.sportlogger.measurements.ResourceFailure;
import com.long2know.sportlogger.measurements.ResourceOwner;
import com.long2know.sportlogger.recording.RecordingController;
import com.long2know.sportlogger.recording.RecordingError;
import com.long2know.sportlogger.recording.RecordingSnapshot;
import com.long2know.sportlogger.recording.RecordingState;
import com.long2know.sportlogger.recording.SharedPreferencesRecordingJournal;
import com.long2know.sportlogger.recording.SqlRecordingStore;
import com.long2know.sportlogger.recording.WearDataLayerExporter;
import com.long2know.utilities.models.SharedData;

import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final Runnable idleStop = this::stopIfIdleNow;

    private Handler mainHandler;
    private NotificationManager notificationManager;
    private RecordingController recordingController;
    private MeasurementRuntime measurementRuntime;
    private boolean foreground;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        notificationManager = getSystemService(NotificationManager.class);
        createNotificationChannel();

        ScheduledThreadPoolExecutor recordingWorker =
                new ScheduledThreadPoolExecutor(1, namedThreadFactory("recording"));
        recordingWorker.setRemoveOnCancelPolicy(true);
        recordingWorker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        Executor mainExecutor = command -> mainHandler.post(command);

        try {
            recordingController = new RecordingController(
                    new SharedPreferencesRecordingJournal(this),
                    new SqlRecordingStore(this),
                    new WearDataLayerExporter(
                            this,
                            getString(R.string.wear_path),
                            recordingWorker),
                    recordingWorker,
                    mainExecutor,
                    System::currentTimeMillis,
                    this::handleRecordingSnapshot,
                    SportLoggerService::retryDelayMillis);
        } catch (Exception error) {
            Log.e(TAG, "Could not initialize recording controller", error);
            stopSelf();
            return;
        }

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
        ResourceOwner sensorOwner = new ResourceOwner(
                "sensors",
                new AndroidSensorSource(this),
                newMeasurementWorker("sensors"),
                measurementListener,
                SportLoggerService::retryDelayMillis);
        ResourceOwner locationOwner = new ResourceOwner(
                "location",
                new AndroidLocationSource(this),
                newMeasurementWorker("location"),
                measurementListener,
                SportLoggerService::retryDelayMillis);
        measurementRuntime = new MeasurementRuntime(sensorOwner, locationOwner);
        measurementRuntime.startAsync();
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
        if (measurementRuntime != null) {
            measurementRuntime.stopAsync();
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
        super.onDestroy();
    }

    public void registerClient(ISportLoggerServiceClient client) {
        mainHandler.removeCallbacks(idleStop);
        clients.register(client);
        if (recordingController != null) {
            client.onRecordingStateChanged(recordingController.getSnapshot());
        }
        client.onLoggerUpdate(SharedData.getInstance());
    }

    public void unregisterClient(ISportLoggerServiceClient client) {
        clients.unregister(client);
        scheduleStopIfIdle();
    }

    public boolean startNewActivity() {
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
        boolean recording = state != RecordingState.IDLE && state != RecordingState.ERROR;
        boolean paused = state == RecordingState.PAUSED
                || state == RecordingState.STOPPING
                || state == RecordingState.DISCARDING
                || state == RecordingState.EXPORT_PENDING;
        SharedData.getInstance().setRecordingState(
                recording,
                paused,
                snapshot.getActivityId(),
                formatDuration(snapshot.getDurationMillis()));

        if (snapshot.isActive() && state != RecordingState.STARTING) {
            mainHandler.removeCallbacks(idleStop);
            ensureForeground();
        } else if (state == RecordingState.IDLE) {
            if (foreground) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                foreground = false;
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
        SharedData sharedData = SharedData.getInstance();
        switch (event.getKind()) {
            case LOCATION:
                sharedData.setLocation(event.getLocation());
                break;
            case HEART_RATE:
                sharedData.setHeartRate(event.getHeartRate());
                break;
            case STEPS:
                sharedData.setSteps(event.getSteps());
                break;
            default:
                throw new IllegalStateException("Unhandled measurement kind " + event.getKind());
        }
        scheduleMeasurementDispatch();
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
        mainHandler.post(() -> reportError(
                failure.getCode().name(),
                failure.getResource() + ": " + failure.getMessage()));
    }

    private void reportError(String code, String message) {
        Log.e(TAG, code + ": " + message);
        clients.dispatch(client -> client.onLoggerError(code, message));
    }

    private boolean ensureForeground() {
        if (foreground) {
            return true;
        }
        Notification notification = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                int type = 0;
                if (hasLocationPermission()) {
                    type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                }
                if (hasHealthPermission()) {
                    type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH;
                }
                if (type == 0) {
                    reportError(
                            RecordingError.Code.FOREGROUND.name(),
                            "Location or health permission is required to record");
                    return false;
                }
                startForeground(NOTIFICATION_ID, notification, type);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && hasLocationPermission()) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
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

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasHealthPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS)
                == PackageManager.PERMISSION_GRANTED
                || (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACTIVITY_RECOGNITION)
                == PackageManager.PERMISSION_GRANTED);
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
        if (state == RecordingState.IDLE && clients.isEmpty()) {
            stopSelf();
        }
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

    public final class LocalBinder extends Binder {
        public SportLoggerService getService() {
            return SportLoggerService.this;
        }
    }
}
