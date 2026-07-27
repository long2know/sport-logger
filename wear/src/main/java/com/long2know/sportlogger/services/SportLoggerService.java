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
    public enum RecordingStatus {
        IDLE,
        RECORDING,
        PAUSED,
        RECOVERY_REQUIRED
    }

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
    private volatile boolean _listenersReady;
    private volatile int _activityId;
    private volatile long _writerGeneration;
    private RecordingRecoveryState _recoveryState;

    @Override
    public void onCreate() {
        super.onCreate();
        Config.context = getApplicationContext();
        _recoveryState = new RecordingRecoveryState(
                new SharedPreferencesRecordingRecoveryStore(this));
        restoreRetainedRecording();
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

        LifecycleTermination listenerStartup = replaceOwnedListeners();
        if (!listenerStartup.succeeded()) {
            Log.e(
                    TAG,
                    "Could not replace the previous listener generation: "
                            + listenerStartup);
            handleListenerLifecycleFailure(listenerStartup);
            return;
        }
        if (_recoveryState.snapshot().ownsActivity()) {
            RecordingOperationResult recovery = retryRecovery();
            if (!recovery.isSuccess() && !recovery.isNoOp()) {
                postLifecycleFailure(recovery);
            }
        }
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
        RecordingRecoveryState.Snapshot recovery =
                _recoveryState == null
                        ? RecordingRecoveryState.Snapshot.idle()
                        : _recoveryState.snapshot();
        LifecycleTermination writerTermination = recovery.ownsActivity()
                ? WRITERS.fenceGeneration(
                        recovery.getActivityId(),
                        recovery.getGeneration(),
                        WRITER_FENCE_TIMEOUT_MILLIS)
                : WRITERS.fenceOwned(this, WRITER_FENCE_TIMEOUT_MILLIS);
        LifecycleTermination listenerTermination = LifecycleTermination.TERMINATED;
        if (_listenerGroup != null) {
            listenerTermination =
                    LISTENERS.release(
                            _listenerGroup, LISTENER_FENCE_TIMEOUT_MILLIS);
            _listenerGroup = null;
        }
        _listenersReady = false;
        _stopWatch.pauseTimer();
        if (recovery.ownsActivity()) {
            if (writerTermination.quiesced() && listenerTermination.succeeded()) {
                _recoveryState.recordPaused(
                        recovery.getActivityId(), recovery.getGeneration());
            } else {
                _recoveryState.requireRecovery(
                        recovery.getActivityId(), recovery.getGeneration());
            }
        } else if (writerTermination.succeeded()
                && listenerTermination.succeeded()) {
            _stopWatch.resetTimer();
        }
        if (!writerTermination.quiesced() || !listenerTermination.succeeded()) {
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

    public synchronized RecordingStatus getRecordingStatus() {
        switch (_stateMachine.getState()) {
            case RECORDING:
            case STARTING:
            case RESUMING:
                return RecordingStatus.RECORDING;
            case PAUSED:
            case PAUSING:
            case STOPPING:
            case DISCARDING:
                return RecordingStatus.PAUSED;
            case RECOVERING:
            case RECOVERY_REQUIRED:
                return RecordingStatus.RECOVERY_REQUIRED;
            default:
                return RecordingStatus.IDLE;
        }
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
            _stateMachine.completeFailure(RecordingStateMachine.Operation.START, false);
            showIdle();
            return writerFailure(
                    previousGeneration,
                    RecordingOperationResult.RecoveryAction.RETURN_TO_START);
        }

        int createdActivityId = 0;
        try {
            SqlLogger.initDatabase();
            createdActivityId = SqlLogger.createActivity();
            if (createdActivityId <= 0) {
                _stateMachine.completeFailure(RecordingStateMachine.Operation.START, false);
                return RecordingOperationResult.of(
                        RecordingOperationResult.Status.DATABASE_FAILED, _activityId);
            }
            if (!_recoveryState.recordActivityCreated(createdActivityId)) {
                _activityId = createdActivityId;
                _writerGeneration = 0L;
                return rollbackCreatedActivity(
                        createdActivityId,
                        RecordingOperationResult.Status.DATABASE_FAILED);
            }
            _activityId = createdActivityId;
            _writerGeneration = 0L;

            RecordingWriterCoordinator.StartStatus writerStatus =
                    startWriter(createdActivityId);
            if (writerStatus != RecordingWriterCoordinator.StartStatus.STARTED) {
                LifecycleTermination writerTermination =
                        WRITERS.fenceOwned(this, WRITER_FENCE_TIMEOUT_MILLIS);
                if (!writerTermination.quiesced()) {
                    _stateMachine.completeFailure(
                            RecordingStateMachine.Operation.START, true);
                    markRecoveryRequired();
                    return writerFailure(
                            writerTermination,
                            RecordingOperationResult.RecoveryAction
                                   .SHOW_RECOVERY_RETRY);
                }
                return rollbackCreatedActivity(
                        createdActivityId,
                        RecordingOperationResult.Status.START_FAILED);
            }

            if (_writerGeneration <= 0L
                    || !_recoveryState.recordRecording(
                            createdActivityId, _writerGeneration)) {
                LifecycleTermination writerTermination =
                        WRITERS.fenceOwned(this, WRITER_FENCE_TIMEOUT_MILLIS);
                if (writerTermination.quiesced()) {
                    return rollbackCreatedActivity(
                            createdActivityId,
                            RecordingOperationResult.Status.DATABASE_FAILED);
                }
                _stateMachine.completeFailure(
                        RecordingStateMachine.Operation.START, true);
                markRecoveryRequired();
                return writerFailure(
                        writerTermination,
                        RecordingOperationResult.RecoveryAction
                               .SHOW_RECOVERY_RETRY);
            }
            if (_permissionLossHandled) {
                _stateMachine.completeFailure(
                        RecordingStateMachine.Operation.START, true);
                markRecoveryRequired();
                return RecordingOperationResult.recovery(
                        RecordingOperationResult.Status.PERMISSION_DENIED,
                        createdActivityId,
                        RecordingOperationResult.RecoveryAction
                               .SHOW_RECOVERY_RETRY);
            }
            showRecording(createdActivityId);
            _listenerEventsActive.set(true);
            _stopWatch.startTImer();
            _stateMachine.completeSuccess(RecordingStateMachine.Operation.START);
            Toast.makeText(
                    this, "Starting new activity", Toast.LENGTH_SHORT).show();
            return RecordingOperationResult.success(createdActivityId);
        } catch (RuntimeException exception) {
            Log.e(TAG, "Could not start recording.", exception);
            LifecycleTermination writerTermination =
                    WRITERS.fenceOwned(this, WRITER_FENCE_TIMEOUT_MILLIS);
            if (createdActivityId > 0 && writerTermination.quiesced()) {
                return rollbackCreatedActivity(
                        createdActivityId,
                        RecordingOperationResult.Status.DATABASE_FAILED);
            }
            _stateMachine.completeFailure(
                    RecordingStateMachine.Operation.START,
                    createdActivityId > 0);
            if (createdActivityId > 0) {
                markRecoveryRequired();
                return RecordingOperationResult.recovery(
                        RecordingOperationResult.Status.DATABASE_FAILED,
                        createdActivityId,
                        RecordingOperationResult.RecoveryAction
                               .SHOW_RECOVERY_RETRY);
            }
            return RecordingOperationResult.of(
                    RecordingOperationResult.Status.DATABASE_FAILED, 0);
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
        if (!termination.quiesced()) {
            _stopWatch.pauseTimer();
            _stateMachine.completeFailure(RecordingStateMachine.Operation.PAUSE, true);
            markRecoveryRequired();
            return writerFailure(
                    termination,
                    RecordingOperationResult.RecoveryAction.SHOW_RECOVERY_RETRY);
        }
        if (!_recoveryState.recordPaused(_activityId, _writerGeneration)) {
            _stopWatch.pauseTimer();
            _stateMachine.completeFailure(RecordingStateMachine.Operation.PAUSE, true);
            markRecoveryRequired();
            return RecordingOperationResult.recovery(
                    RecordingOperationResult.Status.DATABASE_FAILED,
                    _activityId,
                    RecordingOperationResult.RecoveryAction.SHOW_RECOVERY_RETRY);
        }

        showPausedRecording(_activityId);
        _stopWatch.pauseTimer();
        _stateMachine.completeSuccess(RecordingStateMachine.Operation.PAUSE);
        Toast.makeText(this, "Paused activity", Toast.LENGTH_SHORT).show();
        if (termination == LifecycleTermination.TERMINATED_WITH_FAILURE) {
            return RecordingOperationResult.recovery(
                    RecordingOperationResult.Status.WRITER_FAILED,
                    _activityId,
                    RecordingOperationResult.RecoveryAction.SHOW_PAUSED_CONTROLS);
        }
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
        if (!_listenersReady
                || _listenerGroup == null
                || !LISTENERS.isOwner(_listenerGroup)) {
            _stateMachine.completeFailure(RecordingStateMachine.Operation.RESUME, true);
            markRecoveryRequired();
            return RecordingOperationResult.recovery(
                    RecordingOperationResult.Status.LISTENER_FAILED,
                    _activityId,
                    RecordingOperationResult.RecoveryAction.SHOW_RECOVERY_RETRY);
        }

        RecordingWriterCoordinator.StartStatus writerStatus =
                startWriter(_activityId);
        if (writerStatus != RecordingWriterCoordinator.StartStatus.STARTED) {
            _stateMachine.completeFailure(RecordingStateMachine.Operation.RESUME, true);
            markRecoveryRequired();
            return RecordingOperationResult.recovery(
                    writerStatus
                            == RecordingWriterCoordinator.StartStatus.PREVIOUS_GENERATION_ACTIVE
                            ? RecordingOperationResult.Status.WRITER_TIMED_OUT
                            : RecordingOperationResult.Status.START_FAILED,
                    _activityId,
                    RecordingOperationResult.RecoveryAction.SHOW_RECOVERY_RETRY);
        }
        if (_writerGeneration <= 0L
                || !_recoveryState.recordRecording(
                        _activityId, _writerGeneration)) {
            LifecycleTermination writerTermination =
                    WRITERS.fenceOwned(this, WRITER_FENCE_TIMEOUT_MILLIS);
            _stateMachine.completeFailure(RecordingStateMachine.Operation.RESUME, true);
            markRecoveryRequired();
            return writerTermination.quiesced()
                    ? RecordingOperationResult.recovery(
                            RecordingOperationResult.Status.DATABASE_FAILED,
                            _activityId,
                            RecordingOperationResult.RecoveryAction
                                    .SHOW_RECOVERY_RETRY)
                    : writerFailure(
                            writerTermination,
                            RecordingOperationResult.RecoveryAction
                                    .SHOW_RECOVERY_RETRY);
        }

        showRecording(_activityId);
        _listenerEventsActive.set(true);
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
        if (!termination.quiesced()) {
            _stopWatch.pauseTimer();
            _stateMachine.completeFailure(RecordingStateMachine.Operation.STOP, true);
            markRecoveryRequired();
            return writerFailure(
                    termination,
                    RecordingOperationResult.RecoveryAction.SHOW_RECOVERY_RETRY);
        }

        int stoppedActivityId = _activityId;
        if (!_recoveryState.clearAfterStop(stoppedActivityId)) {
            _stateMachine.completeFailure(RecordingStateMachine.Operation.STOP, true);
            markRecoveryRequired();
            return RecordingOperationResult.recovery(
                    RecordingOperationResult.Status.DATABASE_FAILED,
                    stoppedActivityId,
                    RecordingOperationResult.RecoveryAction.SHOW_RECOVERY_RETRY);
        }
        showIdle();
        _stopWatch.pauseTimer();
        _stopWatch.resetTimer();
        _stateMachine.completeSuccess(RecordingStateMachine.Operation.STOP);
        _activityId = 0;
        _writerGeneration = 0L;
        Toast.makeText(this, "Stopped activity", Toast.LENGTH_SHORT).show();
        return RecordingOperationResult.success(stoppedActivityId);
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
        if (!termination.quiesced()) {
            _stopWatch.pauseTimer();
            _stateMachine.completeFailure(RecordingStateMachine.Operation.DISCARD, true);
            markRecoveryRequired();
            return writerFailure(
                    termination,
                    RecordingOperationResult.RecoveryAction.SHOW_RECOVERY_RETRY);
        }

        int discardedActivityId = _activityId;
        try {
            new SqlLogger().deleteActivity(discardedActivityId);
        } catch (RuntimeException exception) {
            Log.e(TAG, "Could not discard activity.", exception);
            _stopWatch.pauseTimer();
            _stateMachine.completeFailure(RecordingStateMachine.Operation.DISCARD, true);
            markRecoveryRequired();
            return RecordingOperationResult.recovery(
                    RecordingOperationResult.Status.DATABASE_FAILED,
                    discardedActivityId,
                    RecordingOperationResult.RecoveryAction.SHOW_RECOVERY_RETRY);
        }

        if (!_recoveryState.clearAfterDiscard(discardedActivityId)) {
            _stateMachine.completeFailure(RecordingStateMachine.Operation.DISCARD, true);
            markRecoveryRequired();
            return RecordingOperationResult.recovery(
                    RecordingOperationResult.Status.DATABASE_FAILED,
                    discardedActivityId,
                    RecordingOperationResult.RecoveryAction.SHOW_RECOVERY_RETRY);
        }
        showIdle();
        _stopWatch.pauseTimer();
        _stopWatch.resetTimer();
        _stateMachine.completeSuccess(RecordingStateMachine.Operation.DISCARD);
        _activityId = 0;
        _writerGeneration = 0L;
        Toast.makeText(this, "Discarded activity", Toast.LENGTH_SHORT).show();
        return RecordingOperationResult.success(discardedActivityId);
    }

    public synchronized RecordingOperationResult retryRecovery() {
        RecordingStateMachine.Decision decision =
                _stateMachine.begin(RecordingStateMachine.Operation.RECOVER);
        if (decision == RecordingStateMachine.Decision.NO_OP) {
            return RecordingOperationResult.noOp(_activityId);
        }
        if (decision == RecordingStateMachine.Decision.INVALID) {
            return RecordingOperationResult.of(
                    RecordingOperationResult.Status.INVALID_STATE, _activityId);
        }

        RecordingRecoveryState.Snapshot retained = _recoveryState.snapshot();
        if (!retained.ownsActivity()) {
            _stateMachine.restoreIdle();
            showIdle();
            return RecordingOperationResult.recovery(
                    RecordingOperationResult.Status.INVALID_STATE,
                    0,
                    RecordingOperationResult.RecoveryAction.RETURN_TO_START);
        }

        try {
            SqlLogger.initDatabase();
            if (!SqlLogger.activityExists(retained.getActivityId())) {
                _recoveryState.clearAfterDiscard(retained.getActivityId());
                _activityId = 0;
                _writerGeneration = 0L;
                _stateMachine.restoreIdle();
                showIdle();
                return RecordingOperationResult.recovery(
                        RecordingOperationResult.Status.DATABASE_FAILED,
                        0,
                        RecordingOperationResult.RecoveryAction.RETURN_TO_START);
            }
        } catch (RuntimeException exception) {
            Log.e(TAG, "Could not validate the retained recording.", exception);
            _stateMachine.completeFailure(
                    RecordingStateMachine.Operation.RECOVER, true);
            markRecoveryRequired();
            return RecordingOperationResult.recovery(
                    RecordingOperationResult.Status.DATABASE_FAILED,
                    retained.getActivityId(),
                    RecordingOperationResult.RecoveryAction.SHOW_RECOVERY_RETRY);
        }

        LifecycleTermination writerTermination =
                WRITERS.fenceGeneration(
                        retained.getActivityId(),
                        retained.getGeneration(),
                        WRITER_FENCE_TIMEOUT_MILLIS);
        if (!writerTermination.quiesced()) {
            _stateMachine.completeFailure(
                    RecordingStateMachine.Operation.RECOVER, true);
            markRecoveryRequired();
            return writerFailure(
                    writerTermination,
                    RecordingOperationResult.RecoveryAction.SHOW_RECOVERY_RETRY);
        }

        _permissionLossHandled = false;
        LifecycleTermination listenerStartup =
                _listenersReady
                        && _listenerGroup != null
                        && LISTENERS.isOwner(_listenerGroup)
                        ? LifecycleTermination.TERMINATED
                        : replaceOwnedListeners();
        if (!listenerStartup.succeeded()) {
            _stateMachine.completeFailure(
                    RecordingStateMachine.Operation.RECOVER, true);
            markRecoveryRequired();
            return listenerFailure(
                    listenerStartup,
                    RecordingOperationResult.RecoveryAction.SHOW_RECOVERY_RETRY);
        }
        if (_permissionLossHandled
                || !RecordingPermissions.allRequiredForRecordingGranted(this)) {
            _stateMachine.completeFailure(
                    RecordingStateMachine.Operation.RECOVER, true);
            markRecoveryRequired();
            return RecordingOperationResult.recovery(
                    RecordingOperationResult.Status.PERMISSION_DENIED,
                    retained.getActivityId(),
                    RecordingOperationResult.RecoveryAction.SHOW_RECOVERY_RETRY);
        }

        if (!_recoveryState.recordPaused(
                retained.getActivityId(), retained.getGeneration())) {
            _stateMachine.completeFailure(
                    RecordingStateMachine.Operation.RECOVER, true);
            markRecoveryRequired();
            return RecordingOperationResult.recovery(
                    RecordingOperationResult.Status.DATABASE_FAILED,
                    retained.getActivityId(),
                    RecordingOperationResult.RecoveryAction.SHOW_RECOVERY_RETRY);
        }

        _activityId = retained.getActivityId();
        _writerGeneration = retained.getGeneration();
        _writerFailureHandled.set(false);
        _listenerEventsActive.set(true);
        showPausedRecording(_activityId);
        _stateMachine.completeSuccess(RecordingStateMachine.Operation.RECOVER);
        return RecordingOperationResult.success(_activityId);
    }

    private RecordingWriterCoordinator.StartStatus startWriter(final int activityId) {
        _writerFailureHandled.set(false);
        RecordingWriterCoordinator.StartStatus status = WRITERS.start(
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
        if (status == RecordingWriterCoordinator.StartStatus.STARTED) {
            _writerGeneration = WRITERS.generationFor(this, activityId);
        }
        return status;
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
            if (_activityId != generation.getActivityId()
                    || _writerGeneration != generation.getGeneration()
                    || !_recoveryState.requireRecovery(
                            generation.getActivityId(),
                            generation.getGeneration())) {
                return;
            }
            showRecoveryRequired(generation.getActivityId());
            _stopWatch.pauseTimer();
        }
        _lifecycleExecutor.execute(new Runnable() {
            @Override
            public void run() {
                LifecycleTermination termination =
                        WRITERS.fenceGeneration(
                                generation.getActivityId(),
                                generation.getGeneration(),
                                WRITER_FENCE_TIMEOUT_MILLIS);
                _mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        synchronized (SportLoggerService.this) {
                            if (termination.quiesced()
                                    && _recoveryState.recordPaused(
                                            generation.getActivityId(),
                                            generation.getGeneration())) {
                                _stateMachine.restorePaused();
                                showPausedRecording(generation.getActivityId());
                                postLifecycleFailure(
                                        RecordingOperationResult.recovery(
                                                RecordingOperationResult.Status
                                                        .WRITER_FAILED,
                                                generation.getActivityId(),
                                                RecordingOperationResult
                                                        .RecoveryAction
                                                        .SHOW_PAUSED_CONTROLS));
                                return;
                            }
                            _stateMachine.restoreOwnedActivity();
                            markRecoveryRequired();
                            postLifecycleFailure(writerFailure(
                                    termination,
                                    RecordingOperationResult.RecoveryAction
                                            .SHOW_RECOVERY_RETRY));
                        }
                    }
                });
            }
        });
    }

    private LifecycleTermination replaceOwnedListeners() {
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
        _listenersReady = replacement.succeeded();
        return replacement;
    }

    private void handleRecordingPermissionLoss() {
        final RecordingRecoveryState.Snapshot retained;
        synchronized (this) {
            if (_permissionLossHandled) {
                return;
            }
            _permissionLossHandled = true;
            _stateMachine.begin(RecordingStateMachine.Operation.SHUTDOWN);
            retained = _recoveryState.snapshot();
            if (retained.ownsActivity()) {
                _recoveryState.requireRecovery(
                        retained.getActivityId(), retained.getGeneration());
                showRecoveryRequired(retained.getActivityId());
            } else {
                _recoveryState.clearAfterPreRecordingFailure();
                showIdle();
            }
            _listenersReady = false;
            _stopWatch.pauseTimer();
        }

        _lifecycleExecutor.execute(new Runnable() {
            @Override
            public void run() {
                LifecycleTermination writerTermination =
                        retained.ownsActivity()
                                ? WRITERS.fenceGeneration(
                                        retained.getActivityId(),
                                        retained.getGeneration(),
                                        WRITER_FENCE_TIMEOUT_MILLIS)
                                : WRITERS.fenceOwned(
                                        SportLoggerService.this,
                                        WRITER_FENCE_TIMEOUT_MILLIS);
                LifecycleTermination listenerTermination =
                        _listenerGroup == null
                                ? LifecycleTermination.TERMINATED
                                : LISTENERS.release(
                                        _listenerGroup,
                                        LISTENER_FENCE_TIMEOUT_MILLIS);
                if (!writerTermination.quiesced()) {
                    applyPermissionFenceFailureState(retained);
                    postLifecycleFailure(writerFailure(
                            writerTermination,
                            retained.ownsActivity()
                                    ? RecordingOperationResult.RecoveryAction
                                            .SHOW_RECOVERY_RETRY
                                    : RecordingOperationResult.RecoveryAction
                                            .RETURN_TO_START));
                    return;
                }
                if (!listenerTermination.succeeded()) {
                    applyPermissionFenceFailureState(retained);
                    postLifecycleFailure(listenerFailure(
                            listenerTermination,
                            retained.ownsActivity()
                                    ? RecordingOperationResult.RecoveryAction
                                            .SHOW_RECOVERY_RETRY
                                    : RecordingOperationResult.RecoveryAction
                                            .RETURN_TO_START));
                    return;
                }
                if (retained.ownsActivity()
                        && !_recoveryState.recordPaused(
                                retained.getActivityId(),
                                retained.getGeneration())) {
                    applyPermissionFenceFailureState(retained);
                    postLifecycleFailure(RecordingOperationResult.recovery(
                            RecordingOperationResult.Status.DATABASE_FAILED,
                            retained.getActivityId(),
                            RecordingOperationResult.RecoveryAction
                                    .SHOW_RECOVERY_RETRY));
                    return;
                }

                _mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        SharedData shared = SharedData.getInstance();
                        shared.IsRecording = false;
                        shared.IsPaused = false;
                        shared.RequiresRecovery = false;
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

    private void applyPermissionFenceFailureState(
            RecordingRecoveryState.Snapshot retained) {
        if (retained.ownsActivity()) {
            markRecoveryRequired();
        } else {
            _stateMachine.restoreIdle();
            showIdle();
        }
    }

    private void handleListenerLifecycleFailure(LifecycleTermination termination) {
        _permissionLossHandled = true;
        _listenersReady = false;
        _stopWatch.pauseTimer();
        RecordingRecoveryState.Snapshot retained = _recoveryState.snapshot();
        if (!retained.ownsActivity()) {
            _recoveryState.clearAfterPreRecordingFailure();
            _stateMachine.restoreIdle();
            showIdle();
            postLifecycleFailure(listenerFailure(
                    termination,
                    RecordingOperationResult.RecoveryAction.RETURN_TO_START));
            return;
        }
        _recoveryState.requireRecovery(
                retained.getActivityId(), retained.getGeneration());
        _stateMachine.restoreOwnedActivity();
        showRecoveryRequired(retained.getActivityId());
        postLifecycleFailure(listenerFailure(
                termination,
                RecordingOperationResult.RecoveryAction.SHOW_RECOVERY_RETRY));
    }

    private void postLifecycleFailure(final RecordingOperationResult failure) {
        Log.e(TAG, "Recording lifecycle failure: " + failure.getStatus());
        _pendingLifecycleFailure = failure;
        _mainHandler.post(new Runnable() {
            @Override
            public void run() {
                deliverPendingLifecycleFailure();
                if (failure.getRecoveryAction()
                        == RecordingOperationResult.RecoveryAction.RETURN_TO_START) {
                    stopSelf();
                }
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

    private RecordingOperationResult writerFailure(
            LifecycleTermination termination,
            RecordingOperationResult.RecoveryAction recoveryAction) {
        RecordingOperationResult.Status status;
        if (termination == LifecycleTermination.INTERRUPTED) {
            status = RecordingOperationResult.Status.INTERRUPTED;
        } else if (termination == LifecycleTermination.TIMED_OUT) {
            status = RecordingOperationResult.Status.WRITER_TIMED_OUT;
        } else {
            status = RecordingOperationResult.Status.WRITER_FAILED;
        }
        return RecordingOperationResult.recovery(
                status, _activityId, recoveryAction);
    }

    private RecordingOperationResult listenerFailure(
            LifecycleTermination termination,
            RecordingOperationResult.RecoveryAction recoveryAction) {
        RecordingOperationResult.Status status;
        if (termination == LifecycleTermination.INTERRUPTED) {
            status = RecordingOperationResult.Status.INTERRUPTED;
        } else if (termination == LifecycleTermination.TIMED_OUT) {
            status = RecordingOperationResult.Status.LISTENER_TIMED_OUT;
        } else {
            status = RecordingOperationResult.Status.LISTENER_FAILED;
        }
        return RecordingOperationResult.recovery(
                status, _activityId, recoveryAction);
    }

    private void restoreRetainedRecording() {
        RecordingRecoveryState.Snapshot retained = _recoveryState.snapshot();
        if (!retained.ownsActivity()) {
            _recoveryState.clearAfterPreRecordingFailure();
            _stateMachine.restoreIdle();
            showIdle();
            return;
        }

        boolean activityExists = true;
        try {
            SqlLogger.initDatabase();
            activityExists = SqlLogger.activityExists(retained.getActivityId());
        } catch (RuntimeException exception) {
            Log.e(TAG, "Could not validate retained recording metadata.", exception);
        }
        if (!_recoveryState.prepareForServiceReplacement(activityExists)) {
            Log.e(TAG, "Could not persist replacement recovery state.");
        }

        retained = _recoveryState.snapshot();
        if (!retained.ownsActivity() || !activityExists) {
            _activityId = 0;
            _writerGeneration = 0L;
            _stateMachine.restoreIdle();
            showIdle();
            return;
        }

        _activityId = retained.getActivityId();
        _writerGeneration = retained.getGeneration();
        WRITERS.restoreGenerationFloor(_writerGeneration);
        _stateMachine.restoreOwnedActivity();
        showRecoveryRequired(_activityId);
    }

    private synchronized void markRecoveryRequired() {
        RecordingRecoveryState.Snapshot retained = _recoveryState.snapshot();
        if (!retained.ownsActivity()) {
            _stateMachine.restoreIdle();
            showIdle();
            return;
        }
        _recoveryState.requireRecovery(
                retained.getActivityId(), retained.getGeneration());
        _stateMachine.restoreOwnedActivity();
        showRecoveryRequired(retained.getActivityId());
    }

    private RecordingOperationResult rollbackCreatedActivity(
            int activityId, RecordingOperationResult.Status failureStatus) {
        if (deleteActivity(activityId)
                && _recoveryState.clearAfterDiscard(activityId)) {
            _activityId = 0;
            _writerGeneration = 0L;
            _stateMachine.completeFailure(
                    RecordingStateMachine.Operation.START, false);
            showIdle();
            return RecordingOperationResult.of(failureStatus, 0);
        }

        _stateMachine.completeFailure(RecordingStateMachine.Operation.START, true);
        markRecoveryRequired();
        return RecordingOperationResult.recovery(
                RecordingOperationResult.Status.DATABASE_FAILED,
                activityId,
                RecordingOperationResult.RecoveryAction.SHOW_RECOVERY_RETRY);
    }

    private boolean deleteActivity(int activityId) {
        try {
            new SqlLogger().deleteActivity(activityId);
            return true;
        } catch (RuntimeException exception) {
            Log.e(TAG, "Could not delete activity " + activityId + ".", exception);
            return false;
        }
    }

    private static void showRecording(int activityId) {
        SharedData shared = SharedData.getInstance();
        shared.ActivityId = activityId;
        shared.IsRecording = true;
        shared.IsPaused = false;
        shared.RequiresRecovery = false;
    }

    private static void showPausedRecording(int activityId) {
        SharedData shared = SharedData.getInstance();
        shared.ActivityId = activityId;
        shared.IsRecording = true;
        shared.IsPaused = true;
        shared.RequiresRecovery = false;
    }

    private static void showRecoveryRequired(int activityId) {
        SharedData shared = SharedData.getInstance();
        shared.ActivityId = activityId;
        shared.IsRecording = true;
        shared.IsPaused = true;
        shared.RequiresRecovery = true;
    }

    private static void showIdle() {
        SharedData shared = SharedData.getInstance();
        shared.ActivityId = 0;
        shared.IsRecording = false;
        shared.IsPaused = false;
        shared.RequiresRecovery = false;
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
