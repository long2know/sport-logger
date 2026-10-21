package com.long2know.sportlogger.transfer;

import android.util.Log;

import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.WearableListenerService;
import com.long2know.utilities.transfer.CanonicalSyncProtocol;

import java.util.List;

public final class PhoneSyncListenerService extends WearableListenerService {
    private static final String TAG = "PhoneCanonicalSync";

    @Override
    public void onCapabilityChanged(CapabilityInfo capabilityInfo) {
        if (CanonicalSyncProtocol.WATCH_RECEIVER_CAPABILITY.equals(
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
            boolean pending =
                    PhoneTombstoneSyncDispatcher.acceptAcknowledgement(
                            this,
                            messageEvent.getData(),
                            System.currentTimeMillis());
            if (!pending) {
                PhoneTombstoneSyncScheduler.cancel(this);
            }
        } catch (Exception error) {
            Log.e(TAG, "Rejected phone tombstone commit acknowledgement", error);
            PhoneTombstoneSyncScheduler.scheduleFallback(this);
        }
    }

    private void runConnectivityTriggeredSync() {
        try {
            PhoneTombstoneSyncRunner.Result result =
                    PhoneTombstoneSyncDispatcher.runNow(this, false);
            if (result.isRetryRequired()) {
                PhoneTombstoneSyncScheduler.scheduleFallback(this);
            }
        } catch (Exception error) {
            Log.e(TAG, "Connectivity-triggered phone tombstone sync failed", error);
            PhoneTombstoneSyncScheduler.scheduleFallback(this);
        }
    }
}
