package com.long2know.sportlogger

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.wear.ambient.AmbientLifecycleObserver
import com.long2know.sportlogger.acquisition.RecordingPermissionPolicy
import com.long2know.sportlogger.acquisition.RouteStatus
import com.long2know.sportlogger.acquisition.healthservices.HealthServicesExerciseSnapshot
import com.long2know.sportlogger.recording.RecordingSnapshot
import com.long2know.sportlogger.recording.RecordingState
import com.long2know.sportlogger.services.ISportLoggerServiceClient
import com.long2know.sportlogger.services.SportLoggerService
import com.long2know.sportlogger.sync.WatchSyncEvents
import com.long2know.sportlogger.sync.WatchSyncFinishHook
import com.long2know.sportlogger.ui.ActivityChoice
import com.long2know.sportlogger.ui.ConfirmationAction
import com.long2know.sportlogger.ui.ServiceClientRegistration
import com.long2know.sportlogger.ui.ServiceConnectionState
import com.long2know.sportlogger.ui.WatchAppActions
import com.long2know.sportlogger.ui.WatchSummarySource
import com.long2know.sportlogger.ui.WatchUiMapper
import com.long2know.sportlogger.ui.WatchUiState
import com.long2know.sportlogger.ui.WearSportLoggerApp
import com.long2know.utilities.models.SharedData

class MainActivity :
    ComponentActivity(),
    ISportLoggerServiceClient {

    private val preferences by lazy {
        getSharedPreferences(UI_PREFERENCES, Context.MODE_PRIVATE)
    }
    private val summarySource by lazy { WatchSummarySource(this) }
    private var uiState by mutableStateOf(WatchUiState())
    private var latestRecordingSnapshot: RecordingSnapshot? = null
    private var latestSharedData: SharedData = SharedData.getInstance()
    private var service: SportLoggerService? = null
    private var bindRequested = false
    private var receiverRegistered = false
    private var pendingStart: ActivityChoice? = null
    private lateinit var ambientObserver: AmbientLifecycleObserver
    private val clientRegistration = ServiceClientRegistration<SportLoggerService>(
        register = { connected -> connected.registerClient(this) },
        unregister = { connected -> connected.unregisterClient(this) },
    )
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            updatePermissionState()
            service?.refreshAcquisitionState()
            pendingStart?.let { choice ->
                pendingStart = null
                startSelectedActivity(choice)
            }
        }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!bindRequested) {
                return
            }
            val connected = (binder as SportLoggerService.LocalBinder).service
            if (!clientRegistration.serviceConnected(connected)) {
                return
            }
            service = connected
            val supportedActivities = ActivityChoice.entries
                .filter { it.canonicalType in connected.supportedActivityTypes }
                .toSet()
            uiState = uiState.copy(
                serviceConnection = ServiceConnectionState.CONNECTED,
                supportedActivities = supportedActivities,
                activitySupport = WatchUiMapper.activitySupport(
                    uiState.selectedActivity,
                    uiState.acquisition,
                    supportedActivities,
                ),
            )
            connected.refreshAcquisitionState()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            clientRegistration.serviceDisconnected()
            service = null
            uiState = uiState.copy(serviceConnection = ServiceConnectionState.DISCONNECTED)
        }

        override fun onBindingDied(name: ComponentName) {
            handleDeadBinding(rebind = true)
        }

        override fun onNullBinding(name: ComponentName) {
            handleDeadBinding(rebind = false)
        }
    }

    private val syncStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            summarySource.refreshLatest()
        }
    }

    private val actions by lazy {
        WatchAppActions(
            onSelectActivity = ::selectActivity,
            onRequestPermissions = ::requestMissingPermissions,
            onOpenSettings = {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName"),
                    ),
                )
            },
            onRefreshReadiness = ::refreshReadiness,
            onStart = ::startActivity,
            onPause = { runServiceCommand { it.pauseActivity() } },
            onResume = { runServiceCommand { it.resumeActivity() } },
            onLap = ::recordLap,
            onRequestStop = {
                uiState = uiState.copy(confirmation = ConfirmationAction.STOP)
            },
            onRequestDiscard = {
                uiState = uiState.copy(confirmation = ConfirmationAction.DISCARD)
            },
            onConfirmStop = {
                uiState = uiState.copy(confirmation = null)
                runServiceCommand { it.stopActivity() }
            },
            onConfirmDiscard = {
                uiState = uiState.copy(confirmation = null)
                runServiceCommand { it.discardActivity() }
            },
            onCancelConfirmation = {
                uiState = uiState.copy(confirmation = null)
            },
            onRetrySync = {
                WatchSyncFinishHook.requestRetry(this)
                summarySource.refreshLatest()
            },
            onDismissSummary = ::dismissSummary,
            onDismissError = {
                uiState = uiState.copy(errorCode = null, errorMessage = null)
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ambientObserver = AmbientLifecycleObserver(
            this,
            object : AmbientLifecycleObserver.AmbientLifecycleCallback {
                override fun onEnterAmbient(
                    ambientDetails: AmbientLifecycleObserver.AmbientDetails,
                ) {
                    uiState = uiState.copy(ambient = true)
                }

                override fun onExitAmbient() {
                    uiState = uiState.copy(ambient = false)
                    latestRecordingSnapshot?.let { snapshot ->
                        uiState = uiState.copy(recording = WatchUiMapper.recording(snapshot))
                    }
                    updateMetrics()
                }
            },
        )
        lifecycle.addObserver(ambientObserver)
        val selected = preferences
            .getString(PREFERENCE_ACTIVITY, null)
            ?.let { stored -> runCatching { ActivityChoice.valueOf(stored) }.getOrNull() }
            ?: ActivityChoice.RUNNING
        uiState = uiState.copy(selectedActivity = selected)
        updatePermissionState()
        summarySource.start(
            listener = ::showSummary,
            onError = {
                uiState = uiState.copy(
                    errorCode = "SUMMARY_READ_FAILED",
                    errorMessage = getString(R.string.summary_read_failed),
                )
            },
        )
        setContent {
            WearSportLoggerApp(
                state = uiState,
                actions = actions,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        updatePermissionState()
        registerSyncReceiver()
        startAndBindService()
    }

    override fun onStop() {
        unregisterSyncReceiver()
        clientRegistration.bindingStopped()
        if (bindRequested) {
            unbindService(serviceConnection)
            bindRequested = false
        }
        service = null
        uiState = uiState.copy(serviceConnection = ServiceConnectionState.DISCONNECTED)
        super.onStop()
    }

    override fun onDestroy() {
        summarySource.close()
        super.onDestroy()
    }

    override fun onLoggerUpdate(data: SharedData) {
        latestSharedData = data
        if (!uiState.ambient) {
            updateMetrics()
        }
    }

    override fun onRecordingStateChanged(snapshot: RecordingSnapshot) {
        val previous = latestRecordingSnapshot
        latestRecordingSnapshot = snapshot
        if (uiState.ambient &&
            previous != null &&
            previous.state == snapshot.state &&
            previous.activityId == snapshot.activityId &&
            previous.activityType == snapshot.activityType &&
            previous.error == snapshot.error &&
            previous.elapsedDurationMillis / 60_000L ==
            snapshot.elapsedDurationMillis / 60_000L
        ) {
            return
        }

        val recording = WatchUiMapper.recording(snapshot)
        val authoritativeActivity = recording.activity
        if (authoritativeActivity != null) {
            preferences.edit {
                putString(PREFERENCE_ACTIVITY, authoritativeActivity.name)
            }
        }
        uiState = uiState.copy(
            recording = recording,
            selectedActivity = authoritativeActivity ?: uiState.selectedActivity,
            confirmation = if (snapshot.state.isTerminalTransition()) {
                null
            } else {
                uiState.confirmation
            },
            errorCode = recording.errorCode ?: uiState.errorCode,
            errorMessage = recording.errorMessage ?: uiState.errorMessage,
        )
        if (snapshot.state == RecordingState.EXPORT_QUEUED) {
            summarySource.refreshLatest()
        }
    }

    override fun onRouteStatusChanged(status: RouteStatus) {
        uiState = uiState.copy(routeStatus = status)
        updateMetrics()
    }

    override fun onAcquisitionStateChanged(snapshot: HealthServicesExerciseSnapshot) {
        uiState = uiState.copy(
            acquisition = snapshot,
            activitySupport = WatchUiMapper.activitySupport(
                uiState.selectedActivity,
                snapshot,
                uiState.supportedActivities,
            ),
        )
        updateMetrics()
    }

    override fun onLoggerError(code: String, message: String) {
        uiState = uiState.copy(
            errorCode = code,
            errorMessage = message.ifBlank { getString(R.string.recording_service_unavailable) },
        )
    }

    private fun startAndBindService() {
        if (bindRequested) {
            return
        }
        val intent = Intent(this, SportLoggerService::class.java)
        startService(intent)
        uiState = uiState.copy(serviceConnection = ServiceConnectionState.CONNECTING)
        clientRegistration.bindingStarted()
        bindRequested = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        if (!bindRequested) {
            clientRegistration.bindingStopped()
            uiState = uiState.copy(
                serviceConnection = ServiceConnectionState.DISCONNECTED,
                errorCode = "SERVICE_BIND_FAILED",
                errorMessage = getString(R.string.recording_service_unavailable),
            )
        }
    }

    private fun handleDeadBinding(rebind: Boolean) {
        clientRegistration.serviceDisconnected()
        service = null
        if (bindRequested) {
            runCatching { unbindService(serviceConnection) }
            bindRequested = false
        }
        uiState = uiState.copy(serviceConnection = ServiceConnectionState.DISCONNECTED)
        if (rebind) {
            mainExecutor.execute {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    startAndBindService()
                }
            }
        }
    }

    private fun selectActivity(choice: ActivityChoice) {
        preferences.edit {
            putString(PREFERENCE_ACTIVITY, choice.name)
        }
        uiState = uiState.copy(
            selectedActivity = choice,
            activitySupport = WatchUiMapper.activitySupport(
                choice,
                uiState.acquisition,
                uiState.supportedActivities,
            ),
        )
    }

    private fun startActivity(choice: ActivityChoice) {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            pendingStart = choice
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        startSelectedActivity(choice)
    }

    private fun startSelectedActivity(choice: ActivityChoice) {
        uiState.summary?.let { summary ->
            preferences.edit {
                putString(PREFERENCE_DISMISSED_SUMMARY, summary.transferId)
            }
        }
        uiState = uiState.copy(
            summaryDismissed = true,
            confirmation = null,
        )
        runServiceCommand { it.startNewActivity(choice.canonicalType) }
    }

    private fun recordLap() {
        uiState = uiState.copy(
            errorCode = "LAP_CONTRACT_UNAVAILABLE",
            errorMessage = getString(R.string.lap_not_ready),
        )
    }

    private fun requestMissingPermissions() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            refreshReadiness()
        }
    }

    private fun refreshReadiness() {
        updatePermissionState()
        service?.refreshAcquisitionState()
    }

    private fun updatePermissionState() {
        uiState = uiState.copy(missingPermissionCount = missingPermissions().size)
    }

    private fun missingPermissions(): List<String> =
        RecordingPermissionPolicy.runtimePermissions(
            Build.VERSION.SDK_INT,
            true,
            true,
        ).filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) !=
                PackageManager.PERMISSION_GRANTED
        }

    private fun updateMetrics() {
        uiState = uiState.copy(
            metrics = WatchUiMapper.liveMetrics(
                latestSharedData,
                uiState.acquisition,
                uiState.routeStatus,
            ),
        )
    }

    private fun runServiceCommand(command: (SportLoggerService) -> Boolean) {
        val connected = service
        if (connected == null || !command(connected)) {
            uiState = uiState.copy(
                errorCode = "SERVICE_COMMAND_REJECTED",
                errorMessage = getString(R.string.recording_command_rejected),
            )
        }
    }

    private fun showSummary(summary: com.long2know.sportlogger.ui.WorkoutSummaryUi?) {
        if (summary == null) {
            uiState = uiState.copy(
                summary = null,
                summaryDismissed = false,
            )
            return
        }
        val dismissed =
            preferences.getString(PREFERENCE_DISMISSED_SUMMARY, null) == summary.transferId
        uiState = uiState.copy(
            summary = summary,
            summaryDismissed = dismissed,
        )
    }

    private fun dismissSummary() {
        val summary = uiState.summary ?: return
        preferences.edit {
            putString(PREFERENCE_DISMISSED_SUMMARY, summary.transferId)
        }
        uiState = uiState.copy(summaryDismissed = true)
    }

    private fun registerSyncReceiver() {
        if (receiverRegistered) {
            return
        }
        ContextCompat.registerReceiver(
            this,
            syncStateReceiver,
            IntentFilter(WatchSyncEvents.ACTION_SYNC_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun unregisterSyncReceiver() {
        if (!receiverRegistered) {
            return
        }
        unregisterReceiver(syncStateReceiver)
        receiverRegistered = false
    }

    private fun RecordingState.isTerminalTransition(): Boolean =
        this == RecordingState.STOPPING ||
            this == RecordingState.DISCARDING ||
            this == RecordingState.EXPORT_PENDING ||
            this == RecordingState.EXPORT_QUEUED

    companion object {
        private const val UI_PREFERENCES = "watch_compose_ui"
        private const val PREFERENCE_ACTIVITY = "selected_activity"
        private const val PREFERENCE_DISMISSED_SUMMARY = "dismissed_summary"
    }
}
