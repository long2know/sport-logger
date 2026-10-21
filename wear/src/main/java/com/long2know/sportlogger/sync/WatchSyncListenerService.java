package com.long2know.sportlogger.sync;

import android.util.Log;

import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.WearableListenerService;
import com.long2know.utilities.transfer.CanonicalSyncProtocol;

import java.util.List;

public final class WatchSyncListenerService extends WearableListenerService {
    private static final String TAG = "WatchCanonicalSync";

    @Override
    public void onCapabilityChanged(CapabilityInfo capabilityInfo) {
        if (CanonicalSyncProtocol.PHONE_RECEIVER_CAPABILITY.equals(
                capabilityInfo.getName())) {
            runConnectivityTriggeredSync();
        } else {
            super.onCapabilityChanged(capabilityInfo);
        }
    }

    @Override
    public void onPeerConnected(Node peer) {
        runConnectivityTriggeredSync();
    }

    @Override
    public void onConnectedNodes(List<Node> connectedNodes) {
        if (!connectedNodes.isEmpty()) {
            runConnectivityTriggeredSync();
        }
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        if (!CanonicalSyncProtocol.COMMIT_ACK_PATH.equals(messageEvent.getPath())) {
            super.onMessageReceived(messageEvent);
            return;
        }
        try {
            boolean pending = WatchSyncDispatcher.acceptAcknowledgement(
                    this,
                    messageEvent.getData(),
                    System.currentTimeMillis());
            if (!pending) {
                WatchSyncScheduler.cancel(this);
            }
        } catch (Exception error) {
            Log.e(TAG, "Rejected canonical commit acknowledgement", error);
            WatchSyncScheduler.scheduleFallback(this);
        }
    }

    private void runConnectivityTriggeredSync() {
        try {
            WatchSyncRunner.Result result =
                    WatchSyncDispatcher.runNow(this, false);
            if (result.isRetryRequired()) {
                WatchSyncScheduler.scheduleFallback(this);
            }
        } catch (Exception error) {
            Log.e(TAG, "Connectivity-triggered canonical sync failed", error);
            WatchSyncScheduler.scheduleFallback(this);
        }
    }
}
