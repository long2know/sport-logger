package com.long2know.sportlogger.services;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.long2know.sportlogger.MainActivity;
import com.long2know.sportlogger.R;
import com.long2know.sportlogger.RecordingPermissions;
import com.long2know.utilities.data_access.SqlLogger;
import com.long2know.utilities.models.Config;
import com.long2know.utilities.models.SharedData;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class SportLoggerService extends Service {
    private static final String NOTIFICATION_CHANNEL_ID = "long2know_sport_logger";
    private static final int NOTIFICATION_ID = 1;
    private static final String TAG = "SportLoggerService";
    private static final long WRITER_FENCE_TIMEOUT_MILLIS = 2_000L;
    private static final long LISTENER_FENCE_TIMEOUT_MILLIS = 2_000L;

    private static final RecordingWriterCoordinator WRITERS =
            new RecordingWriterCoordinator();
    private static final OwnedListenerRegistry<ListenerGroup> LISTENERS =
            new OwnedListenerRegistry<>();

    private final IBinder _binder = new LocalBinder();
    private final Handler _mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService _lifecycleExecutor =
            Executors.newSingleThreadExecutor();
    private final RecordingStateMachine _stateMachine = new RecordingStateMachine();
    private final StopWatch _stopWatch = new StopWatch();
    private final AtomicBoolean _listenerEventsActive = new AtomicBoolean(false);
    private final AtomicBoolean _writerFailureHandled = new AtomicBoolean(false);

    private NotificationManager _notificationManager;
    private Handler _uiForwardingHandler;
    private ListenerGroup _listenerGroup;
    private volatile ISportLoggerServiceClient _serviceClient;
    private volatile RecordingOperationResult _pendingLifecycleFailure;
    private volatile boolean _permissionLossHandled;
    private volatile int _activityId;

    @Override
    public void onCreate() {
        super.onCreate();
        Config.context = getApplicationContext();
        _uiForwardingHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message message) {
                if (!_listenerEventsActive.get()) {
                    return;
                }
                Handler activityHandler = Config.activityHandler;
                if (activityHandler != null) {
                    Message forwarded = activityHandler.obtainMessage(
                            message.what,
                            message.arg1,
                            message.arg2,
                            message.obj);
                    forwarded.sendToTarget();
                }
            }
        };

        _notificationManager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (!RecordingPermissions.allRequiredForRecordingGranted(this)) {
            handleRecordingPermissionLoss();
            return;
        }

        try {
            showNotification();
        } catch (SecurityException exception) {
            Log.e(TAG, "Recording permissions were revoked before foreground startup.", exception);
            handleRecordingPermissionLoss();
            return;
        }

        startOwnedListeners();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!RecordingPermissions.allRequiredForRecordingGranted(this)) {
            handleRecordingPermissionLoss();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return _binder;
    }

    @Override
    public void onDestroy() {
        _serviceClient = null;
        _permissionLossHandled = true;
        _listenerEventsActive.set(false);
        _stateMachine.begin(RecordingStateMachine.Operation.SHUTDOWN);
        LifecycleTermination writerTermination =
                WRITERS.fenceOwned(this, WRITER_FENCE_TIMEOUT_MILLIS);
        LifecycleTermination listenerTermination = LifecycleTermination.TERMINATED;
        if (_listenerGroup != null) {
            listenerTermination =
                    LISTENERS.release(
                            _listenerGroup, LISTENER_FENCE_TIMEOUT_MILLIS);
            _listenerGroup = null;
        }
        _stopWatch.pauseTimer();
        if (writerTermination.succeeded()
                && listenerTermination.succeeded()
                && SharedData.getInstance().ActivityId == _activityId) {
            _stopWatch.resetTimer();
        } else {
            Log.e(
                    TAG,
                    "Service destroyed before bounded lifecycle termination: writer="
                            + writerTermination
                            + ", listeners="
                            + listenerTermination);
        }
        _lifecycleExecutor.shutdownNow();
        super.onDestroy();
    }

    public synchronized void setServiceClient(ISportLoggerServiceClient client) {
        _serviceClient = client;
        if (client != null && _pendingLifecycleFailure != null) {
            _mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    deliverPendingLifecycleFailure();
                }
            });
        }
    }

    public synchronized void clearServiceClient(ISportLoggerServiceClient client) {
        if (_serviceClient == client) {
            _serviceClient = null;
        }
    }

    public void recordingPermissionsRevoked() {
        handleRecordingPermissionLoss();
    }

    public class LocalBinder extends Binder {
        public SportLoggerService getService() {
            return SportLoggerService.this;
        }
    }

    public synchronized RecordingOperationResult startNewActivity() {
        RecordingStateMachine.Decision decision =
                _stateMachine.begin(RecordingStateMachine.Operation.START);
        if (decision == RecordingStateMachine.Decision.NO_OP) {
            return RecordingOperationResult.noOp(_activityId);
        }
        if (decision == RecordingStateMachine.Decision.INVALID) {
            return RecordingOperationResult.of(
                    RecordingOperationResult.Status.INVALID_STATE, _activityId);
        }
        if (_permissionLossHandled) {
            _stateMachine.completeFailure(RecordingStateMachine.Operation.START, false);
            return RecordingOperationResult.of(
                    RecordingOperationResult.Status.INVALID_STATE, _activityId);
        }
        if (!RecordingPermissions.allRequiredForRecordingGranted(this)) {
            _stateMachine.completeFailure(RecordingStateMachine.Operation.START, false);
            handleRecordingPermissionLoss();
            return RecordingOperationResult.of(
                    RecordingOperationResult.Status.PERMISSION_DENIED, _activityId);
        }

        LifecycleTermination previousGeneration =
                WRITERS.fenceAny(WRITER_FENCE_TIMEOUT_MILLIS);
        if (!previousGeneration.succeeded()) {
            _stateMachine.completeFailure(RecordingStateMachine.Operation.START, true);
            return writerFailure(previousGeneration);
        }

        try {
            SqlLogger.initDatabase();
            int activityId = SqlLogger.createActivity();
            if (activityId <= 0) {
                _stateMachine.completeFailure(RecordingStateMachine.Operation.START, false);
                return RecordingOperationResult.of(
                        RecordingOperationResult.Status.DATABASE_FAILED, _activityId);
            }

            RecordingWriterCoordinator.StartStatus writerStatus =
                    startWriter(activityId);
            if (writerStatus != RecordingWriterCoordinator.StartStatus.STARTED) {
                LifecycleTermination writerTermination =
                        WRITERS.fenceOwned(this, WRITER_FENCE_TIMEOUT_MILLIS);
                if (!writerTermination.succeeded()) {
                    _stateMachine.completeFailure(
                            RecordingStateMachine.Operation.START, true);
                    return writerFailure(writerTermination);
                }
                new SqlLogger().deleteActivity(activityId);
                _stateMachine.completeFailure(RecordingStateMachine.Operation.START, false);
                return RecordingOperationResult.of(
                        RecordingOperationResult.Status.START_FAILED, _activityId);
            }

            _activityId = activityId;
            SharedData shared = SharedData.getInstance();
            shared.ActivityId = activityId;
            if (_permissionLossHandled) {
                shared.IsRecording = true;
                shared.IsPaused = true;
                return RecordingOperationResult.of(
                        RecordingOperationResult.Status.WRITER_FAILED, activityId);
            }
            shared.IsRecording = true;
            shared.IsPaused = false;
            _stopWatch.startTImer();
            _stateMachine.completeSuccess(RecordingStateMachine.Operation.START);
            Toast.makeText(
                    this, "Starting new activity", Toast.LENGTH_SHORT).show();
            return RecordingOperationResult.success(activityId);
        } catch (RuntimeException exception) {
            Log.e(TAG, "Could not start recording.", exception);
            WRITERS.fenceOwned(this, WRITER_FENCE_TIMEOUT_MILLIS);
            _stateMachine.completeFailure(RecordingStateMachine.Operation.START, false);
            return RecordingOperationResult.of(
                    RecordingOperationResult.Status.DATABASE_FAILED, _activityId);
        }
    }

    public synchronized RecordingOperationResult pauseActivity() {
        RecordingStateMachine.Decision decision =
                _stateMachine.begin(RecordingStateMachine.Operation.PAUSE);
        if (decision == RecordingStateMachine.Decision.NO_OP) {
            return RecordingOperationResult.noOp(_activityId);
        }
        if (decision == RecordingStateMachine.Decision.INVALID) {
            return RecordingOperationResult.of(
                    RecordingOperationResult.Status.INVALID_STATE, _activityId);
        }

        LifecycleTermination termination =
                WRITERS.fenceOwned(this, WRITER_FENCE_TIMEOUT_MILLIS);
        if (!termination.succeeded()) {
            SharedData.getInstance().IsPaused = true;
            _stopWatch.pauseTimer();
            _stateMachine.completeFailure(RecordingStateMachine.Operation.PAUSE, true);
            return writerFailure(termination);
        }

        SharedData.getInstance().IsPaused = true;
        _stopWatch.pauseTimer();
        _stateMachine.completeSuccess(RecordingStateMachine.Operation.PAUSE);
        Toast.makeText(this, "Paused activity", Toast.LENGTH_SHORT).show();
        return RecordingOperationResult.success(_activityId);
    }

    public synchronized RecordingOperationResult resumeActivity() {
        RecordingStateMachine.Decision decision =
                _stateMachine.begin(RecordingStateMachine.Operation.RESUME);
        if (decision == RecordingStateMachine.Decision.NO_OP) {
            return RecordingOperationResult.noOp(_activityId);
        }
        if (decision == RecordingStateMachine.Decision.INVALID) {
            return RecordingOperationResult.of(
                    RecordingOperationResult.Status.INVALID_STATE, _activityId);
        }
        if (_permissionLossHandled) {
            _stateMachine.completeFailure(RecordingStateMachine.Operation.RESUME, false);
            return RecordingOperationResult.of(
                    RecordingOperationResult.Status.INVALID_STATE, _activityId);
        }
        if (!RecordingPermissions.allRequiredForRecordingGranted(this)) {
            _stateMachine.completeFailure(RecordingStateMachine.Operation.RESUME, false);
            handleRecordingPermissionLoss();
            return RecordingOperationResult.of(
                    RecordingOperationResult.Status.PERMISSION_DENIED, _activityId);
        }

        RecordingWriterCoordinator.StartStatus writerStatus =
                startWriter(_activityId);
        if (writerStatus != RecordingWriterCoordinator.StartStatus.STARTED) {
            _stateMachine.completeFailure(
                    RecordingStateMachine.Operation.RESUME,
                    writerStatus
                            == RecordingWriterCoordinator.StartStatus.PREVIOUS_GENERATION_ACTIVE);
            return RecordingOperationResult.of(
                    writerStatus
                            == RecordingWriterCoordinator.StartStatus.PREVIOUS_GENERATION_ACTIVE
                            ? RecordingOperationResult.Status.WRITER_TIMED_OUT
                            : RecordingOperationResult.Status.START_FAILED,
                    _activityId);
        }

        SharedData.getInstance().IsPaused = false;
        _stopWatch.startTImer();
        _stateMachine.completeSuccess(RecordingStateMachine.Operation.RESUME);
        Toast.makeText(this, "Resuming activity", Toast.LENGTH_SHORT).show();
        return RecordingOperationResult.success(_activityId);
    }

    public synchronized RecordingOperationResult stopActivity() {
        RecordingStateMachine.Decision decision =
                _stateMachine.begin(RecordingStateMachine.Operation.STOP);
        if (decision == RecordingStateMachine.Decision.NO_OP) {
            return RecordingOperationResult.noOp(_activityId);
        }
        if (decision == RecordingStateMachine.Decision.INVALID) {
            return RecordingOperationResult.of(
                    RecordingOperationResult.Status.INVALID_STATE, _activityId);
        }

        LifecycleTermination termination =
                WRITERS.fenceOwned(this, WRITER_FENCE_TIMEOUT_MILLIS);
        if (!termination.succeeded()) {
            SharedData.getInstance().IsPaused = true;
            _stopWatch.pauseTimer();
            _stateMachine.completeFailure(RecordingStateMachine.Operation.STOP, true);
            return writerFailure(termination);
        }

        SharedData shared = SharedData.getInstance();
        shared.IsRecording = false;
        shared.IsPaused = false;
        _stopWatch.pauseTimer();
        _stopWatch.resetTimer();
        _stateMachine.completeSuccess(RecordingStateMachine.Operation.STOP);
        Toast.makeText(this, "Stopped activity", Toast.LENGTH_SHORT).show();
        return RecordingOperationResult.success(_activityId);
    }

    public synchronized RecordingOperationResult discardActivity() {
        RecordingStateMachine.Decision decision =
                _stateMachine.begin(RecordingStateMachine.Operation.DISCARD);
        if (decision == RecordingStateMachine.Decision.NO_OP) {
            return RecordingOperationResult.noOp(_activityId);
        }
        if (decision == RecordingStateMachine.Decision.INVALID) {
            return RecordingOperationResult.of(
                    RecordingOperationResult.Status.INVALID_STATE, _activityId);
        }

        LifecycleTermination termination =
                WRITERS.fenceOwned(this, WRITER_FENCE_TIMEOUT_MILLIS);
        if (!termination.succeeded()) {
            SharedData.getInstance().IsPaused = true;
            _stopWatch.pauseTimer();
            _stateMachine.completeFailure(RecordingStateMachine.Operation.DISCARD, true);
            return writerFailure(termination);
        }

        try {
            new SqlLogger().deleteActivity(_activityId);
        } catch (RuntimeException exception) {
            Log.e(TAG, "Could not discard activity.", exception);
            SharedData.getInstance().IsPaused = true;
            _stopWatch.pauseTimer();
            _stateMachine.completeFailure(RecordingStateMachine.Operation.DISCARD, true);
            return RecordingOperationResult.of(
                    RecordingOperationResult.Status.DATABASE_FAILED, _activityId);
        }

        SharedData shared = SharedData.getInstance();
        shared.IsRecording = false;
        shared.IsPaused = false;
        _stopWatch.pauseTimer();
        _stopWatch.resetTimer();
        _stateMachine.completeSuccess(RecordingStateMachine.Operation.DISCARD);
        Toast.makeText(this, "Discarded activity", Toast.LENGTH_SHORT).show();
        return RecordingOperationResult.success(_activityId);
    }

    private RecordingWriterCoordinator.StartStatus startWriter(final int activityId) {
        return WRITERS.start(
                this,
                activityId,
                generation ->
                        new PermissionCheckedTask(
                                new PermissionCheckedTask.CancellationCheck() {
                                    @Override
                                    public boolean isCancelled() {
                                        return _permissionLossHandled
                                                || !WRITERS.isActive(generation);
                                    }
                                },
                                new PermissionCheckedTask.PermissionCheck() {
                                    @Override
                                    public boolean allRequiredPermissionsGranted() {
                                        return RecordingPermissions
                                                .allRequiredForRecordingGranted(
                                                        SportLoggerService.this);
                                    }
                                },
                                new SqlLogger(activityId),
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        handleRecordingPermissionLoss();
                                    }
                                }),
                new RecordingWriterCoordinator.FailureListener() {
                    @Override
                    public void onFailure(
                            final RecordingWriterCoordinator.GenerationToken generation,
                            final RuntimeException exception) {
                        if (!_writerFailureHandled.compareAndSet(false, true)) {
                            return;
                        }
                        _permissionLossHandled = true;
                        _listenerEventsActive.set(false);
                        _stateMachine.failGeneration();
                        _mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                handleWriterTaskFailure(generation, exception);
                            }
                        });
                    }
                });
    }

    private void handleWriterTaskFailure(
            RecordingWriterCoordinator.GenerationToken generation,
            RuntimeException exception) {
        Log.e(
                TAG,
                "Track-point writer failed for generation "
                        + generation.getGeneration()
                        + ".",
                exception);
        synchronized (this) {
            SharedData shared = SharedData.getInstance();
            if (shared.ActivityId == generation.getActivityId()) {
                shared.IsPaused = true;
                _stopWatch.pauseTimer();
            }
        }
        postLifecycleFailure(RecordingOperationResult.of(
                RecordingOperationResult.Status.WRITER_FAILED,
                generation.getActivityId()));
    }

    private void startOwnedListeners() {
        Runnable permissionFailure = new Runnable() {
            @Override
            public void run() {
                handleRecordingPermissionLoss();
            }
        };
        ListenerGroup group = new ListenerGroup(
                _listenerEventsActive,
                new SensorListener(
                        this,
                        _uiForwardingHandler,
                        permissionFailure,
                        _listenerEventsActive),
                new GpsListener(
                        this,
                        _uiForwardingHandler,
                        permissionFailure,
                        _listenerEventsActive),
                "sport-logger-" + System.identityHashCode(this));
        _listenerGroup = group;
        LifecycleTermination replacement =
                LISTENERS.replace(group, LISTENER_FENCE_TIMEOUT_MILLIS);
        if (!replacement.succeeded()) {
            Log.e(TAG, "Could not replace the previous listener generation: " + replacement);
            handleListenerLifecycleFailure(replacement);
        }
    }

    private void handleRecordingPermissionLoss() {
        synchronized (this) {
            if (_permissionLossHandled) {
                return;
            }
            _permissionLossHandled = true;
            _stateMachine.begin(RecordingStateMachine.Operation.SHUTDOWN);
            SharedData shared = SharedData.getInstance();
            shared.IsPaused = true;
            _stopWatch.pauseTimer();
        }

        _lifecycleExecutor.execute(new Runnable() {
            @Override
            public void run() {
                LifecycleTermination writerTermination =
                        WRITERS.fenceOwned(
                                SportLoggerService.this,
                                WRITER_FENCE_TIMEOUT_MILLIS);
                LifecycleTermination listenerTermination =
                        _listenerGroup == null
                                ? LifecycleTermination.TERMINATED
                                : LISTENERS.release(
                                        _listenerGroup,
                                        LISTENER_FENCE_TIMEOUT_MILLIS);
                if (!writerTermination.succeeded()) {
                    postLifecycleFailure(writerFailure(writerTermination));
                    return;
                }
                if (!listenerTermination.succeeded()) {
                    postLifecycleFailure(listenerFailure(listenerTermination));
                    return;
                }

                _mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        SharedData shared = SharedData.getInstance();
                        shared.IsRecording = false;
                        shared.IsPaused = false;
                        shared.setHeartRate(0);
                        _stopWatch.resetTimer();
                        ISportLoggerServiceClient client = _serviceClient;
                        if (client != null) {
                            client.onRecordingPermissionLost();
                        }
                        stopSelf();
                    }
                });
            }
        });
    }

    private void handleListenerLifecycleFailure(LifecycleTermination termination) {
        _permissionLossHandled = true;
        SharedData.getInstance().IsPaused = true;
        _stopWatch.pauseTimer();
        postLifecycleFailure(listenerFailure(termination));
    }

    private void postLifecycleFailure(final RecordingOperationResult failure) {
        Log.e(TAG, "Recording lifecycle failure: " + failure.getStatus());
        _pendingLifecycleFailure = failure;
        _mainHandler.post(new Runnable() {
            @Override
            public void run() {
                deliverPendingLifecycleFailure();
                stopSelf();
            }
        });
    }

    private synchronized void deliverPendingLifecycleFailure() {
        ISportLoggerServiceClient client = _serviceClient;
        RecordingOperationResult failure = _pendingLifecycleFailure;
        if (client != null && failure != null) {
            _pendingLifecycleFailure = null;
            client.onRecordingLifecycleFailure(failure);
        }
    }

    private RecordingOperationResult writerFailure(LifecycleTermination termination) {
        RecordingOperationResult.Status status;
        if (termination == LifecycleTermination.INTERRUPTED) {
            status = RecordingOperationResult.Status.INTERRUPTED;
        } else if (termination == LifecycleTermination.TIMED_OUT) {
            status = RecordingOperationResult.Status.WRITER_TIMED_OUT;
        } else {
            status = RecordingOperationResult.Status.WRITER_FAILED;
        }
        return RecordingOperationResult.of(
                status, _activityId);
    }

    private RecordingOperationResult listenerFailure(LifecycleTermination termination) {
        RecordingOperationResult.Status status;
        if (termination == LifecycleTermination.INTERRUPTED) {
            status = RecordingOperationResult.Status.INTERRUPTED;
        } else if (termination == LifecycleTermination.TIMED_OUT) {
            status = RecordingOperationResult.Status.LISTENER_TIMED_OUT;
        } else {
            status = RecordingOperationResult.Status.LISTENER_FAILED;
        }
        return RecordingOperationResult.of(
                status, _activityId);
    }

    private void showNotification() {
        Intent contentIntent = new Intent(this, MainActivity.class);
        contentIntent.setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pending = PendingIntent.getActivity(
                this,
                0,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_description));
        _notificationManager.createNotificationChannel(channel);
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                        .setWhen(System.currentTimeMillis())
                        .setContentTitle(getString(R.string.notification_title))
                        .setContentText(getString(R.string.notification_text))
                        .setSmallIcon(R.drawable.ic_play_circle_outline_black_24dp)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setContentIntent(pending);

        int serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        ServiceCompat.startForeground(
                this, NOTIFICATION_ID, builder.build(), serviceTypes);
    }
}
