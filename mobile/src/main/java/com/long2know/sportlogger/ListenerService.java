package com.long2know.sportlogger;

import android.content.Intent;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import com.long2know.sportlogger.transfer.ActivityTransferReceiver;
import com.long2know.sportlogger.transfer.PendingAcknowledgementRetry;
import com.long2know.sportlogger.transfer.PhoneActivityStore;
import com.long2know.sportlogger.transfer.WearDataLayerAcknowledger;
import com.long2know.utilities.models.SportActivity;
import com.long2know.utilities.transfer.ActivityTransferProtocol;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

public class ListenerService extends WearableListenerService {
    private static final String TAG = "ListenerService";
    private static final long DATA_LAYER_TIMEOUT_SECONDS = 30L;

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {

        if (messageEvent.getPath().equals(getString(R.string.wear_path))) {
            final String message = new String(messageEvent.getData());
            Log.v(TAG, "Message path received on phone is: " + messageEvent.getPath());
            Log.v(TAG, "Message received on phone is: " + message);

            // Broadcast message to MainActivity for display
            Intent messageIntent = new Intent();
            messageIntent.setAction(Intent.ACTION_SEND);
            messageIntent.putExtra("message", message);
            LocalBroadcastManager.getInstance(this).sendBroadcast(messageIntent);
        } else {
            super.onMessageReceived(messageEvent);
        }
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        String activityPath = getString(R.string.wear_path);
        PhoneActivityStore store = new PhoneActivityStore(this);
        ActivityTransferReceiver.Acknowledger acknowledger =
                new WearDataLayerAcknowledger(this);
        retryPendingAcknowledgements(store, acknowledger);
        ActivityTransferReceiver receiver = new ActivityTransferReceiver(
                activityPath,
                store,
                acknowledger,
                () -> PendingAcknowledgementRetry.schedule(this));

        for (DataEvent event : dataEvents) {
            String itemPath = event.getDataItem().getUri().getPath();
            if (event.getType() != DataEvent.TYPE_CHANGED
                    || !ActivityTransferProtocol.isActivityPath(activityPath, itemPath)) {
                continue;
            }
            try {
                DataMapItem dataMapItem = DataMapItem.fromDataItem(event.getDataItem());
                if (dataMapItem.getDataMap().getBoolean(
                        ActivityTransferProtocol.LEGACY_ALIAS_KEY,
                        false)) {
                    continue;
                }
                Asset sportActivityAsset = dataMapItem.getDataMap().getAsset(
                        ActivityTransferProtocol.ASSET_KEY);
                if (sportActivityAsset == null) {
                    throw new IllegalStateException("Activity transfer has no payload asset");
                }
                byte[] bytes;
                try (InputStream assetInputStream =
                             Tasks.await(
                                     Wearable.getDataClient(this)
                                             .getFdForAsset(sportActivityAsset),
                                     DATA_LAYER_TIMEOUT_SECONDS,
                                     TimeUnit.SECONDS)
                                     .getInputStream();
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[0xFFFF];
                    for (int len = assetInputStream.read(buffer);
                         len != -1;
                         len = assetInputStream.read(buffer)) {
                        output.write(buffer, 0, len);
                    }
                    bytes = output.toByteArray();
                }

                String sourceNodeId = event.getDataItem().getUri().getHost();
                String sourceUri = event.getDataItem().getUri().toString();
                String declaredTransferId = dataMapItem.getDataMap().getString(
                        ActivityTransferProtocol.TRANSFER_ID_KEY);
                Long declaredActivityId = dataMapItem.getDataMap().containsKey(
                        ActivityTransferProtocol.ACTIVITY_ID_KEY)
                        ? dataMapItem.getDataMap().getLong(
                        ActivityTransferProtocol.ACTIVITY_ID_KEY)
                        : null;
                String declaredPayloadSha256 = dataMapItem.getDataMap().getString(
                        ActivityTransferProtocol.PAYLOAD_SHA256_KEY);
                SportActivity sportActivity = receiver.receive(
                        itemPath,
                        sourceNodeId,
                        sourceUri,
                        declaredTransferId,
                        declaredActivityId,
                        declaredPayloadSha256,
                        bytes);

                Intent activityIntent = new Intent(Intent.ACTION_SEND);
                activityIntent.putExtra("sportActivity", sportActivity);
                LocalBroadcastManager.getInstance(this).sendBroadcast(activityIntent);
            } catch (Exception error) {
                Log.e(TAG, "Could not durably receive activity transfer", error);
            }
        }
    }

    private void retryPendingAcknowledgements(
            PhoneActivityStore store,
            ActivityTransferReceiver.Acknowledger acknowledger) {
        try {
            PendingAcknowledgementRetry.Result result =
                    new PendingAcknowledgementRetry(store, acknowledger).run();
            if (result.needsRetry()) {
                Log.e(
                        TAG,
                        "Could not retry "
                                + result.getFailureCount()
                                + " durable activity acknowledgement(s)",
                        result.getFirstFailure());
                PendingAcknowledgementRetry.schedule(this);
            }
        } catch (Exception error) {
            Log.e(TAG, "Could not retry or schedule pending activity acknowledgements", error);
        }
    }
}