package com.long2know.sportlogger

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.long2know.sportlogger.integration.UnboundPhoneIntegrationStatusGateway
import com.long2know.sportlogger.healthconnect.HealthConnectProviderFactory
import com.long2know.sportlogger.phone.CanonicalPhoneDataSource
import com.long2know.sportlogger.phone.PhoneUiEvent
import com.long2know.sportlogger.transfer.PhoneSyncEvents
import com.long2know.sportlogger.transfer.PhoneTombstoneSyncHook
import com.long2know.sportlogger.ui.SportLoggerPhoneRoute
import com.long2know.utilities.export.tcx.TcxWriter
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: ComposePhoneViewModel by viewModels()
    private lateinit var healthPermissionLauncher: ActivityResultLauncher<Set<String>>

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument(TcxWriter.MEDIA_TYPE),
    ) { destination ->
        viewModel.saveToUri(contentResolver, destination)
    }

    private val activityReceiver = object : BroadcastReceiver() {
        override fun onReceive(
            context: Context,
            intent: Intent,
        ) {
            if (intent.action == ListenerService.ACTION_ACTIVITY_RECEIVED) {
                viewModel.refreshFromReceipt()
            } else if (intent.action == PhoneSyncEvents.ACTION_TOMBSTONE_SYNC_STATE_CHANGED) {
                viewModel.onTombstoneSyncState(
                    intent.getStringExtra(PhoneSyncEvents.EXTRA_DISPLAY_STATE).orEmpty(),
                )
            } else {
                viewModel.refreshHistory()
            }
        }
    }

    private val canonicalSyncReceiver = object : BroadcastReceiver() {
        override fun onReceive(
            context: Context,
            intent: Intent,
        ) {
            if (intent.action == PhoneSyncEvents.ACTION_CANONICAL_ACTIVITY_CHANGED) {
                viewModel.onCanonicalSyncHint(
                    acknowledgementDelivered = intent.getBooleanExtra(
                        PhoneSyncEvents.EXTRA_ACK_DELIVERED,
                        false,
                    ),
                )
            } else {
                viewModel.onCanonicalSyncFailure()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.initialize(
            dataSourceFactory = {
                CanonicalPhoneDataSource.open(applicationContext)
            },
            integrationGateway = UnboundPhoneIntegrationStatusGateway(),
            healthConnectProviderFactory = {
                HealthConnectProviderFactory.create(applicationContext)
            },
            deletionCommittedHook = { outbox ->
                PhoneTombstoneSyncHook.onDeletionCommitted(applicationContext, outbox)
            },
            deleteRetryHook = {
                PhoneTombstoneSyncHook.requestRetry(applicationContext)
            },
        )
        healthPermissionLauncher = registerForActivityResult(
            viewModel.healthConnectPermissionContract(),
            viewModel::onHealthConnectPermissionResult,
        )
        setContent {
            SportLoggerPhoneRoute(
                viewModel = viewModel,
                onPrepareShare = {
                    viewModel.prepareShare(File(filesDir, "shared-tcx"))
                },
                onEvent = ::handleEvent,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            activityReceiver,
            IntentFilter(ListenerService.ACTION_ACTIVITY_RECEIVED),
        )
        ContextCompat.registerReceiver(
            this,
            canonicalSyncReceiver,
            IntentFilter().apply {
                addAction(PhoneSyncEvents.ACTION_CANONICAL_ACTIVITY_CHANGED)
                addAction(PhoneSyncEvents.ACTION_CANONICAL_SYNC_FAILED)
                addAction(PhoneSyncEvents.ACTION_TOMBSTONE_SYNC_STATE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onResume() {
        super.onResume()
        viewModel.onActivityResumed()
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(activityReceiver)
        unregisterReceiver(canonicalSyncReceiver)
        super.onStop()
    }

    private fun handleEvent(event: PhoneUiEvent) {
        viewModel.consumeEvent(event.id)
        when (event) {
            is PhoneUiEvent.CreateDocument -> createDocument.launch(event.suggestedFileName)
            is PhoneUiEvent.ShareFile -> share(event)
            is PhoneUiEvent.RequestHealthConnectPermissions ->
                healthPermissionLauncher.launch(event.permissions)
            is PhoneUiEvent.ResolveHealthConnectProvider -> {
                try {
                    startActivity(event.intent)
                } catch (_: ActivityNotFoundException) {
                    viewModel.providerResolutionLaunchFailed()
                }
            }
        }
    }

    private fun share(event: PhoneUiEvent.ShareFile) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                event.file,
            )
            val send = Intent(Intent.ACTION_SEND)
                .setType(TcxWriter.MEDIA_TYPE)
                .putExtra(Intent.EXTRA_STREAM, uri)
            send.clipData = ClipData.newUri(
                contentResolver,
                event.fileName,
                uri,
            )
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, getString(R.string.share_tcx_chooser)))
        } catch (_: ActivityNotFoundException) {
            viewModel.shareLaunchFailed()
        } catch (_: IllegalArgumentException) {
            viewModel.shareLaunchFailed()
        }
    }
}
