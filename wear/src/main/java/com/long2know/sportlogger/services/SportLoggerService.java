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
import java.util.concurrent.atomic.AtomicReference;

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

    private static final class WriterStart {
        final RecordingWriterCoordinator.StartStatus status;
        final RecordingWriterCoordinator.GenerationToken generation;

        WriterStart(
                RecordingWriterCoordinator.StartStatus status,
                RecordingWriterCoordinator.GenerationToken generation) {
            this.status = status;
            this.generation = generation;
        }
    }

    private final IBinder _binder = new LocalBinder();
    private final Handler _mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService _lifecycleExecutor =
            Executors.newSingleThreadExecutor();
    private final RecordingOperationDispatcher _operations =
            new RecordingOperationDispatcher(_lifecycleExecutor);
    private final ExecutorService _destroyExecutor =
            Executors.newSingleThreadExecutor();
    private final RecordingStateMachine _stateMachine = new RecordingStateMachine();
    private final StopWatch _stopWatch = new StopWatch();
    private final AtomicBoolean _listenerEventsActive = new AtomicBoolean(false);

    private NotificationManager _notificationManager;
    private ListenerGroup _listenerGroup;
    private ListenerGroup _startingListenerGroup;
    private ISportLoggerServiceClient _serviceClient;
    private RecordingOperationResult _pendingLifecycleFailure;
    private RecordingOperationResult _pendingOperationCompletion;
    private volatile boolean _permissionLossHandled;
    private boolean _listenersReady;
    private boolean _startupReady;
    private volatile boolean _closing;
    private int _activityId;
    private long _writerGeneration;
    private long _nextListenerGeneration;
    private long _listenerGeneration;
    private RecordingRecoveryState _recoveryState;

    @Override
    public void onCreate() {
        super.onCreate();
        Config.context = getApplicationContext();
        _recoveryState = new RecordingRecoveryState(
                new SharedPreferencesRecordingRecoveryStore(this));
        initializeRetainedMirror();

        _notificationManager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (!RecordingPermissions.allRequiredForRecordingGranted(this)) {
            handleRecordingPermissionLoss(_operations.getServiceGeneration());
            return;
        }

        try {
            showNotification();
        } catch (SecurityException exception) {
            Log.e(
                    TAG,
                    "Recording permissions were revoked before foreground startup.",
                    exception);
            handleRecordingPermissionLoss(_operations.getServiceGeneration());
            return;
        }

        scheduleStartup();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!RecordingPermissions.allRequiredForRecordingGranted(this)) {
            handleRecordingPermissionLoss(_operations.getServiceGeneration());
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
        final RecordingRecoveryState.Snapshot retained;
        final ListenerGroup listenerGroup;
        final ListenerGroup startingListenerGroup;
        final int writerActivityId;
        final long writerGeneration;
        final boolean recoveryCurrentProcessOnly;
        synchronized (this) {
            _closing = true;
            _serviceClient = null;
            _pendingLifecycleFailure = null;
            _pendingOperationCompletion = null;
            _permissionLossHandled = true;
            _listenerEventsActive.set(false);
            _listenersReady = false;
            _listenerGeneration = ++_nextListenerGeneration;
            _stateMachine.begin(RecordingStateMachine.Operation.SHUTDOWN);
            retained = _recoveryState == null
                    ? RecordingRecoveryState.Snapshot.idle()
                    : _recoveryState.snapshot();
            writerActivityId = _activityId > 0
                    ? _activityId
                    : retained.getActivityId();
            writerGeneration = _writerGeneration > 0L
                    ? _writerGeneration
                    : retained.getGeneration();
            recoveryCurrentProcessOnly =
                    SharedData.getInstance().RecoveryCurrentProcessOnly;
            listenerGroup = _listenerGroup;
            startingListenerGroup = _startingListenerGroup;
            _listenerGroup = null;
            _startingListenerGroup = null;
        }
        _stopWatch.pauseTimer();
        if (listenerGroup != null) {
            listenerGroup.requestShutdown();
        }
        if (startingListenerGroup != null
                && startingListenerGroup != listenerGroup) {
            startingListenerGroup.requestShutdown();
        }
        boolean writerCancellationRequested =
                writerActivityId > 0
                        ? WRITERS.requestFenceGeneration(
                                writerActivityId, writerGeneration)
                        : WRITERS.requestFenceOwned(this);
        if (!writerCancellationRequested) {
            Log.e(
                    TAG,
                    "Destroyed service could not invalidate its exact writer immediately; "
                            + "durable recovery remains retained.");
        }

        boolean executorClosed = _operations.close();
        if (!executorClosed) {
            Log.e(TAG, "Lifecycle executor rejected shutdown after generation invalidation.");
        }
        if (recoveryCurrentProcessOnly) {
            Log.e(
                    TAG,
                    "Service was destroyed with current-process-only recovery metadata. "
                            + "Database rows remain retained, but process/service replacement "
                            + "cannot be promised.");
        }
        scheduleDestroyCleanup(
                writerActivityId,
                writerGeneration,
                listenerGroup,
                startingListenerGroup);
        super.onDestroy();
    }

    public void setServiceClient(ISportLoggerServiceClient client) {
        final long serviceGeneration;
        synchronized (this) {
            if (_closing) {
                return;
            }
            _serviceClient = client;
            serviceGeneration = _operations.getServiceGeneration();
        }
        if (client != null) {
            postMainForGeneration(serviceGeneration, new Runnable() {
                @Override
                public void run() {
                    deliverPendingOperationCompletion();
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
        handleRecordingPermissionLoss(_operations.getServiceGeneration());
    }

    public synchronized RecordingStatus getRecordingStatus() {
        RecordingOperationDispatcher.Token active = _operations.getActive();
        if (active != null) {
            RecordingStatus pendingStatus = pendingStatus(
                    active.getOperation(), active.getActivityId());
            if (pendingStatus != null) {
                return pendingStatus;
            }
        } else if (_pendingOperationCompletion != null) {
            RecordingStatus pendingStatus = pendingStatus(
                    _pendingOperationCompletion.getOperation(),
                    _pendingOperationCompletion.getActivityId());
            if (pendingStatus != null) {
                return pendingStatus;
            }
        }
        switch (_stateMachine.getStableState()) {
            case RECORDING:
                return RecordingStatus.RECORDING;
            case PAUSED:
                return RecordingStatus.PAUSED;
            case RECOVERY_REQUIRED:
                return RecordingStatus.RECOVERY_REQUIRED;
            case SHUTTING_DOWN:
                return _activityId > 0
                        ? RecordingStatus.RECOVERY_REQUIRED
                        : RecordingStatus.IDLE;
            default:
                return RecordingStatus.IDLE;
        }
    }

    public synchronized RecordingOperationResult getPendingOperation() {
        RecordingOperationDispatcher.Token active = _operations.getActive();
        if (active != null) {
            return RecordingOperationResult.pending(
                        active.getOperation(),
                        active.getOperationToken(),
                        active.getActivityId());
        }
        RecordingOperationResult pending =
                _pendingOperationCompletion != null
                        ? _pendingOperationCompletion
                        : _pendingLifecycleFailure;
        return pending == null
                ? null
                : RecordingOperationResult.pending(
                        pending.getOperation(),
                        pending.getOperationToken(),
                        pending.getActivityId());
    }

    public class LocalBinder extends Binder {
        public SportLoggerService getService() {
            return SportLoggerService.this;
        }
    }

    public RecordingOperationResult startNewActivity() {
        return submitOperation(
                RecordingOperationResult.Operation.START,
                RecordingStateMachine.Operation.START);
    }

    public RecordingOperationResult pauseActivity() {
        return submitOperation(
                RecordingOperationResult.Operation.PAUSE,
                RecordingStateMachine.Operation.PAUSE);
    }

    public RecordingOperationResult resumeActivity() {
        return submitOperation(
                RecordingOperationResult.Operation.RESUME,
                RecordingStateMachine.Operation.RESUME);
    }

    public RecordingOperationResult stopActivity() {
        return submitOperation(
                RecordingOperationResult.Operation.STOP,
                RecordingStateMachine.Operation.STOP);
    }

    public RecordingOperationResult discardActivity() {
        return submitOperation(
                RecordingOperationResult.Operation.DISCARD,
                RecordingStateMachine.Operation.DISCARD);
    }

    public RecordingOperationResult retryRecovery() {
        return submitOperation(
                RecordingOperationResult.Operation.RECOVER,
                RecordingStateMachine.Operation.RECOVER);
    }

    private RecordingOperationResult submitOperation(
            RecordingOperationResult.Operation publicOperation,
            RecordingStateMachine.Operation stateOperation) {
        final RecordingOperationDispatcher.Token token;
        synchronized (this) {
            if (_closing) {
                return operationResult(
                        RecordingOperationResult.Status.SERVICE_CLOSED,
                        publicOperation,
                        0L,
                        _activityId);
            }
            RecordingOperationResult pending =
                    _pendingOperationCompletion != null
                            ? _pendingOperationCompletion
                            : _pendingLifecycleFailure;
            if (pending != null) {
                return RecordingOperationResult.pending(
                        pending.getOperation(),
                        pending.getOperationToken(),
                        pending.getActivityId());
            }
            RecordingOperationDispatcher.BeginResult begin =
                    _operations.begin(
                            publicOperation, _activityId, _writerGeneration);
            if (begin.getStatus()
                    == RecordingOperationDispatcher.BeginStatus.PENDING) {
                RecordingOperationDispatcher.Token active = begin.getToken();
                return RecordingOperationResult.pending(
                        active.getOperation(),
                        active.getOperationToken(),
                        active.getActivityId());
            }
            if (begin.getStatus()
                    == RecordingOperationDispatcher.BeginStatus.CLOSED) {
                return operationResult(
                        RecordingOperationResult.Status.SERVICE_CLOSED,
                        publicOperation,
                        0L,
                        _activityId);
            }
            token = begin.getToken();

            RecordingStateMachine.Decision decision =
                    _stateMachine.begin(stateOperation);
            if (decision != RecordingStateMachine.Decision.ACCEPTED) {
                _operations.finish(token);
                RecordingOperationResult.Status status =
                        decision == RecordingStateMachine.Decision.NO_OP
                                ? RecordingOperationResult.Status.NO_OP
                                : RecordingOperationResult.Status.INVALID_STATE;
                return operationResult(
                        status,
                        publicOperation,
                        token.getOperationToken(),
                        _activityId);
            }
        }

        RecordingOperationDispatcher.DispatchStatus dispatch =
                _operations.tryExecute(token, new Runnable() {
                    @Override
                    public void run() {
                        runOperation(token, stateOperation);
                    }
                });
        if (dispatch != RecordingOperationDispatcher.DispatchStatus.SCHEDULED) {
            synchronized (this) {
                if (_operations.owns(token)) {
                    _stateMachine.completeFailure(stateOperation, false);
                    _operations.finish(token);
                }
            }
            Log.e(
                    TAG,
                    "Lifecycle operation could not be scheduled; retained data was not cleared: "
                            + publicOperation
                            + " ("
                            + dispatch
                            + ")");
            return operationResult(
                    dispatch == RecordingOperationDispatcher.DispatchStatus.STALE
                            ? RecordingOperationResult.Status.STALE_OPERATION
                            : RecordingOperationResult.Status.SERVICE_CLOSED,
                    publicOperation,
                    token.getOperationToken(),
                    token.getActivityId());
        }
        return RecordingOperationResult.accepted(
                publicOperation, token.getOperationToken(), token.getActivityId());
    }

    private void runOperation(
            RecordingOperationDispatcher.Token token,
            RecordingStateMachine.Operation stateOperation) {
        switch (stateOperation) {
            case START:
                runStart(token);
                break;
            case PAUSE:
                runPause(token);
                break;
            case RESUME:
                runResume(token);
                break;
            case STOP:
                runStop(token);
                break;
            case DISCARD:
                runDiscard(token);
                break;
            case RECOVER:
                runRecovery(token);
                break;
            default:
                failWithoutRecovery(
                        token,
                        RecordingOperationResult.Status.INVALID_STATE,
                        token.getActivityId());
        }
    }

    private void runStart(RecordingOperationDispatcher.Token token) {
        if (!operationOwns(token)) {
            return;
        }
        if (_permissionLossHandled
                || !RecordingPermissions.allRequiredForRecordingGranted(this)) {
            handleRecordingPermissionLoss(token.getServiceGeneration());
            return;
        }
        LifecycleTermination previousGeneration =
                WRITERS.fenceAny(
                        new RecordingWriterCoordinator.FenceClaim() {
                            @Override
                            public boolean claim() {
                                return operationOwns(token);
                            }
                        },
                        WRITER_FENCE_TIMEOUT_MILLIS);
        if (!previousGeneration.succeeded()) {
            failWithoutRecovery(
                    token,
                    writerStatus(previousGeneration),
                    0);
            return;
        }
        if (!operationOwns(token)) {
            return;
        }
        LifecycleTermination listenerStartup =
                listenersReady()
                        ? LifecycleTermination.TERMINATED
                        : replaceOwnedListeners(
                                token.getServiceGeneration(), token);
        if (!listenerStartup.succeeded()) {
            failWithoutRecovery(
                    token,
                    listenerStatus(listenerStartup),
                    0);
            return;
        }

        final int createdActivityId;
        try {
            SqlLogger.initDatabase();
            createdActivityId = SqlLogger.createActivity();
        } catch (RuntimeException exception) {
            Log.e(TAG, "Could not create a recording activity.", exception);
            failWithoutRecovery(
                    token,
                    RecordingOperationResult.Status.DATABASE_FAILED,
                    0);
            return;
        }
        if (createdActivityId <= 0) {
            failWithoutRecovery(
                    token,
                    RecordingOperationResult.Status.DATABASE_FAILED,
                    0);
            return;
        }

        RecordingRecoveryState.Transition createdTransition =
                _recoveryState.recordActivityCreated(createdActivityId);
        if (!claimActivity(token, createdActivityId, 0L)) {
            Log.w(
                    TAG,
                    "Start became stale after row creation; durable ownership was retained for "
                            + createdActivityId
                            + ".");
            return;
        }
        if (!createdTransition.isPersisted()) {
            failWithRecovery(
                    token,
                    RecordingOperationResult.Status.RECOVERY_PERSISTENCE_FAILED,
                    createdActivityId,
                    0L,
                    createdTransition);
            return;
        }

        WriterStart writer = startWriter(
                createdActivityId, token.getServiceGeneration(), token);
        if (writer.status != RecordingWriterCoordinator.StartStatus.STARTED
                || writer.generation == null) {
            failWithRecovery(
                    token,
                    writer.status
                                    == RecordingWriterCoordinator.StartStatus
                                            .PREVIOUS_GENERATION_ACTIVE
                            ? RecordingOperationResult.Status.WRITER_TIMED_OUT
                            : RecordingOperationResult.Status.START_FAILED,
                    createdActivityId,
                    0L,
                    null);
            return;
        }
        long writerGeneration = writer.generation.getGeneration();
        if (!claimActivity(token, createdActivityId, writerGeneration)) {
            Log.w(
                    TAG,
                    "Start became stale after writer creation; recovery ownership remains "
                            + createdActivityId
                            + "/"
                            + writerGeneration
                            + ".");
            return;
        }

        RecordingRecoveryState.Transition recordingTransition =
                _recoveryState.recordRecording(
                        createdActivityId, writerGeneration);
        if (!recordingTransition.isPersisted()
                || !WRITERS.isActive(writer.generation)) {
            LifecycleTermination writerTermination =
                    WRITERS.fenceGeneration(
                            createdActivityId,
                            writerGeneration,
                            WRITER_FENCE_TIMEOUT_MILLIS);
            failWithRecovery(
                    token,
                    !recordingTransition.isPersisted()
                            ? RecordingOperationResult.Status
                                    .RECOVERY_PERSISTENCE_FAILED
                            : writerStatus(writerTermination),
                    createdActivityId,
                    writerGeneration,
                    recordingTransition);
            return;
        }

        synchronized (this) {
            if (!operationOwnsLocked(token)
                    || _permissionLossHandled
                    || !_stateMachine.completeSuccess(
                            RecordingStateMachine.Operation.START)) {
                return;
            }
            _activityId = createdActivityId;
            _writerGeneration = writerGeneration;
            showRecording(createdActivityId);
            _listenerEventsActive.set(true);
        }
        _stopWatch.startTImer();
        postOperationCompletion(
                token, RecordingOperationResult.success(createdActivityId));
    }

    private void runPause(RecordingOperationDispatcher.Token token) {
        LifecycleTermination termination =
                WRITERS.fenceGeneration(
                        token.getActivityId(),
                        token.getWriterGeneration(),
                        WRITER_FENCE_TIMEOUT_MILLIS);
        if (!termination.succeeded()) {
            failWithRecovery(
                    token,
                    writerStatus(termination),
                    token.getActivityId(),
                    token.getWriterGeneration(),
                    null);
            return;
        }
        if (!operationOwns(token)) {
            return;
        }

        RecordingRecoveryState.Transition pausedTransition =
                _recoveryState.recordPaused(
                        token.getActivityId(), token.getWriterGeneration());
        if (!pausedTransition.isPersisted()) {
            failWithRecovery(
                    token,
                    RecordingOperationResult.Status.RECOVERY_PERSISTENCE_FAILED,
                    token.getActivityId(),
                    token.getWriterGeneration(),
                    pausedTransition);
            return;
        }

        synchronized (this) {
            if (!operationOwnsLocked(token)
                    || !_stateMachine.completeSuccess(
                            RecordingStateMachine.Operation.PAUSE)) {
                return;
            }
            _listenerEventsActive.set(false);
            showPausedRecording(token.getActivityId());
        }
        _stopWatch.pauseTimer();
        postOperationCompletion(
                token, RecordingOperationResult.success(token.getActivityId()));
    }

    private void runResume(RecordingOperationDispatcher.Token token) {
        if (_permissionLossHandled
                || !RecordingPermissions.allRequiredForRecordingGranted(this)) {
            handleRecordingPermissionLoss(token.getServiceGeneration());
            return;
        }
        if (!listenersReady()) {
            failWithRecovery(
                    token,
                    RecordingOperationResult.Status.LISTENER_FAILED,
                    token.getActivityId(),
                    token.getWriterGeneration(),
                    null);
            return;
        }

        WriterStart writer = startWriter(
                token.getActivityId(), token.getServiceGeneration(), token);
        if (writer.status != RecordingWriterCoordinator.StartStatus.STARTED
                || writer.generation == null) {
            failWithRecovery(
                    token,
                    writer.status
                                    == RecordingWriterCoordinator.StartStatus
                                            .PREVIOUS_GENERATION_ACTIVE
                            ? RecordingOperationResult.Status.WRITER_TIMED_OUT
                            : RecordingOperationResult.Status.START_FAILED,
                    token.getActivityId(),
                    token.getWriterGeneration(),
                    null);
            return;
        }

        long writerGeneration = writer.generation.getGeneration();
        if (!claimActivity(token, token.getActivityId(), writerGeneration)) {
            return;
        }
        RecordingRecoveryState.Transition recordingTransition =
                _recoveryState.recordRecording(
                        token.getActivityId(), writerGeneration);
        if (!recordingTransition.isPersisted()
                || !WRITERS.isActive(writer.generation)) {
            LifecycleTermination writerTermination =
                    WRITERS.fenceGeneration(
                            token.getActivityId(),
                            writerGeneration,
                            WRITER_FENCE_TIMEOUT_MILLIS);
            failWithRecovery(
                    token,
                    !recordingTransition.isPersisted()
                            ? RecordingOperationResult.Status
                                    .RECOVERY_PERSISTENCE_FAILED
                            : writerStatus(writerTermination),
                    token.getActivityId(),
                    writerGeneration,
                    recordingTransition);
            return;
        }

        synchronized (this) {
            if (!operationOwnsLocked(token)
                    || _permissionLossHandled
                    || !_stateMachine.completeSuccess(
                            RecordingStateMachine.Operation.RESUME)) {
                return;
            }
            _writerGeneration = writerGeneration;
            showRecording(token.getActivityId());
            _listenerEventsActive.set(true);
        }
        _stopWatch.startTImer();
        postOperationCompletion(
                token, RecordingOperationResult.success(token.getActivityId()));
    }

    private void runStop(RecordingOperationDispatcher.Token token) {
        LifecycleTermination termination =
                WRITERS.fenceGeneration(
                        token.getActivityId(),
                        token.getWriterGeneration(),
                        WRITER_FENCE_TIMEOUT_MILLIS);
        if (!termination.succeeded()) {
            failWithRecovery(
                    token,
                    writerStatus(termination),
                    token.getActivityId(),
                    token.getWriterGeneration(),
                    null);
            return;
        }
        if (!operationOwns(token)) {
            return;
        }
        LifecycleTermination listenerTermination = releaseOwnedListeners();
        if (!listenerTermination.succeeded()) {
            failWithRecovery(
                    token,
                    listenerStatus(listenerTermination),
                    token.getActivityId(),
                    token.getWriterGeneration(),
                    null);
            return;
        }

        RecordingTerminalTransition.Outcome terminalOutcome;
        synchronized (this) {
            if (!operationOwnsLocked(token)) {
                return;
            }
            terminalOutcome = RecordingTerminalTransition.finish(
                    _stateMachine,
                    RecordingStateMachine.Operation.STOP,
                    termination);
        }
        if (!terminalOutcome.permitsTerminalEffects()) {
            failWithRecovery(
                    token,
                    terminalOutcome
                                    == RecordingTerminalTransition.Outcome
                                            .STATE_CHANGED
                            ? RecordingOperationResult.Status.STALE_OPERATION
                            : writerStatus(termination),
                    token.getActivityId(),
                    token.getWriterGeneration(),
                    null);
            return;
        }
        if (!operationOwns(token)) {
            return;
        }

        RecordingRecoveryState.Transition clearTransition =
                _recoveryState.clearAfterStop(token.getActivityId());
        if (!clearTransition.isPersisted()) {
            failWithRecovery(
                    token,
                    clearTransition.isAccepted()
                            ? RecordingOperationResult.Status
                                    .RECOVERY_PERSISTENCE_FAILED
                            : RecordingOperationResult.Status.INVALID_STATE,
                    token.getActivityId(),
                    token.getWriterGeneration(),
                    clearTransition);
            return;
        }

        synchronized (this) {
            if (!operationOwnsLocked(token)) {
                return;
            }
            _activityId = 0;
            _writerGeneration = 0L;
            _listenerEventsActive.set(false);
            showIdle();
        }
        _stopWatch.pauseTimer();
        _stopWatch.resetTimer();
        postOperationCompletion(
                token, RecordingOperationResult.success(token.getActivityId()));
    }

    private void runDiscard(RecordingOperationDispatcher.Token token) {
        LifecycleTermination termination =
                WRITERS.fenceGeneration(
                        token.getActivityId(),
                        token.getWriterGeneration(),
                        WRITER_FENCE_TIMEOUT_MILLIS);
        if (!termination.succeeded()) {
            failWithRecovery(
                    token,
                    writerStatus(termination),
                    token.getActivityId(),
                    token.getWriterGeneration(),
                    null);
            return;
        }
        if (!operationOwns(token)) {
            return;
        }
        LifecycleTermination listenerTermination = releaseOwnedListeners();
        if (!listenerTermination.succeeded()) {
            failWithRecovery(
                    token,
                    listenerStatus(listenerTermination),
                    token.getActivityId(),
                    token.getWriterGeneration(),
                    null);
            return;
        }

        RecordingTerminalTransition.Outcome terminalOutcome;
        synchronized (this) {
            if (!operationOwnsLocked(token)) {
                return;
            }
            terminalOutcome = RecordingTerminalTransition.finish(
                    _stateMachine,
                    RecordingStateMachine.Operation.DISCARD,
                    termination);
        }
        if (!terminalOutcome.permitsTerminalEffects()) {
            failWithRecovery(
                    token,
                    terminalOutcome
                                    == RecordingTerminalTransition.Outcome
                                            .STATE_CHANGED
                            ? RecordingOperationResult.Status.STALE_OPERATION
                            : writerStatus(termination),
                    token.getActivityId(),
                    token.getWriterGeneration(),
                    null);
            return;
        }
        if (!operationOwns(token)) {
            return;
        }

        try {
            new SqlLogger().deleteActivity(token.getActivityId());
        } catch (RuntimeException exception) {
            Log.e(TAG, "Could not discard activity.", exception);
            failWithRecovery(
                    token,
                    RecordingOperationResult.Status.DATABASE_FAILED,
                    token.getActivityId(),
                    token.getWriterGeneration(),
                    null);
            return;
        }
        if (!operationOwns(token)) {
            Log.w(
                    TAG,
                    "Discard completed after its service generation closed; no stale UI was notified.");
            return;
        }

        RecordingRecoveryState.Transition clearTransition =
                _recoveryState.clearAfterDiscard(token.getActivityId());
        if (!clearTransition.isPersisted()) {
            Log.e(
                    TAG,
                    "Discard deleted activity "
                            + token.getActivityId()
                            + " but could not clear durable recovery metadata.");
            failWithRecovery(
                    token,
                    RecordingOperationResult.Status
                            .RECOVERY_PERSISTENCE_FAILED,
                    token.getActivityId(),
                    token.getWriterGeneration(),
                    clearTransition);
            return;
        }
        synchronized (this) {
            if (!operationOwnsLocked(token)) {
                return;
            }
            _activityId = 0;
            _writerGeneration = 0L;
            _listenerEventsActive.set(false);
            _stateMachine.restoreIdle();
            showIdle();
        }
        _stopWatch.pauseTimer();
        _stopWatch.resetTimer();
        postOperationCompletion(
                token,
                RecordingOperationResult.success(token.getActivityId()));
    }

    private void runRecovery(RecordingOperationDispatcher.Token token) {
        RecordingRecoveryState.Snapshot retained = _recoveryState.snapshot();
        if (!retained.ownsActivity()) {
            synchronized (this) {
                if (!operationOwnsLocked(token)) {
                    return;
                }
                _stateMachine.restoreIdle();
                showIdle();
            }
            postOperationCompletion(
                    token,
                    RecordingOperationResult.recovery(
                            RecordingOperationResult.Status.INVALID_STATE,
                            0,
                            RecordingOperationResult.RecoveryAction
                                    .RETURN_TO_START));
            return;
        }

        try {
            SqlLogger.initDatabase();
            if (!SqlLogger.activityExists(retained.getActivityId())) {
                if (!operationOwns(token)) {
                    return;
                }
                RecordingRecoveryState.Transition clearTransition =
                        _recoveryState.clearAfterDiscard(
                                retained.getActivityId());
                if (!clearTransition.isPersisted()) {
                    failWithRecovery(
                            token,
                            RecordingOperationResult.Status
                                    .RECOVERY_PERSISTENCE_FAILED,
                            retained.getActivityId(),
                            retained.getGeneration(),
                            clearTransition);
                    return;
                }
                synchronized (this) {
                    if (!operationOwnsLocked(token)) {
                        return;
                    }
                    _activityId = 0;
                    _writerGeneration = 0L;
                    _stateMachine.restoreIdle();
                    showIdle();
                }
                postOperationCompletion(
                        token,
                        RecordingOperationResult.success(0));
                return;
            }
        } catch (RuntimeException exception) {
            Log.e(TAG, "Could not validate the retained recording.", exception);
            failWithRecovery(
                    token,
                    RecordingOperationResult.Status.DATABASE_FAILED,
                    retained.getActivityId(),
                    retained.getGeneration(),
                    null);
            return;
        }

        LifecycleTermination writerTermination =
                WRITERS.fenceGeneration(
                        retained.getActivityId(),
                        retained.getGeneration(),
                        WRITER_FENCE_TIMEOUT_MILLIS);
        if (!writerTermination.succeeded()) {
            failWithRecovery(
                    token,
                    writerStatus(writerTermination),
                    retained.getActivityId(),
                    retained.getGeneration(),
                    null);
            return;
        }
        if (_permissionLossHandled
                || !RecordingPermissions.allRequiredForRecordingGranted(this)) {
            handleRecordingPermissionLoss(token.getServiceGeneration());
            return;
        }

        LifecycleTermination listenerStartup =
                listenersReady()
                        ? LifecycleTermination.TERMINATED
                        : replaceOwnedListeners(
                                token.getServiceGeneration(), token);
        if (!listenerStartup.succeeded()) {
            failWithRecovery(
                    token,
                    listenerStatus(listenerStartup),
                    retained.getActivityId(),
                    retained.getGeneration(),
                    null);
            return;
        }

        RecordingRecoveryState.Transition pausedTransition =
                _recoveryState.recordPaused(
                        retained.getActivityId(), retained.getGeneration());
        if (!pausedTransition.isPersisted()) {
            releaseOwnedListeners();
            failWithRecovery(
                    token,
                    RecordingOperationResult.Status.RECOVERY_PERSISTENCE_FAILED,
                    retained.getActivityId(),
                    retained.getGeneration(),
                    pausedTransition);
            return;
        }

        synchronized (this) {
            if (!operationOwnsLocked(token)
                    || !_stateMachine.completeSuccess(
                            RecordingStateMachine.Operation.RECOVER)) {
                return;
            }
            _permissionLossHandled = false;
            _activityId = retained.getActivityId();
            _writerGeneration = retained.getGeneration();
            _listenerEventsActive.set(false);
            showPausedRecording(_activityId);
        }
        postOperationCompletion(
                token, RecordingOperationResult.success(retained.getActivityId()));
    }

    private void scheduleStartup() {
        final RecordingOperationDispatcher.Token token;
        synchronized (this) {
            RecordingOperationDispatcher.BeginResult begin =
                    _operations.begin(
                            RecordingOperationResult.Operation.STARTUP,
                            _activityId,
                            _writerGeneration);
            if (begin.getStatus()
                    != RecordingOperationDispatcher.BeginStatus.ACCEPTED) {
                Log.e(TAG, "Could not begin asynchronous service startup.");
                return;
            }
            token = begin.getToken();
        }

        RecordingOperationDispatcher.DispatchStatus dispatch =
                _operations.tryExecute(token, new Runnable() {
                    @Override
                    public void run() {
                        runStartup(token);
                    }
                });
        if (dispatch != RecordingOperationDispatcher.DispatchStatus.SCHEDULED) {
            synchronized (this) {
                _startupReady = true;
            }
            postLifecycleFailure(
                    token,
                    RecordingOperationResult.recovery(
                            RecordingOperationResult.Status.START_FAILED,
                            _activityId,
                            _activityId > 0
                                    ? RecordingOperationResult.RecoveryAction
                                            .SHOW_RECOVERY_RETRY
                                    : RecordingOperationResult.RecoveryAction
                                            .RETURN_TO_START));
        }
    }

    private void runStartup(RecordingOperationDispatcher.Token token) {
        RecordingRecoveryState.Snapshot retained = _recoveryState.snapshot();
        boolean activityExists = retained.ownsActivity();
        if (activityExists) {
            try {
                SqlLogger.initDatabase();
                activityExists = SqlLogger.activityExists(retained.getActivityId());
            } catch (RuntimeException exception) {
                Log.e(TAG, "Could not validate retained recording metadata.", exception);
                failStartupWithRecovery(
                        token,
                        RecordingOperationResult.Status.DATABASE_FAILED,
                        retained,
                        null);
                return;
            }
        }

        if (!operationOwns(token)) {
            return;
        }
        RecordingRecoveryState.Transition replacementTransition =
                _recoveryState.prepareForServiceReplacement(activityExists);
        retained = _recoveryState.snapshot();
        if (!activityExists
                && retained.ownsActivity()
                && !replacementTransition.isPersisted()) {
            failStartupWithRecovery(
                    token,
                    RecordingOperationResult.Status
                            .RECOVERY_PERSISTENCE_FAILED,
                    retained,
                    replacementTransition);
            return;
        }
        if (retained.ownsActivity()) {
            WRITERS.restoreGenerationFloor(retained.getGeneration());
            LifecycleTermination writerTermination =
                    WRITERS.fenceGeneration(
                            retained.getActivityId(),
                            retained.getGeneration(),
                            WRITER_FENCE_TIMEOUT_MILLIS);
            if (!writerTermination.succeeded()) {
                failStartupWithRecovery(
                        token,
                        writerStatus(writerTermination),
                        retained,
                        replacementTransition);
                return;
            }
        }
        if (!operationOwns(token)) {
            return;
        }

        LifecycleTermination listenerStartup =
                replaceOwnedListeners(token.getServiceGeneration(), token);
        if (!listenerStartup.succeeded()) {
            failStartupWithRecovery(
                    token,
                    listenerStatus(listenerStartup),
                    retained,
                    replacementTransition);
            return;
        }

        if (retained.ownsActivity()) {
            RecordingRecoveryState.Transition pausedTransition =
                    _recoveryState.recordPaused(
                            retained.getActivityId(), retained.getGeneration());
            if (!pausedTransition.isPersisted()) {
                releaseOwnedListeners();
                failStartupWithRecovery(
                        token,
                        RecordingOperationResult.Status
                                .RECOVERY_PERSISTENCE_FAILED,
                        retained,
                        pausedTransition);
                return;
            }
        }

        synchronized (this) {
            if (!operationOwnsLocked(token)) {
                return;
            }
            _startupReady = true;
            _permissionLossHandled = false;
            if (retained.ownsActivity()) {
                _activityId = retained.getActivityId();
                _writerGeneration = retained.getGeneration();
                _stateMachine.restorePaused();
                showPausedRecording(_activityId);
            } else {
                _activityId = 0;
                _writerGeneration = 0L;
                _stateMachine.restoreIdle();
                showIdle();
            }
        }
        postOperationCompletion(
                token,
                RecordingOperationResult.success(
                        retained.ownsActivity() ? retained.getActivityId() : 0));
    }

    private void failStartupWithRecovery(
            RecordingOperationDispatcher.Token token,
            RecordingOperationResult.Status status,
            RecordingRecoveryState.Snapshot retained,
            RecordingRecoveryState.Transition transition) {
        if (!operationOwns(token)) {
            return;
        }
        RecordingRecoveryState.Transition recoveryTransition = transition;
        if (retained.ownsActivity()
                && (recoveryTransition == null
                        || !recoveryTransition.isAccepted())) {
            recoveryTransition = _recoveryState.requireRecovery(
                    retained.getActivityId(), retained.getGeneration());
        }
        synchronized (this) {
            if (!operationOwnsLocked(token)) {
                return;
            }
            _startupReady = true;
            if (retained.ownsActivity()) {
                _activityId = retained.getActivityId();
                _writerGeneration = retained.getGeneration();
                _stateMachine.restoreOwnedActivity();
                showRecoveryRequired(
                        _activityId,
                        recoveryTransition != null
                                && recoveryTransition.isPersisted());
            } else {
                _activityId = 0;
                _writerGeneration = 0L;
                _stateMachine.restoreIdle();
                showIdle();
            }
            _listenerEventsActive.set(false);
            _listenersReady = false;
        }
        postLifecycleFailure(
                token,
                retained.ownsActivity()
                        ? recoveryFailure(
                                status,
                                retained.getActivityId(),
                                RecordingOperationResult.RecoveryAction
                                        .SHOW_RECOVERY_RETRY,
                                recoveryTransition)
                        : RecordingOperationResult.recovery(
                                status,
                                0,
                                RecordingOperationResult.RecoveryAction
                                        .RETURN_TO_START));
    }

    private WriterStart startWriter(
            final int activityId,
            final long serviceGeneration,
            final RecordingOperationDispatcher.Token operationToken) {
        final AtomicReference<RecordingWriterCoordinator.GenerationToken> started =
                new AtomicReference<>();
        RecordingWriterCoordinator.StartStatus status = WRITERS.start(
                this,
                activityId,
                generation -> {
                    started.set(generation);
                    return new PermissionCheckedTask(
                            new PermissionCheckedTask.CancellationCheck() {
                                @Override
                                public boolean isCancelled() {
                                    return _permissionLossHandled
                                            || _closing
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
                                    handleRecordingPermissionLoss(
                                            serviceGeneration);
                                }
                            });
                },
                new RecordingWriterCoordinator.GenerationClaim() {
                    @Override
                    public boolean claim(
                            RecordingWriterCoordinator.GenerationToken generation) {
                        started.set(generation);
                        return claimActivity(
                                operationToken,
                                activityId,
                                generation.getGeneration());
                    }
                },
                new RecordingWriterCoordinator.FailureListener() {
                    @Override
                    public void onFailure(
                            RecordingWriterCoordinator.GenerationToken generation,
                            RuntimeException exception) {
                        handleWriterTaskFailure(
                                serviceGeneration, generation, exception);
                    }
                });
        return new WriterStart(status, started.get());
    }

    private void handleWriterTaskFailure(
            long serviceGeneration,
            RecordingWriterCoordinator.GenerationToken generation,
            RuntimeException exception) {
        Log.e(
                TAG,
                "Track-point writer failed for generation "
                        + generation.getGeneration()
                        + ".",
                exception);

        final RecordingOperationDispatcher.Token cleanupToken;
        final ListenerGroup listenerGroup;
        synchronized (this) {
            if (_closing
                    || !_operations.isServiceGenerationCurrent(
                            serviceGeneration)
                    || _permissionLossHandled
                    || _activityId != generation.getActivityId()
                    || _writerGeneration != generation.getGeneration()) {
                Log.e(
                        TAG,
                        "Late writer failure was not dispatched. Existing recovery data remains "
                                + "retained for "
                                + generation.getActivityId()
                                + "/"
                                + generation.getGeneration()
                                + ".");
                return;
            }
            _operations.invalidateActive();
            _pendingOperationCompletion = null;
            _stateMachine.failGeneration();
            _listenerEventsActive.set(false);
            _listenersReady = false;
            _listenerGeneration = ++_nextListenerGeneration;
            listenerGroup = _listenerGroup;
            RecordingOperationDispatcher.BeginResult begin =
                    _operations.begin(
                            RecordingOperationResult.Operation.NONE,
                            generation.getActivityId(),
                            generation.getGeneration());
            if (begin.getStatus()
                    != RecordingOperationDispatcher.BeginStatus.ACCEPTED) {
                Log.e(
                        TAG,
                        "Writer failure cleanup could not claim the lifecycle executor; "
                                + "recovery data was retained.");
                return;
            }
            cleanupToken = begin.getToken();
        }
        _stopWatch.pauseTimer();
        if (listenerGroup != null) {
            listenerGroup.requestShutdown();
        }

        RecordingOperationDispatcher.DispatchStatus dispatch =
                _operations.tryExecute(cleanupToken, new Runnable() {
                    @Override
                    public void run() {
                        runWriterFailureCleanup(cleanupToken, generation);
                    }
                });
        if (dispatch != RecordingOperationDispatcher.DispatchStatus.SCHEDULED) {
            if (!operationOwns(cleanupToken)) {
                Log.e(
                        TAG,
                        "Writer failure raced service destruction. The existing durable tuple was "
                                + "left unchanged and no stale callback was delivered.");
                return;
            }
            RecordingRecoveryState.Transition retained =
                    _recoveryState.requireRecovery(
                            generation.getActivityId(),
                            generation.getGeneration());
            synchronized (this) {
                if (operationOwnsLocked(cleanupToken)) {
                    showRecoveryRequired(
                            generation.getActivityId(), retained.isPersisted());
                }
            }
            Log.e(
                    TAG,
                    "Writer failure cleanup dispatch was rejected; exact recovery tuple retained: "
                            + generation.getActivityId()
                            + "/"
                            + generation.getGeneration());
            postLifecycleFailure(
                    cleanupToken,
                    recoveryFailure(
                            RecordingOperationResult.Status.WRITER_FAILED,
                            generation.getActivityId(),
                            RecordingOperationResult.RecoveryAction
                                    .SHOW_RECOVERY_RETRY,
                            retained));
        }
    }

    private void runWriterFailureCleanup(
            RecordingOperationDispatcher.Token cleanupToken,
            RecordingWriterCoordinator.GenerationToken generation) {
        if (!operationOwns(cleanupToken)) {
            return;
        }
        RecordingRecoveryState.Transition recoveryTransition =
                _recoveryState.requireRecovery(
                        generation.getActivityId(), generation.getGeneration());
        LifecycleTermination writerTermination =
                WRITERS.fenceGeneration(
                        generation.getActivityId(),
                        generation.getGeneration(),
                        WRITER_FENCE_TIMEOUT_MILLIS);
        LifecycleTermination listenerTermination = releaseOwnedListeners();

        synchronized (this) {
            if (!operationOwnsLocked(cleanupToken)) {
                return;
            }
            _stateMachine.restoreOwnedActivity();
            _activityId = generation.getActivityId();
            _writerGeneration = generation.getGeneration();
            showRecoveryRequired(
                    generation.getActivityId(),
                    recoveryTransition.isPersisted());
        }
        if (!listenerTermination.succeeded()) {
            Log.e(
                    TAG,
                    "Writer failure listener shutdown did not complete: "
                            + listenerTermination);
        }
        RecordingOperationResult failure =
                writerTermination == LifecycleTermination.TIMED_OUT
                                || writerTermination
                                        == LifecycleTermination.INTERRUPTED
                                || writerTermination == LifecycleTermination.FAILED
                        ? recoveryFailure(
                                writerStatus(writerTermination),
                                generation.getActivityId(),
                                RecordingOperationResult.RecoveryAction
                                        .SHOW_RECOVERY_RETRY,
                                recoveryTransition)
                        : recoveryFailure(
                                RecordingOperationResult.Status.WRITER_FAILED,
                                generation.getActivityId(),
                                RecordingOperationResult.RecoveryAction
                                        .SHOW_RECOVERY_RETRY,
                                recoveryTransition);
        postLifecycleFailure(cleanupToken, failure);
    }

    private void handleRecordingPermissionLoss(long serviceGeneration) {
        handleRecordingPermissionLoss(serviceGeneration, 0L);
    }

    private void handleRecordingPermissionLoss(
            long serviceGeneration, long listenerGeneration) {
        final RecordingOperationDispatcher.Token permissionToken;
        final ListenerGroup listenerGroup;
        final ListenerGroup startingListenerGroup;
        synchronized (this) {
            if (_closing
                    || !_operations.isServiceGenerationCurrent(
                            serviceGeneration)
                    || (listenerGeneration != 0L
                            && listenerGeneration != _listenerGeneration)) {
                Log.w(
                        TAG,
                        "Ignored late permission-loss callback from a stale service/listener "
                                + "generation.");
                return;
            }
            if (_permissionLossHandled) {
                return;
            }
            _permissionLossHandled = true;
            _listenerEventsActive.set(false);
            _listenersReady = false;
            _listenerGeneration = ++_nextListenerGeneration;
            listenerGroup = _listenerGroup;
            startingListenerGroup = _startingListenerGroup;
            _operations.invalidateActive();
            _pendingOperationCompletion = null;
            _stateMachine.begin(RecordingStateMachine.Operation.SHUTDOWN);
            RecordingOperationDispatcher.BeginResult begin =
                    _operations.begin(
                            RecordingOperationResult.Operation.PERMISSION_LOSS,
                            _activityId,
                            _writerGeneration);
            if (begin.getStatus()
                    != RecordingOperationDispatcher.BeginStatus.ACCEPTED) {
                Log.e(
                        TAG,
                        "Permission-loss teardown could not claim its lifecycle operation; "
                                + "recording ownership remains retained.");
                return;
            }
            permissionToken = begin.getToken();
        }
        _stopWatch.pauseTimer();
        if (listenerGroup != null) {
            listenerGroup.requestShutdown();
        }
        if (startingListenerGroup != null
                && startingListenerGroup != listenerGroup) {
            startingListenerGroup.requestShutdown();
        }
        boolean writerCancellationRequested =
                permissionToken.getActivityId() > 0
                        ? WRITERS.requestFenceGeneration(
                                permissionToken.getActivityId(),
                                permissionToken.getWriterGeneration())
                        : WRITERS.requestFenceOwned(this);
        if (!writerCancellationRequested) {
            Log.e(
                    TAG,
                    "Permission loss could not immediately invalidate the exact writer; "
                            + "bounded teardown will retain recovery.");
        }

        RecordingOperationDispatcher.DispatchStatus dispatch =
                _operations.tryExecute(permissionToken, new Runnable() {
                    @Override
                    public void run() {
                        runPermissionLoss(permissionToken);
                    }
                });
        if (dispatch != RecordingOperationDispatcher.DispatchStatus.SCHEDULED) {
            RecordingRecoveryState.Snapshot retained = _recoveryState.snapshot();
            synchronized (this) {
                if (operationOwnsLocked(permissionToken)) {
                    if (retained.ownsActivity()) {
                        _stateMachine.restoreOwnedActivity();
                        showRecoveryRequired(retained.getActivityId());
                    } else {
                        _stateMachine.restoreIdle();
                        showIdle();
                    }
                }
            }
            Log.e(
                    TAG,
                    "Permission-loss teardown dispatch was rejected; data was retained.");
            postLifecycleFailure(
                    permissionToken,
                    retained.ownsActivity()
                            ? RecordingOperationResult.recovery(
                                    RecordingOperationResult.Status
                                            .LISTENER_FAILED,
                                    retained.getActivityId(),
                                    RecordingOperationResult.RecoveryAction
                                            .SHOW_RECOVERY_RETRY)
                            : RecordingOperationResult.recovery(
                                    RecordingOperationResult.Status
                                            .LISTENER_FAILED,
                                    0,
                                    RecordingOperationResult.RecoveryAction
                                            .RETURN_TO_START));
        }
    }

    private void runPermissionLoss(
            RecordingOperationDispatcher.Token permissionToken) {
        RecordingRecoveryState.Snapshot retained = _recoveryState.snapshot();
        int writerActivityId = permissionToken.getActivityId() > 0
                ? permissionToken.getActivityId()
                : retained.getActivityId();
        long writerGeneration = permissionToken.getWriterGeneration() > 0L
                ? permissionToken.getWriterGeneration()
                : retained.getGeneration();
        LifecycleTermination writerTermination =
                writerActivityId > 0
                        ? WRITERS.fenceGeneration(
                                writerActivityId,
                                writerGeneration,
                                WRITER_FENCE_TIMEOUT_MILLIS)
                        : WRITERS.fenceOwned(
                                this, WRITER_FENCE_TIMEOUT_MILLIS);
        LifecycleTermination listenerTermination = releaseOwnedListeners();
        if (!writerTermination.succeeded()
                || !listenerTermination.succeeded()) {
            RecordingRecoveryState.Transition recoveryTransition =
                    retained.ownsActivity()
                            ? _recoveryState.requireRecovery(
                                    retained.getActivityId(),
                                    retained.getGeneration())
                            : RecordingRecoveryState.Transition.rejected(
                                    retained);
            synchronized (this) {
                if (!operationOwnsLocked(permissionToken)) {
                    return;
                }
                if (retained.ownsActivity()) {
                    _stateMachine.restoreOwnedActivity();
                    showRecoveryRequired(
                            retained.getActivityId(),
                            recoveryTransition.isPersisted());
                } else {
                    _stateMachine.restoreIdle();
                    showIdle();
                }
            }
            postLifecycleFailure(
                    permissionToken,
                    !writerTermination.succeeded()
                            ? recoveryFailure(
                                    writerStatus(writerTermination),
                                    retained.getActivityId(),
                                    retained.ownsActivity()
                                            ? RecordingOperationResult
                                                    .RecoveryAction
                                                    .SHOW_RECOVERY_RETRY
                                            : RecordingOperationResult
                                                    .RecoveryAction
                                                    .RETURN_TO_START,
                                    recoveryTransition)
                            : recoveryFailure(
                                    listenerStatus(listenerTermination),
                                    retained.getActivityId(),
                                    retained.ownsActivity()
                                            ? RecordingOperationResult
                                                    .RecoveryAction
                                                    .SHOW_RECOVERY_RETRY
                                            : RecordingOperationResult
                                                    .RecoveryAction
                                                    .RETURN_TO_START,
                                    recoveryTransition));
            return;
        }

        RecordingRecoveryState.Transition pausedTransition = null;
        if (retained.ownsActivity()) {
            pausedTransition = _recoveryState.recordPaused(
                    retained.getActivityId(), retained.getGeneration());
            if (!pausedTransition.isPersisted()) {
                synchronized (this) {
                    if (!operationOwnsLocked(permissionToken)) {
                        return;
                    }
                    _stateMachine.restoreOwnedActivity();
                    showRecoveryRequired(retained.getActivityId(), false);
                }
                postLifecycleFailure(
                        permissionToken,
                        recoveryFailure(
                                RecordingOperationResult.Status
                                        .RECOVERY_PERSISTENCE_FAILED,
                                retained.getActivityId(),
                                RecordingOperationResult.RecoveryAction
                                        .SHOW_RECOVERY_RETRY,
                                pausedTransition));
                return;
            }
        }

        synchronized (this) {
            if (!operationOwnsLocked(permissionToken)) {
                return;
            }
            _stateMachine.completeSuccess(
                    RecordingStateMachine.Operation.SHUTDOWN);
            showIdle();
        }
        _stopWatch.resetTimer();
        SharedData.getInstance().setHeartRate(0);
        postPermissionLossCompletion(permissionToken);
    }

    private void failWithoutRecovery(
            RecordingOperationDispatcher.Token token,
            RecordingOperationResult.Status status,
            int activityId) {
        synchronized (this) {
            if (!operationOwnsLocked(token)) {
                return;
            }
            RecordingStateMachine.Operation stateOperation =
                    stateOperation(token.getOperation());
            if (stateOperation != null) {
                _stateMachine.completeFailure(stateOperation, false);
            }
            if (activityId <= 0) {
                _stateMachine.restoreIdle();
                showIdle();
            }
        }
        postOperationCompletion(
                token,
                activityId > 0
                        ? RecordingOperationResult.recovery(
                                status,
                                activityId,
                                RecordingOperationResult.RecoveryAction
                                        .SHOW_RECOVERY_RETRY)
                        : RecordingOperationResult.recovery(
                                status,
                                0,
                                RecordingOperationResult.RecoveryAction
                                        .RETURN_TO_START));
    }

    private void failWithRecovery(
            RecordingOperationDispatcher.Token token,
            RecordingOperationResult.Status status,
            int activityId,
            long writerGeneration,
            RecordingRecoveryState.Transition existingTransition) {
        if (!operationOwns(token)) {
            return;
        }
        if (activityId <= 0) {
            failWithoutRecovery(token, status, 0);
            return;
        }

        RecordingRecoveryState.Transition recoveryTransition =
                existingTransition;
        if (recoveryTransition == null
                || !recoveryTransition.isAccepted()
                || _recoveryState.snapshot().getPhase()
                        != RecordingRecoveryState.Phase.RECOVERY_REQUIRED) {
            recoveryTransition =
                    _recoveryState.requireRecovery(activityId, writerGeneration);
        }
        synchronized (this) {
            if (!operationOwnsLocked(token)) {
                return;
            }
            RecordingStateMachine.Operation stateOperation =
                    stateOperation(token.getOperation());
            if (stateOperation != null
                    && !_stateMachine.completeFailure(stateOperation, true)) {
                _stateMachine.restoreOwnedActivity();
            }
            _activityId = activityId;
            _writerGeneration = writerGeneration;
            _listenerEventsActive.set(false);
            _listenersReady = false;
            showRecoveryRequired(
                    activityId, recoveryTransition.isPersisted());
        }
        _stopWatch.pauseTimer();
        if (recoveryTransition.isCurrentProcessOnly()) {
            LifecycleTermination listenerTermination = releaseOwnedListeners();
            if (!listenerTermination.succeeded()) {
                Log.e(
                        TAG,
                        "Listener cleanup failed after recovery persistence failure: "
                                + listenerTermination);
            }
        }
        postOperationCompletion(
                token,
                recoveryFailure(
                        status,
                        activityId,
                        RecordingOperationResult.RecoveryAction
                                .SHOW_RECOVERY_RETRY,
                        recoveryTransition));
    }

    private LifecycleTermination replaceOwnedListeners(
            long serviceGeneration,
            RecordingOperationDispatcher.Token operationToken) {
        final long listenerGeneration;
        synchronized (this) {
            if (_closing
                    || !_operations.isServiceGenerationCurrent(
                            serviceGeneration)
                    || !_operations.owns(operationToken)) {
                return LifecycleTermination.FAILED;
            }
            listenerGeneration = ++_nextListenerGeneration;
            _listenerGeneration = listenerGeneration;
            _listenersReady = false;
        }
        final AtomicBoolean ownerActive = new AtomicBoolean(false);
        final Handler uiForwardingHandler =
                createListenerUiHandler(
                        serviceGeneration, listenerGeneration);
        Runnable permissionFailure = new Runnable() {
            @Override
            public void run() {
                handleRecordingPermissionLoss(
                        serviceGeneration, listenerGeneration);
            }
        };
        ListenerGroup group = new ListenerGroup(
                ownerActive,
                new SensorListener(
                        this,
                        uiForwardingHandler,
                        permissionFailure,
                        ownerActive),
                new GpsListener(
                        this,
                        uiForwardingHandler,
                        permissionFailure,
                        ownerActive),
                "sport-logger-" + System.identityHashCode(this));
        synchronized (this) {
            if (_closing
                    || !_operations.isServiceGenerationCurrent(
                            serviceGeneration)
                    || !_operations.owns(operationToken)) {
                group.requestShutdown();
                return LifecycleTermination.FAILED;
            }
            _startingListenerGroup = group;
        }
        LifecycleTermination replacement =
                LISTENERS.replace(
                        group,
                        new OwnedListenerRegistry.OwnershipClaim() {
                            @Override
                            public boolean claim() {
                                return operationOwns(operationToken);
                            }
                        },
                        LISTENER_FENCE_TIMEOUT_MILLIS);
        boolean registryOwner = LISTENERS.isOwner(group);
        boolean retained;
        synchronized (this) {
            if (_startingListenerGroup == group) {
                _startingListenerGroup = null;
            }
            retained = !_closing
                    && _operations.isServiceGenerationCurrent(serviceGeneration)
                    && _operations.owns(operationToken)
                    && _listenerGeneration == listenerGeneration
                    && registryOwner;
            if (retained) {
                _listenerGroup = group;
                _listenersReady = replacement.succeeded();
            } else {
                _listenersReady = false;
            }
        }
        if (!retained && registryOwner) {
            LifecycleTermination staleRelease =
                    LISTENERS.release(group, LISTENER_FENCE_TIMEOUT_MILLIS);
            if (!staleRelease.succeeded()) {
                Log.e(
                        TAG,
                        "Stale listener startup could not be released: "
                                + staleRelease);
            }
            return LifecycleTermination.FAILED;
        }
        return retained ? replacement : LifecycleTermination.FAILED;
    }

    private LifecycleTermination releaseOwnedListeners() {
        final ListenerGroup listenerGroup;
        synchronized (this) {
            _listenerEventsActive.set(false);
            _listenersReady = false;
            _listenerGeneration = ++_nextListenerGeneration;
            listenerGroup = _listenerGroup;
        }
        if (listenerGroup == null) {
            return LifecycleTermination.TERMINATED;
        }
        listenerGroup.requestShutdown();
        LifecycleTermination termination =
                LISTENERS.release(
                        listenerGroup, LISTENER_FENCE_TIMEOUT_MILLIS);
        synchronized (this) {
            if (termination.succeeded() && _listenerGroup == listenerGroup) {
                _listenerGroup = null;
            }
        }
        return termination;
    }

    private Handler createListenerUiHandler(
            final long serviceGeneration, final long listenerGeneration) {
        return new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message message) {
                Handler activityHandler;
                synchronized (SportLoggerService.this) {
                    if (_closing
                            || !_listenerEventsActive.get()
                            || !_operations.isServiceGenerationCurrent(
                                    serviceGeneration)
                            || _listenerGeneration != listenerGeneration) {
                        return;
                    }
                    activityHandler = Config.activityHandler;
                }
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
    }

    private void scheduleDestroyCleanup(
            final int writerActivityId,
            final long writerGeneration,
            final ListenerGroup listenerGroup,
            final ListenerGroup startingListenerGroup) {
        try {
            _destroyExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    LifecycleTermination writerTermination =
                            writerActivityId > 0
                                    ? WRITERS.fenceGeneration(
                                            writerActivityId,
                                            writerGeneration,
                                            WRITER_FENCE_TIMEOUT_MILLIS)
                                    : WRITERS.fenceOwned(
                                            SportLoggerService.this,
                                            WRITER_FENCE_TIMEOUT_MILLIS);
                    LifecycleTermination listenerTermination =
                            listenerGroup == null
                                    ? LifecycleTermination.TERMINATED
                                    : LISTENERS.release(
                                            listenerGroup,
                                            LISTENER_FENCE_TIMEOUT_MILLIS);
                    LifecycleTermination startingListenerTermination =
                            startingListenerGroup == null
                                            || startingListenerGroup
                                                    == listenerGroup
                                    ? LifecycleTermination.TERMINATED
                                    : LISTENERS.release(
                                            startingListenerGroup,
                                            LISTENER_FENCE_TIMEOUT_MILLIS);
                    if (!writerTermination.succeeded()
                            || !listenerTermination.succeeded()
                            || !startingListenerTermination.succeeded()) {
                        Log.e(
                                TAG,
                                "Destroyed service retained recovery because cleanup was incomplete: "
                                        + "writer="
                                        + writerTermination
                                        + ", listeners="
                                        + listenerTermination
                                        + ", startingListeners="
                                        + startingListenerTermination);
                    }
                }
            });
        } catch (RuntimeException exception) {
            Log.e(
                    TAG,
                    "Destroyed service could not schedule bounded cleanup; retained metadata was "
                            + "left intact.",
                    exception);
        } finally {
            _destroyExecutor.shutdown();
        }
    }

    private void initializeRetainedMirror() {
        RecordingRecoveryState.Snapshot retained = _recoveryState.snapshot();
        synchronized (this) {
            if (retained.ownsActivity()) {
                _activityId = retained.getActivityId();
                _writerGeneration = retained.getGeneration();
                _stateMachine.restoreOwnedActivity();
                showRecoveryRequired(_activityId);
            } else {
                _stateMachine.restoreIdle();
                showIdle();
            }
        }
    }

    private synchronized boolean claimActivity(
            RecordingOperationDispatcher.Token token,
            int activityId,
            long writerGeneration) {
        if (!operationOwnsLocked(token)) {
            return false;
        }
        _activityId = activityId;
        _writerGeneration = writerGeneration;
        return true;
    }

    private boolean operationOwns(
            RecordingOperationDispatcher.Token token) {
        synchronized (this) {
            return operationOwnsLocked(token);
        }
    }

    private boolean operationOwnsLocked(
            RecordingOperationDispatcher.Token token) {
        return !_closing && _operations.owns(token);
    }

    private boolean listenersReady() {
        ListenerGroup listenerGroup;
        synchronized (this) {
            if (!_startupReady
                    || !_listenersReady
                    || _listenerGroup == null) {
                return false;
            }
            listenerGroup = _listenerGroup;
        }
        return LISTENERS.isOwner(listenerGroup);
    }

    private void postPermissionLossCompletion(
            RecordingOperationDispatcher.Token permissionToken) {
        synchronized (this) {
            if (!operationOwnsLocked(permissionToken)) {
                Log.w(
                        TAG,
                        "Dropped stale permission-loss completion without notifying UI.");
                return;
            }
            _operations.finish(permissionToken);
        }
        postMainForGeneration(
                permissionToken.getServiceGeneration(),
                new Runnable() {
                    @Override
                    public void run() {
                        ISportLoggerServiceClient client;
                        synchronized (SportLoggerService.this) {
                            client = _serviceClient;
                        }
                        if (client != null) {
                            client.onRecordingPermissionLost();
                        }
                        stopSelf();
                    }
                });
    }

    private void postOperationCompletion(
            RecordingOperationDispatcher.Token token,
            RecordingOperationResult result) {
        RecordingOperationResult completion =
                RecordingOperationResult.forOperation(
                        result,
                        token.getOperation(),
                        token.getOperationToken());
        synchronized (this) {
            if (_closing
                    || !_operations.isServiceGenerationCurrent(
                            token.getServiceGeneration())
                    || !_operations.owns(token)) {
                Log.w(
                        TAG,
                        "Dropped stale operation completion without notifying UI: "
                                + token.getOperation()
                                + "/"
                                + token.getOperationToken());
                return;
            }
            _pendingOperationCompletion = completion;
            _operations.finish(token);
        }
        postMainForGeneration(
                token.getServiceGeneration(),
                new Runnable() {
                    @Override
                    public void run() {
                        deliverPendingOperationCompletion();
                    }
                });
    }

    private void postLifecycleFailure(
            RecordingOperationDispatcher.Token token,
            RecordingOperationResult failure) {
        RecordingOperationResult publishedFailure =
                RecordingOperationResult.forOperation(
                        failure,
                        token.getOperation(),
                        token.getOperationToken());
        Log.e(
                TAG,
                "Recording lifecycle failure: "
                        + publishedFailure.getStatus());
        synchronized (this) {
            if (_closing
                    || !_operations.isServiceGenerationCurrent(
                            token.getServiceGeneration())
                    || !_operations.owns(token)) {
                Log.e(
                        TAG,
                        "Late lifecycle failure retained data but did not notify stale UI: "
                                + publishedFailure.getStatus());
                return;
            }
            _pendingLifecycleFailure = publishedFailure;
            _operations.finish(token);
        }
        postMainForGeneration(token.getServiceGeneration(), new Runnable() {
            @Override
            public void run() {
                deliverPendingLifecycleFailure();
                if (publishedFailure.getRecoveryAction()
                        == RecordingOperationResult.RecoveryAction
                                .RETURN_TO_START) {
                    stopSelf();
                }
            }
        });
    }

    private void postMainForGeneration(
            final long serviceGeneration, final Runnable callback) {
        _mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (_operations.isServiceGenerationCurrent(serviceGeneration)
                        && !_closing) {
                    callback.run();
                }
            }
        });
    }

    private void deliverPendingOperationCompletion() {
        ISportLoggerServiceClient client;
        RecordingOperationResult completion;
        synchronized (this) {
            client = _serviceClient;
            completion = _pendingOperationCompletion;
            if (client == null || completion == null || _closing) {
                return;
            }
            _pendingOperationCompletion = null;
        }
        client.onRecordingOperationCompleted(completion);
    }

    private void deliverPendingLifecycleFailure() {
        ISportLoggerServiceClient client;
        RecordingOperationResult failure;
        synchronized (this) {
            client = _serviceClient;
            failure = _pendingLifecycleFailure;
            if (client == null || failure == null || _closing) {
                return;
            }
            _pendingLifecycleFailure = null;
        }
        client.onRecordingLifecycleFailure(failure);
    }

    private static RecordingStateMachine.Operation stateOperation(
            RecordingOperationResult.Operation operation) {
        switch (operation) {
            case START:
                return RecordingStateMachine.Operation.START;
            case PAUSE:
                return RecordingStateMachine.Operation.PAUSE;
            case RESUME:
                return RecordingStateMachine.Operation.RESUME;
            case STOP:
                return RecordingStateMachine.Operation.STOP;
            case DISCARD:
                return RecordingStateMachine.Operation.DISCARD;
            case RECOVER:
                return RecordingStateMachine.Operation.RECOVER;
            default:
                return null;
        }
    }

    private static RecordingStatus pendingStatus(
            RecordingOperationResult.Operation operation, int activityId) {
        switch (operation) {
            case START:
                return RecordingStatus.IDLE;
            case PAUSE:
                return RecordingStatus.RECORDING;
            case RESUME:
            case STOP:
            case DISCARD:
                return RecordingStatus.PAUSED;
            case RECOVER:
            case NONE:
                return activityId > 0
                        ? RecordingStatus.RECOVERY_REQUIRED
                        : RecordingStatus.IDLE;
            case PERMISSION_LOSS:
                return activityId > 0
                        ? RecordingStatus.RECOVERY_REQUIRED
                        : RecordingStatus.IDLE;
            default:
                return null;
        }
    }

    private static RecordingOperationResult operationResult(
            RecordingOperationResult.Status status,
            RecordingOperationResult.Operation operation,
            long operationToken,
            int activityId) {
        return RecordingOperationResult.operation(
                status,
                operation,
                operationToken,
                activityId,
                RecordingOperationResult.RecoveryAction.NONE,
                RecordingOperationResult.RecoveryRetention.NONE);
    }

    private static RecordingOperationResult.Status writerStatus(
            LifecycleTermination termination) {
        if (termination == LifecycleTermination.INTERRUPTED) {
            return RecordingOperationResult.Status.INTERRUPTED;
        }
        if (termination == LifecycleTermination.TIMED_OUT) {
            return RecordingOperationResult.Status.WRITER_TIMED_OUT;
        }
        return RecordingOperationResult.Status.WRITER_FAILED;
    }

    private static RecordingOperationResult.Status listenerStatus(
            LifecycleTermination termination) {
        if (termination == LifecycleTermination.INTERRUPTED) {
            return RecordingOperationResult.Status.INTERRUPTED;
        }
        if (termination == LifecycleTermination.TIMED_OUT) {
            return RecordingOperationResult.Status.LISTENER_TIMED_OUT;
        }
        return RecordingOperationResult.Status.LISTENER_FAILED;
    }

    private static RecordingOperationResult recoveryFailure(
            RecordingOperationResult.Status status,
            int activityId,
            RecordingOperationResult.RecoveryAction recoveryAction,
            RecordingRecoveryState.Transition recoveryTransition) {
        RecordingOperationResult.RecoveryRetention retention;
        if (activityId <= 0
                || recoveryAction
                        == RecordingOperationResult.RecoveryAction
                                .RETURN_TO_START) {
            retention = RecordingOperationResult.RecoveryRetention.NONE;
        } else if (recoveryTransition != null
                && recoveryTransition.isCurrentProcessOnly()) {
            retention =
                    RecordingOperationResult.RecoveryRetention
                            .CURRENT_PROCESS_ONLY;
        } else {
            retention = RecordingOperationResult.RecoveryRetention.DURABLE;
        }
        return RecordingOperationResult.recovery(
                status, activityId, recoveryAction, retention);
    }

    private static void showRecording(int activityId) {
        SharedData shared = SharedData.getInstance();
        shared.ActivityId = activityId;
        shared.IsRecording = true;
        shared.IsPaused = false;
        shared.RequiresRecovery = false;
        shared.RecoveryCurrentProcessOnly = false;
    }

    private static void showPausedRecording(int activityId) {
        SharedData shared = SharedData.getInstance();
        shared.ActivityId = activityId;
        shared.IsRecording = true;
        shared.IsPaused = true;
        shared.RequiresRecovery = false;
        shared.RecoveryCurrentProcessOnly = false;
    }

    private static void showRecoveryRequired(int activityId) {
        showRecoveryRequired(activityId, true);
    }

    private static void showRecoveryRequired(
            int activityId, boolean recoveryPersisted) {
        SharedData shared = SharedData.getInstance();
        shared.ActivityId = activityId;
        shared.IsRecording = true;
        shared.IsPaused = true;
        shared.RequiresRecovery = true;
        shared.RecoveryCurrentProcessOnly = !recoveryPersisted;
    }

    private static void showIdle() {
        SharedData shared = SharedData.getInstance();
        shared.ActivityId = 0;
        shared.IsRecording = false;
        shared.IsPaused = false;
        shared.RequiresRecovery = false;
        shared.RecoveryCurrentProcessOnly = false;
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
        channel.setDescription(
                getString(R.string.notification_channel_description));
        _notificationManager.createNotificationChannel(channel);
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                        .setWhen(System.currentTimeMillis())
                        .setContentTitle(getString(R.string.notification_title))
                        .setContentText(getString(R.string.notification_text))
                        .setSmallIcon(
                                R.drawable.ic_play_circle_outline_black_24dp)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setContentIntent(pending);

        int serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        ServiceCompat.startForeground(
                this, NOTIFICATION_ID, builder.build(), serviceTypes);
    }
}
