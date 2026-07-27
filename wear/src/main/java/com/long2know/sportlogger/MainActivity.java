package com.long2know.sportlogger;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.os.Bundle;
import android.os.Build;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;

import android.view.MenuItem;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Lifecycle;
import androidx.wear.ambient.AmbientModeSupport;
import androidx.wear.widget.drawer.WearableActionDrawerView;
import androidx.wear.widget.drawer.WearableNavigationDrawerView;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.long2know.sportlogger.services.ISportLoggerServiceClient;
import com.long2know.sportlogger.services.RecordingOperationResult;
import com.long2know.sportlogger.services.SportLoggerService;
import com.long2know.utilities.data_access.SqlLogger;
import com.long2know.utilities.models.Config;
import com.long2know.utilities.models.LocationData;
import com.long2know.utilities.models.Session;
import com.long2know.utilities.models.SharedData;
import com.long2know.utilities.models.SportActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends FragmentActivity implements
        AmbientModeSupport.AmbientCallbackProvider,
        MenuItem.OnMenuItemClickListener,
        WearableNavigationDrawerView.OnItemSelectedListener,
        ActivityCompat.OnRequestPermissionsResultCallback,
        ISportLoggerServiceClient {
    private static final int FOREGROUND_PERMISSION_REQUEST_CODE = 1;
    private static final int BACKGROUND_SENSOR_PERMISSION_REQUEST_CODE = 2;
    private static final String TAG = "MainActivity";

    private SensorFragment _sensorFragment;
    private StartActivityFragment _startFragment;
    private EndActivityFragment _endFragment;
    private RecoveryActivityFragment _recoveryFragment;
    private FragmentManager _fragmentManager;
    private WearableNavigationDrawerView _wearableNavigationDrawer;
    private WearableActionDrawerView _wearableActionDrawer;

    private SportLoggerService _loggingService;
    private ServiceConnection _loggingServiceConnection;
    private Intent _serviceIntent;
    private Handler _activityHandler;
    private boolean _permissionRequestInFlight;
    private boolean _permissionLossMessagePending;
    private boolean _recoveryRetryPending;
    private long _pendingOperationToken;
    private RecordingOperationResult.Operation _pendingOperation =
            RecordingOperationResult.Operation.NONE;
    private final ExecutorService _exportExecutor =
            Executors.newSingleThreadExecutor();
    private final RecordingUiState _recordingUiState = new RecordingUiState();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Enables Ambient mode.
         AmbientModeSupport.attach(this);
        Config.activityContext = this;

        // Initialize the fragments and set set initial content.
        Bundle sargs = new Bundle();
        Bundle aargs = new Bundle();
        Bundle eargs = new Bundle();
        _sensorFragment = new SensorFragment();
        _sensorFragment.setArguments(sargs);
        _startFragment = new StartActivityFragment();
        _startFragment.setArguments(aargs);
        _endFragment = new EndActivityFragment();
        _endFragment.setArguments(eargs);
        _recoveryFragment = new RecoveryActivityFragment();
        _fragmentManager = this.getSupportFragmentManager();

//        _fragmentManager.beginTransaction().replace(R.id.content_frame, _sensorFragment).commit();

        // Top Navigation Drawer
        _wearableNavigationDrawer = findViewById(R.id.top_navigation_drawer);
        _wearableNavigationDrawer.setAdapter(new NavigationAdapter(this));
        // Peeks navigation drawer on the top.
//        _wearableNavigationDrawer.getController().peekDrawer();
        _wearableNavigationDrawer.addOnItemSelectedListener(this);

        // Bottom Action Drawer
        _wearableActionDrawer = findViewById(R.id.bottom_action_drawer);
        // Peeks action drawer on the bottom.
        _wearableActionDrawer.setOnMenuItemClickListener(this);

        // Start out making the drawer locked out of sight
        _wearableActionDrawer.setIsLocked(true);

        // Create a handler for the UI thread
        // Defines a Handler object that's attached to the UI thread
        _activityHandler = new Handler(Looper.getMainLooper()) {
            public void handleMessage(Message msg) {
                int messageType = msg.what;

                if (messageType == 0) {
                    LocationData data = (LocationData) msg.obj;
                    _sensorFragment.updateLocation(data);
                } else {
                    int sensorType = msg.arg1;
                    SensorEvent event = (SensorEvent) msg.obj;

                    switch (sensorType) {
                        case Sensor.TYPE_HEART_RATE: {
                            _sensorFragment.updateHeartRate(event.values[0]);
                            break;
                        }
                        case Sensor.TYPE_STEP_COUNTER: {
                            // _steps.setText(Float.toString(event.values[0]));
                            break;
                        }
                        case Sensor.TYPE_STEP_DETECTOR: {
                            _sensorFragment.updateSteps(SharedData.getInstance().getData().Steps);
                            break;
                        }
                        default: {
                            // Do nothing ..
                        }
                    }
                }
            }
        };
        Config.activityHandler = _activityHandler;

        requestMissingPermissions();

        renderRecordingStatus(recordingStatusFromSharedData());

        _loggingServiceConnection = new ServiceConnection() {
            public void onServiceDisconnected(ComponentName name)            {
                _loggingService = null;
                Session.setBoundToService(false);
                clearPendingOperation();
                reconcileRecordingUi();
            }
            public void onServiceConnected(ComponentName name, IBinder service)            {
                _loggingService = ((SportLoggerService.LocalBinder) service).getService();
                _loggingService.setServiceClient(MainActivity.this);
                syncPendingOperationFromService();
                if (_recoveryRetryPending) {
                    _recoveryRetryPending = false;
                    retryRecordingRecovery();
                    return;
                }
                reconcileRecordingUi();
            }
        };
    }

    @Override
    protected void onStart() {
        super.onStart();
        startAndBindServiceIfPermitted();
        reconcileRecordingUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reconcileRecordingPermissions();
        reconcileRecordingUi();
    }

    @Override
    public void onDestroy() {
        if (SharedData.getInstance().IsRecording) {
            if (Session.isBoundToService() && _loggingServiceConnection != null) {
                unbindService(_loggingServiceConnection);
                Session.setBoundToService(false);
            }
        } else {
            stopAndUnbindServiceIfRequired();
        }

        if (_loggingService != null) {
            _loggingService.clearServiceClient(this);
        }
        if (Config.activityHandler == _activityHandler) {
            Config.activityHandler = null;
        }
        if (Config.activityContext == this) {
            Config.activityContext = null;
        }
        _exportExecutor.shutdownNow();
        super.onDestroy();
    }

    public void onLoggerUpdate(SharedData data) {

    }

    @Override
    public void onRecordingPermissionLost() {
        clearPendingOperation();
        boolean canPresentPermissionUi = canPresentPermissionUi();
        _permissionLossMessagePending = !canPresentPermissionUi;
        handleMissingRecordingPermissions(canPresentPermissionUi);
        if (canPresentPermissionUi) {
            requestMissingPermissions();
        }
    }

    @Override
    public void onRecordingOperationCompleted(RecordingOperationResult result) {
        if (_pendingOperationToken != 0L
                && result.getOperationToken() != 0L
                && result.getOperationToken() != _pendingOperationToken) {
            Log.w(
                    TAG,
                    "Ignored stale operation completion "
                            + result.getOperation()
                            + "/"
                            + result.getOperationToken());
            return;
        }
        clearPendingOperation();
        if (!result.isSuccess()) {
            if (result.isNoOp()) {
                reconcileRecordingUi();
            } else {
                handleRecordingFailure(result);
            }
            return;
        }

        switch (result.getOperation()) {
            case START:
                _sensorFragment.startTImer();
                showRecordingScreenIfPossible();
                break;
            case PAUSE:
                _sensorFragment.pauseTimer();
                showPausedScreenIfPossible();
                break;
            case RESUME:
                _sensorFragment.startTImer();
                showRecordingScreenIfPossible();
                break;
            case STOP:
                _sensorFragment.pauseTimer();
                _sensorFragment.resetTimer();
                exportActivityAsync(result.getActivityId());
                showStartScreenIfPossible();
                break;
            case DISCARD:
                _sensorFragment.pauseTimer();
                _sensorFragment.resetTimer();
                showStartScreenIfPossible();
                break;
            case RECOVER:
                renderRecordingStatus(_loggingService == null
                        ? recordingStatusFromSharedData()
                        : _loggingService.getRecordingStatus());
                Toast.makeText(
                        this,
                        R.string.recording_recovery_ready,
                        Toast.LENGTH_SHORT)
                        .show();
                break;
            case STARTUP:
                if (_recoveryRetryPending) {
                    _recoveryRetryPending = false;
                    retryRecordingRecovery();
                    break;
                }
                reconcileRecordingUi();
                break;
            default:
                reconcileRecordingUi();
        }
    }

    @Override
    public void onRecordingLifecycleFailure(RecordingOperationResult result) {
        clearPendingOperation();
        handleRecordingFailure(result);
        if (result.getRecoveryAction()
                == RecordingOperationResult.RecoveryAction.RETURN_TO_START) {
            stopLoggingServiceForMissingPermissions();
        }
    }

    // Start the logger service and bind the activity to the service
    private void startAndBindServiceIfPermitted() {
        if (!hasRequiredRecordingPermissions() || Session.isBoundToService()) {
            return;
        }

        _serviceIntent = new Intent(this, SportLoggerService.class);

        // Start the service in case it isn't already running
//        startForegroundService(_serviceIntent);
//        startService(_serviceIntent);
        ContextCompat.startForegroundService(this, _serviceIntent);

        // Now bind to service
        boolean bound = bindService(
                _serviceIntent,
                _loggingServiceConnection,
                Context.BIND_AUTO_CREATE);
        Session.setBoundToService(bound);
    }

    // Start the logger service and bind the activity to the service
    private void stopAndUnbindServiceIfRequired() {
        if(Session.isBoundToService())        {
            unbindService(_loggingServiceConnection);
            Session.setBoundToService(false);
        }

        if (!Session.isStarted() && _serviceIntent != null) {
            //serviceIntent = new Intent(this, GpsLoggingService.class);
            stopService(_serviceIntent);
        }
    }

    public void startNewActivity() {
        if (!ensureLoggingService()) {
            return;
        }
        handleOperationRequest(_loggingService.startNewActivity());
    }

    public void stopActivity() {
        if (!ensureLoggingService()) {
            return;
        }
        handleOperationRequest(_loggingService.stopActivity());
    }

    public void pauseActivity() {
        if (!ensureLoggingService()) {
            return;
        }
        handleOperationRequest(_loggingService.pauseActivity());
    }

    public void resumeActivity() {
        if (!ensureLoggingService()) {
            return;
        }
        handleOperationRequest(_loggingService.resumeActivity());
    }

    public void discardActivity() {
        if (!ensureLoggingService()) {
            return;
        }
        handleOperationRequest(_loggingService.discardActivity());
    }

    public void retryRecordingRecovery() {
        if (!ensureLoggingService()) {
            _recoveryRetryPending = true;
            return;
        }
        RecordingOperationResult result = _loggingService.retryRecovery();
        _recoveryRetryPending =
                result.isPending()
                        && result.getOperation()
                                == RecordingOperationResult.Operation.STARTUP;
        handleOperationRequest(result);
    }

    private void handleOperationRequest(RecordingOperationResult result) {
        if (result.isAccepted() || result.isPending()) {
            _pendingOperation = result.getOperation();
            _pendingOperationToken = result.getOperationToken();
            setRecordingControlsPending(true);
            return;
        }
        if (result.isNoOp()) {
            reconcileRecordingUi();
            return;
        }
        handleRecordingFailure(result);
    }

    private void clearPendingOperation() {
        _pendingOperation = RecordingOperationResult.Operation.NONE;
        _pendingOperationToken = 0L;
        setRecordingControlsPending(false);
    }

    private void setRecordingControlsPending(boolean pending) {
        if (_startFragment != null) {
            _startFragment.setOperationPending(pending);
        }
        if (_endFragment != null) {
            _endFragment.setOperationPending(pending);
        }
        if (_recoveryFragment != null) {
            _recoveryFragment.setOperationPending(pending);
        }
        if (_wearableActionDrawer != null) {
            _wearableActionDrawer.setIsLocked(
                    pending
                            || recordingStatusFromSharedData()
                                    != SportLoggerService.RecordingStatus
                                            .RECORDING);
        }
    }

    private void exportActivityAsync(final int activityId) {
        try {
            _exportExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        SqlLogger sqlLogger = new SqlLogger();
                        SportActivity activity =
                                sqlLogger.getSportActivity(activityId);
                        activity.SportTrackPoints =
                                sqlLogger.getTrackPointsByActivity(activityId);
                        byte[] bytes = SportActivity.serialize(activity);
                        Asset asset = Asset.createFromBytes(bytes);
                        PutDataMapRequest dataMap =
                                PutDataMapRequest.create(
                                        getString(R.string.wear_path));
                        dataMap.getDataMap().putAsset("sportActivity", asset);
                        PutDataRequest request = dataMap.asPutDataRequest();
                        Task<DataItem> putTask =
                                Wearable.getDataClient(MainActivity.this)
                                        .putDataItem(request);
                        putTask.addOnFailureListener(exception ->
                                Log.e(
                                        TAG,
                                        "Could not transmit retained activity "
                                                + activityId
                                                + ".",
                                        exception));
                    } catch (Exception exception) {
                        Log.e(
                                TAG,
                                "Could not export retained activity "
                                        + activityId
                                        + ".",
                                exception);
                    }
                }
            });
        } catch (RuntimeException exception) {
            Log.e(
                    TAG,
                    "Activity export was not scheduled; database data remains retained for "
                            + activityId
                            + ".",
                    exception);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == FOREGROUND_PERMISSION_REQUEST_CODE) {
            _permissionRequestInFlight = false;
            if (hasRequiredWhileInUseRecordingPermissions()) {
                requestBackgroundSensorPermissionOrStartService();
            } else {
                handleMissingRecordingPermissions(
                        true, R.string.recording_permissions_required);
            }
        } else if (requestCode == BACKGROUND_SENSOR_PERMISSION_REQUEST_CODE) {
            _permissionRequestInFlight = false;
            if (hasRequiredRecordingPermissions()) {
                startAndBindServiceIfPermitted();
            } else {
                handleMissingRecordingPermissions(
                        true, R.string.background_sensor_permission_required);
            }
        }
    }

    private boolean hasRequiredRecordingPermissions() {
        return RecordingPermissions.allRequiredForRecordingGranted(this);
    }

    private boolean hasRequiredWhileInUseRecordingPermissions() {
        return RecordingPermissions.allWhileInUseRequiredForRecordingGranted(this);
    }

    private boolean ensureLoggingService() {
        if (!hasRequiredRecordingPermissions()) {
            handleMissingRecordingPermissions(true);
            requestMissingPermissions();
            return false;
        }
        if (_loggingService != null) {
            return true;
        }
        startAndBindServiceIfPermitted();
        return false;
    }

    private void requestMissingPermissions() {
        if (_permissionRequestInFlight) {
            return;
        }

        List<String> missingPermissions = new ArrayList<>();
        for (String permission :
                RecordingPermissions.requestedOnStartup(
                        Build.VERSION.SDK_INT,
                        getApplicationInfo().targetSdkVersion)) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }

        if (!missingPermissions.isEmpty()) {
            _permissionRequestInFlight = true;
            ActivityCompat.requestPermissions(
                    this,
                    missingPermissions.toArray(new String[0]),
                    FOREGROUND_PERMISSION_REQUEST_CODE);
            return;
        }

        requestBackgroundSensorPermission();
    }

    private boolean requestBackgroundSensorPermission() {
        if (_permissionRequestInFlight || !hasRequiredWhileInUseRecordingPermissions()) {
            return false;
        }

        String permission = RecordingPermissions.backgroundSensorPermissionForRecording(
                Build.VERSION.SDK_INT,
                getApplicationInfo().targetSdkVersion);
        if (permission == null
                || ContextCompat.checkSelfPermission(this, permission)
                == PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        _permissionRequestInFlight = true;
        ActivityCompat.requestPermissions(
                this,
                new String[]{permission},
                BACKGROUND_SENSOR_PERMISSION_REQUEST_CODE);
        return true;
    }

    private void requestBackgroundSensorPermissionOrStartService() {
        if (!requestBackgroundSensorPermission()) {
            startAndBindServiceIfPermitted();
        }
    }

    private void reconcileRecordingPermissions() {
        if (hasRequiredRecordingPermissions()) {
            startAndBindServiceIfPermitted();
            return;
        }

        boolean requestAfterPermissionLoss = _permissionLossMessagePending;
        _permissionLossMessagePending = false;
        handleMissingRecordingPermissions(requestAfterPermissionLoss);
        if (requestAfterPermissionLoss) {
            requestMissingPermissions();
        }
    }

    private void handleMissingRecordingPermissions(boolean showMessage) {
        int messageResource = hasRequiredWhileInUseRecordingPermissions()
                && !RecordingPermissions.backgroundSensorPermissionGranted(this)
                ? R.string.background_sensor_permission_required
                : R.string.recording_permissions_required;
        handleMissingRecordingPermissions(showMessage, messageResource);
    }

    private void handleMissingRecordingPermissions(boolean showMessage, int messageResource) {
        SharedData shared = SharedData.getInstance();
        boolean recordingWasActive = shared.IsRecording || shared.IsPaused;
        if (recordingWasActive && _loggingService != null) {
            if (_sensorFragment != null) {
                _sensorFragment.pauseTimer();
            }
            _loggingService.recordingPermissionsRevoked();
            return;
        }
        if (recordingWasActive) {
            shared.IsPaused = true;
            shared.RequiresRecovery = true;
            if (_sensorFragment != null) {
                _sensorFragment.pauseTimer();
            }
            handleRecordingFailure(RecordingOperationResult.recovery(
                    RecordingOperationResult.Status.LISTENER_FAILED,
                    shared.ActivityId,
                    RecordingOperationResult.RecoveryAction.SHOW_RECOVERY_RETRY,
                    shared.RecoveryCurrentProcessOnly
                            ? RecordingOperationResult.RecoveryRetention
                                    .CURRENT_PROCESS_ONLY
                            : RecordingOperationResult.RecoveryRetention.DURABLE));
            return;
        }

        shared.IsRecording = false;
        shared.IsPaused = false;
        shared.RequiresRecovery = false;
        shared.RecoveryCurrentProcessOnly = false;

        if (_sensorFragment != null) {
            _sensorFragment.pauseTimer();
            if (recordingWasActive) {
                _sensorFragment.resetTimer();
            }
        }

        stopLoggingServiceForMissingPermissions();
        showStartScreenIfPossible();

        if (showMessage) {
            Toast.makeText(
                    this,
                    messageResource,
                    Toast.LENGTH_SHORT)
                    .show();
        }
    }

    private void stopLoggingServiceForMissingPermissions() {
        if (_loggingService != null) {
            _loggingService.clearServiceClient(this);
        }
        if (Session.isBoundToService() && _loggingServiceConnection != null) {
            unbindService(_loggingServiceConnection);
            Session.setBoundToService(false);
        }
        _loggingService = null;

        if (_serviceIntent != null) {
            stopService(_serviceIntent);
            _serviceIntent = null;
        }
    }

    private boolean canPresentPermissionUi() {
        return !isFinishing()
                && !isDestroyed()
                && getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED);
    }

    private void showStartScreenIfPossible() {
        _recordingUiState.requestStatus(RecordingUiState.Screen.START);
        applyPendingRecordingRenderIfSafe();
    }

    private void showPausedScreenIfPossible() {
        _recordingUiState.requestStatus(RecordingUiState.Screen.PAUSED);
        applyPendingRecordingRenderIfSafe();
    }

    private void showRecordingScreenIfPossible() {
        _recordingUiState.requestStatus(RecordingUiState.Screen.RECORDING);
        applyPendingRecordingRenderIfSafe();
    }

    private void renderRecordingStatus(SportLoggerService.RecordingStatus status) {
        if (status == SportLoggerService.RecordingStatus.RECOVERY_REQUIRED) {
            _recordingUiState.requestStatus(RecordingUiState.Screen.RECOVERY);
        } else if (status == SportLoggerService.RecordingStatus.PAUSED) {
            _recordingUiState.requestStatus(RecordingUiState.Screen.PAUSED);
        } else if (status == SportLoggerService.RecordingStatus.RECORDING) {
            _recordingUiState.requestStatus(RecordingUiState.Screen.RECORDING);
        } else {
            _recordingUiState.requestStatus(RecordingUiState.Screen.START);
        }
        applyPendingRecordingRenderIfSafe();
    }

    private void handleRecordingFailure(RecordingOperationResult result) {
        _sensorFragment.pauseTimer();
        RecordingOperationResult.RecoveryAction recoveryAction =
                result.getRecoveryAction();
        RecordingUiState.Screen screen = recordingScreenFromSharedData();
        SharedData shared = SharedData.getInstance();
        if (recoveryAction
                == RecordingOperationResult.RecoveryAction.RETURN_TO_START) {
            shared.ActivityId = 0;
            shared.IsRecording = false;
            shared.IsPaused = false;
            shared.RequiresRecovery = false;
            shared.RecoveryCurrentProcessOnly = false;
            screen = RecordingUiState.Screen.START;
        } else if (recoveryAction
                == RecordingOperationResult.RecoveryAction.SHOW_PAUSED_CONTROLS) {
            shared.ActivityId = result.getActivityId();
            shared.IsRecording = true;
            shared.IsPaused = true;
            shared.RequiresRecovery = false;
            shared.RecoveryCurrentProcessOnly = false;
            screen = RecordingUiState.Screen.PAUSED;
        } else if (recoveryAction
                == RecordingOperationResult.RecoveryAction.SHOW_RECOVERY_RETRY) {
            if (result.getActivityId() > 0) {
                shared.ActivityId = result.getActivityId();
                shared.IsRecording = true;
                shared.IsPaused = true;
            }
            shared.RequiresRecovery = true;
            shared.RecoveryCurrentProcessOnly =
                    result.getRecoveryRetention()
                            == RecordingOperationResult.RecoveryRetention
                                    .CURRENT_PROCESS_ONLY;
            screen = RecordingUiState.Screen.RECOVERY;
        }

        _recordingUiState.requestFailure(screen, result);
        applyPendingRecordingRenderIfSafe();
    }

    private void reconcileRecordingUi() {
        syncPendingOperationFromService();
        renderRecordingStatus(
                _loggingService == null
                        ? recordingStatusFromSharedData()
                        : _loggingService.getRecordingStatus());
    }

    private void syncPendingOperationFromService() {
        if (_loggingService == null) {
            return;
        }
        RecordingOperationResult pending =
                _loggingService.getPendingOperation();
        if (pending != null) {
            _pendingOperation = pending.getOperation();
            _pendingOperationToken = pending.getOperationToken();
            setRecordingControlsPending(true);
        }
    }

    private SportLoggerService.RecordingStatus recordingStatusFromSharedData() {
        SharedData shared = SharedData.getInstance();
        if (shared.RequiresRecovery) {
            return SportLoggerService.RecordingStatus.RECOVERY_REQUIRED;
        }
        if (shared.IsRecording && !shared.IsPaused) {
            return SportLoggerService.RecordingStatus.RECORDING;
        }
        if (shared.IsPaused) {
            return SportLoggerService.RecordingStatus.PAUSED;
        }
        return SportLoggerService.RecordingStatus.IDLE;
    }

    private RecordingUiState.Screen recordingScreenFromSharedData() {
        SportLoggerService.RecordingStatus status = recordingStatusFromSharedData();
        if (status == SportLoggerService.RecordingStatus.RECOVERY_REQUIRED) {
            return RecordingUiState.Screen.RECOVERY;
        }
        if (status == SportLoggerService.RecordingStatus.PAUSED) {
            return RecordingUiState.Screen.PAUSED;
        }
        if (status == SportLoggerService.RecordingStatus.RECORDING) {
            return RecordingUiState.Screen.RECORDING;
        }
        return RecordingUiState.Screen.START;
    }

    private void applyPendingRecordingRenderIfSafe() {
        boolean safe = _fragmentManager != null
                && !_fragmentManager.isStateSaved()
                && !isFinishing()
                && !isDestroyed()
                && getLifecycle().getCurrentState().isAtLeast(
                        Lifecycle.State.CREATED);
        _recordingUiState.renderIfSafe(safe, this::renderRecordingUiNow);
    }

    private void renderRecordingUiNow(
            RecordingUiState.Screen screen, RecordingOperationResult failure) {
        if (screen == RecordingUiState.Screen.RECOVERY) {
            _fragmentManager.beginTransaction()
                    .replace(R.id.content_frame, _recoveryFragment)
                    .commit();
            _recoveryFragment.refreshStatus();
            _wearableActionDrawer.setIsLocked(true);
            _wearableActionDrawer.getController().closeDrawer();
        } else if (screen == RecordingUiState.Screen.PAUSED) {
            _fragmentManager.beginTransaction()
                    .replace(R.id.content_frame, _endFragment)
                    .commit();
            _wearableActionDrawer.setIsLocked(true);
            _wearableActionDrawer.getController().closeDrawer();
        } else if (screen == RecordingUiState.Screen.RECORDING) {
            _fragmentManager.beginTransaction()
                    .replace(R.id.content_frame, _sensorFragment)
                    .commit();
            _wearableActionDrawer.setIsLocked(
                    _pendingOperation
                            != RecordingOperationResult.Operation.NONE);
            _wearableActionDrawer.getController().peekDrawer();
        } else {
            _fragmentManager.beginTransaction()
                    .replace(R.id.content_frame, _startFragment)
                    .commit();
            _wearableActionDrawer.setIsLocked(true);
            _wearableActionDrawer.getController().closeDrawer();
        }

        if (failure == null) {
            return;
        }
        int message;
        int duration = Toast.LENGTH_LONG;
        if (failure.getRecoveryRetention()
                == RecordingOperationResult.RecoveryRetention
                        .CURRENT_PROCESS_ONLY) {
            message = R.string.recording_recovery_not_persisted;
        } else if (failure.getRecoveryAction()
                == RecordingOperationResult.RecoveryAction.RETURN_TO_START) {
            message = R.string.recording_startup_failed;
        } else if (failure.getRecoveryAction()
                == RecordingOperationResult.RecoveryAction.SHOW_PAUSED_CONTROLS) {
            message = R.string.recording_shutdown_failed;
        } else if (failure.getRecoveryAction()
                == RecordingOperationResult.RecoveryAction.SHOW_RECOVERY_RETRY) {
            message = R.string.recording_recovery_required;
        } else {
            message = R.string.recording_operation_failed;
            duration = Toast.LENGTH_SHORT;
        }
        Toast.makeText(this, message, duration).show();
    }

    @Override
    public boolean onMenuItemClick(MenuItem menuItem) {
        Log.d(TAG, "onMenuItemClick(): " + menuItem);
        final int itemId = menuItem.getItemId();

        if (itemId == R.id.menu_pause) {
            this.pauseActivity();
        }

//        String toastMessage = "";
//
//        switch (itemId) {
//            case R.id.menu_planet_name:
//                toastMessage = mSolarSystem.get(mSelectedPlanet).getName();
//                break;
//            case R.id.menu_number_of_moons:
//                toastMessage = mSolarSystem.get(mSelectedPlanet).getMoons();
//                break;
//            case R.id.menu_volume:
//                toastMessage = mSolarSystem.get(mSelectedPlanet).getVolume();
//                break;
//            case R.id.menu_surface_area:
//                toastMessage = mSolarSystem.get(mSelectedPlanet).getSurfaceArea();
//                break;
//        }
//
//        mWearableActionDrawer.getController().closeDrawer();

//        if (toastMessage.length() > 0) {
//            Toast toast = Toast.makeText(
//                    getApplicationContext(),
//                    toastMessage,
//                    Toast.LENGTH_SHORT);
//            toast.show();
//            return true;
//        } else {
//            return false;
//        }
        return true;
    }

    // Updates content when user changes between items in the navigation drawer.
    @Override
    public void onItemSelected(int position) {
        Log.d(TAG, "WearableNavigationDrawerView triggered onItemSelected(): " + position);
//        mSelectedPlanet = position;
//
//        String selectedPlanetImage = mSolarSystem.get(mSelectedPlanet).getImage();
//        int drawableId =
//                getResources().getIdentifier(selectedPlanetImage, "drawable", getPackageName());
//        mPlanetFragment.updatePlanet(drawableId);
    }

    @Override
    public AmbientModeSupport.AmbientCallback getAmbientCallback() {
        return new MyAmbientCallback();
    }

    private class MyAmbientCallback extends AmbientModeSupport.AmbientCallback {
        /**
         * Prepares the UI for ambient mode.
         */
        @Override
        public void onEnterAmbient(Bundle ambientDetails) {
            super.onEnterAmbient(ambientDetails);
            Log.d(TAG, "onEnterAmbient() " + ambientDetails);

            _sensorFragment.onEnterAmbientInFragment(ambientDetails);
//            mWearableNavigationDrawer.getController().closeDrawer();
//            mWearableActionDrawer.getController().closeDrawer();
        }

        /**
         * Restores the UI to active (non-ambient) mode.
         */
        @Override
        public void onExitAmbient() {
            super.onExitAmbient();
            Log.d(TAG, "onExitAmbient()");

            _sensorFragment.onExitAmbientInFragment();
//            mWearableActionDrawer.getController().peekDrawer();
        }


    }

    private final class NavigationAdapter
            extends WearableNavigationDrawerView.WearableNavigationDrawerAdapter {

        private final Context _context;

        public NavigationAdapter(Context context) {
            _context = context;
        }

        @Override
        public int getCount() {
            return 0;
        }

        @Override
        public String getItemText(int pos) {
            return "";
        }

        @Override
        public Drawable getItemDrawable(int pos) {
            return null;
//            String navigationIcon = mSolarSystem.get(pos).getNavigationIcon();
//
//            int drawableNavigationIconId =
//                    getResources().getIdentifier(navigationIcon, "drawable", getPackageName());
//
//            return _context.getDrawable(drawableNavigationIconId);
        }

        /**
         * Stops the service if it isn't logging. Also unbinds.
         */
        private void stopAndUnbindServiceIfRequired() {
            if(Session.isBoundToService()) {
                unbindService(_loggingServiceConnection);
                Session.setBoundToService(false);
            }

            if(!Session.isStarted())            {
                //serviceIntent = new Intent(this, GpsLoggingService.class);
                stopService(_serviceIntent);
            }

        }
    }
}