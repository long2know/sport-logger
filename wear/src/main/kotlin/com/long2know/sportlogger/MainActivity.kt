package com.long2know.sportlogger

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import com.long2know.sportlogger.acquisition.LiveMeasurementSnapshot
import com.long2know.sportlogger.acquisition.RecordingPermissionPolicy
import com.long2know.sportlogger.acquisition.RouteStatus
import com.long2know.sportlogger.acquisition.healthservices.HealthServicesExerciseSnapshot
import com.long2know.sportlogger.recording.CompletedRecordingSnapshot
import com.long2know.sportlogger.recording.ManualLapResult
import com.long2know.sportlogger.recording.RecordingAuthority
import com.long2know.sportlogger.recording.RecordingSnapshot
import com.long2know.sportlogger.recording.RecordingState
import com.long2know.sportlogger.services.ISportLoggerServiceClient
import com.long2know.sportlogger.services.SportLoggerService
import com.long2know.sportlogger.sync.WatchSyncFinishHook
import com.long2know.sportlogger.ui.ActivityChoice
import com.long2know.sportlogger.ui.AmbientRecordingUpdatePolicy
import com.long2know.sportlogger.ui.CanonicalSummaryMapper
import com.long2know.sportlogger.ui.ConfirmationAction
import com.long2know.sportlogger.ui.PermissionStartCoordinator
import com.long2know.sportlogger.ui.ServiceClientRegistration
import com.long2know.sportlogger.ui.ServiceConnectionState
import com.long2know.sportlogger.ui.StartPermissionResolution
import com.long2know.sportlogger.ui.WatchAppActions
import com.long2know.sportlogger.ui.WatchUiMapper
import com.long2know.sportlogger.ui.WatchUiState
import com.long2know.sportlogger.ui.WearSportLoggerApp

class MainActivity :
    ComponentActivity(),
    ISportLoggerServiceClient {

    private val preferences by lazy {
        getSharedPreferences(UI_PREFERENCES, Context.MODE_PRIVATE)
    }
    private var uiState by mutableStateOf(WatchUiState())
    private var latestRecordingSnapshot: RecordingSnapshot? = null
    private var latestLiveMeasurements = LiveMeasurementSnapshot.empty(0)
    private var service: SportLoggerService? = null
    private var bindRequested = false
    private var lapRequestInFlight = false
    private val permissionStartCoordinator = PermissionStartCoordinator()
    private lateinit var ambientObserver: AmbientLifecycleObserver
    private val clientRegistration = ServiceClientRegistration<SportLoggerService>(
        register = { connected -> connected.registerClient(this) },
        unregister = { connected -> connected.unregisterClient(this) },
    )
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            recordPermissionResults(result)
            updatePermissionState()
            service?.refreshAcquisitionState()
            tryStartPendingActivity()
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
                lapAvailable = latestRecordingSnapshot?.authority ==
                    RecordingAuthority.CANONICAL,
                activitySupport = WatchUiMapper.activitySupport(
                    uiState.selectedActivity,
                    uiState.acquisition,
                    supportedActivities,
                ),
            )
            connected.refreshAcquisitionState()
            tryStartPendingActivity()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            clientRegistration.serviceDisconnected()
            service = null
            lapRequestInFlight = false
            uiState = uiState.copy(
                serviceConnection = ServiceConnectionState.DISCONNECTED,
                lapAvailable = false,
                lapInFlight = false,
            )
        }

        override fun onBindingDied(name: ComponentName) {
            handleDeadBinding(rebind = true)
        }

        override fun onNullBinding(name: ComponentName) {
            handleDeadBinding(rebind = false)
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
        startAndBindService()
    }

    override fun onStop() {
        clientRegistration.bindingStopped()
        if (bindRequested) {
            unbindService(serviceConnection)
            bindRequested = false
        }
        service = null
        lapRequestInFlight = false
        uiState = uiState.copy(
            serviceConnection = ServiceConnectionState.DISCONNECTED,
            lapAvailable = false,
            lapInFlight = false,
        )
        super.onStop()
    }

    override fun onLoggerUpdate(snapshot: LiveMeasurementSnapshot) {
        latestLiveMeasurements = snapshot
        if (!uiState.ambient) {
            updateMetrics()
        }
    }

    override fun onRecordingStateChanged(snapshot: RecordingSnapshot) {
        val previous = latestRecordingSnapshot
        latestRecordingSnapshot = snapshot
        if (uiState.ambient &&
            previous != null &&
            !AmbientRecordingUpdatePolicy.shouldApply(previous, snapshot)
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
            lapAvailable = snapshot.authority == RecordingAuthority.CANONICAL,
            confirmation = if (snapshot.state.isTerminalTransition()) {
                null
            } else {
                uiState.confirmation
            },
            errorCode = recording.errorCode ?: uiState.errorCode
                ?.takeUnless {
                    it == "SERVICE_COMMAND_REJECTED" &&
                        snapshot.state != RecordingState.ERROR
                },
            errorMessage = recording.errorMessage ?: uiState.errorMessage
                ?.takeUnless {
                    uiState.errorCode == "SERVICE_COMMAND_REJECTED" &&
                        snapshot.state != RecordingState.ERROR
                },
        )
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

    override fun onLatestCompletedChanged(snapshot: CompletedRecordingSnapshot?) {
        if (snapshot == null) {
            showSummary(null)
            return
        }
        runCatching {
            CanonicalSummaryMapper.fromCompleted(snapshot)
        }.onSuccess(::showSummary).onFailure {
            uiState = uiState.copy(
                errorCode = "SUMMARY_READ_FAILED",
                errorMessage = getString(R.string.summary_read_failed),
            )
        }
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
        lapRequestInFlight = false
        uiState = uiState.copy(
            serviceConnection = ServiceConnectionState.DISCONNECTED,
            lapAvailable = false,
            lapInFlight = false,
        )
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
        val permissions = currentPermissionState()
        uiState = uiState.copy(permissions = WatchUiMapper.permissions(permissions))
        when (val resolution =
            permissionStartCoordinator.requestStart(choice, permissions)
        ) {
            is StartPermissionResolution.Start ->
                startSelectedActivity(resolution.choice)

            is StartPermissionResolution.Request ->
                launchPermissionRequest(resolution.permissions)

            StartPermissionResolution.Blocked -> Unit
        }
    }

    private fun tryStartPendingActivity() {
        val permissions = currentPermissionState()
        uiState = uiState.copy(permissions = WatchUiMapper.permissions(permissions))
        val choice = permissionStartCoordinator.consumePendingIfReady(
            permissions,
            serviceReady = service != null,
        ) ?: return
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
        val connected = service
        if (connected == null || lapRequestInFlight) {
            return
        }
        lapRequestInFlight = true
        uiState = uiState.copy(
            lapInFlight = true,
            errorCode = null,
            errorMessage = null,
        )
        connected.recordLap().whenComplete { result, error ->
            mainExecutor.execute {
                lapRequestInFlight = false
                if (error != null || result == null) {
                    uiState = uiState.copy(
                        lapInFlight = false,
                        errorCode = "LAP_FAILED",
                        errorMessage = getString(R.string.lap_failed),
                    )
                    return@execute
                }

                onRecordingStateChanged(result.snapshot)
                val fallbackMessage = when (result.status) {
                    ManualLapResult.Status.ACCEPTED -> null
                    ManualLapResult.Status.REJECTED_NOT_RECORDING ->
                        getString(R.string.lap_not_recording)

                    ManualLapResult.Status.REJECTED_BUSY ->
                        getString(R.string.lap_busy)

                    ManualLapResult.Status.REJECTED_LEGACY_AUTHORITY ->
                        getString(R.string.lap_legacy_recording)

                    ManualLapResult.Status.FAILED ->
                        getString(R.string.lap_failed)
                }
                uiState = uiState.copy(
                    lapInFlight = false,
                    errorCode = if (result.isAccepted) {
                        null
                    } else {
                        "LAP_${result.status.name}"
                    },
                    errorMessage = if (result.isAccepted) {
                        null
                    } else {
                        result.message.ifBlank { requireNotNull(fallbackMessage) }
                    },
                )
            }
        }
    }

    private fun requestMissingPermissions() {
        val permissions = currentPermissionState()
        uiState = uiState.copy(permissions = WatchUiMapper.permissions(permissions))
        if (permissions.requestablePermissions.isNotEmpty()) {
            launchPermissionRequest(permissions.requestablePermissions)
        } else {
            refreshReadiness()
        }
    }

    private fun refreshReadiness() {
        updatePermissionState()
        service?.refreshAcquisitionState()
    }

    private fun updatePermissionState() {
        uiState = uiState.copy(
            permissions = WatchUiMapper.permissions(currentPermissionState()),
        )
    }

    private fun currentPermissionState(): RecordingPermissionPolicy.RuntimeState {
        val requested = preferences
            .getStringSet(PREFERENCE_REQUESTED_PERMISSIONS, emptySet())
            .orEmpty()
            .toSet()
        val denied = preferences
            .getStringSet(PREFERENCE_DENIED_PERMISSIONS, emptySet())
            .orEmpty()
            .toSet()
        return RecordingPermissionPolicy.runtimeState(
            Build.VERSION.SDK_INT,
            true,
            true,
            ::isPermissionGranted,
            requested::contains,
            denied::contains,
            ::shouldShowRequestPermissionRationale,
        )
    }

    private fun isPermissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED

    private fun launchPermissionRequest(permissions: Collection<String>) {
        val requestedNow = permissions.distinct()
        if (requestedNow.isEmpty()) {
            return
        }
        val requested = preferences
            .getStringSet(PREFERENCE_REQUESTED_PERMISSIONS, emptySet())
            .orEmpty()
            .toMutableSet()
        requested.addAll(requestedNow)
        preferences.edit {
            putStringSet(PREFERENCE_REQUESTED_PERMISSIONS, requested)
        }
        permissionLauncher.launch(requestedNow.toTypedArray())
    }

    private fun recordPermissionResults(result: Map<String, Boolean>) {
        val denied = preferences
            .getStringSet(PREFERENCE_DENIED_PERMISSIONS, emptySet())
            .orEmpty()
            .toMutableSet()
        result.forEach { (permission, granted) ->
            if (granted) {
                denied.remove(permission)
            } else {
                denied.add(permission)
            }
        }
        preferences.edit {
            putStringSet(PREFERENCE_DENIED_PERMISSIONS, denied)
        }
    }

    private fun updateMetrics() {
        uiState = uiState.copy(
            metrics = WatchUiMapper.liveMetrics(
                latestLiveMeasurements,
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

    private fun RecordingState.isTerminalTransition(): Boolean =
        this == RecordingState.STOPPING ||
            this == RecordingState.DISCARDING ||
            this == RecordingState.EXPORT_PENDING ||
            this == RecordingState.EXPORT_QUEUED

    companion object {
        private const val UI_PREFERENCES = "watch_compose_ui"
        private const val PREFERENCE_ACTIVITY = "selected_activity"
        private const val PREFERENCE_DISMISSED_SUMMARY = "dismissed_summary"
        private const val PREFERENCE_REQUESTED_PERMISSIONS = "requested_permissions"
        private const val PREFERENCE_DENIED_PERMISSIONS = "denied_permissions"
    }
}
