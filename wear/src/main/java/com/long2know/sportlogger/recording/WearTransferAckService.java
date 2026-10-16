package com.long2know.sportlogger.recording;

import android.net.Uri;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import com.long2know.sportlogger.R;
import com.long2know.utilities.transfer.ActivityTransferProtocol;

import java.util.concurrent.TimeUnit;

public final class WearTransferAckService extends WearableListenerService {
    private static final String TAG = "WearTransferAck";
    private static final long DATA_LAYER_TIMEOUT_SECONDS = 30L;

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        String activityPath = getString(R.string.wear_path);
        TransferReceiptStore receiptStore = new SharedPreferencesTransferReceiptStore(this);
        for (DataEvent event : dataEvents) {
            if (event.getType() != DataEvent.TYPE_CHANGED) {
                continue;
            }
            String path = event.getDataItem().getUri().getPath();
            if (!ActivityTransferProtocol.isAcknowledgementPath(activityPath, path)) {
                continue;
            }
            try {
                DataMapItem item = DataMapItem.fromDataItem(event.getDataItem());
                String transferId = item.getDataMap().getString(
                        ActivityTransferProtocol.TRANSFER_ID_KEY);
                String pathTransferId =
                        ActivityTransferProtocol.transferIdFromAcknowledgementPath(
                                activityPath,
                                path);
                if (transferId == null || !transferId.equals(pathTransferId)) {
                    throw new IllegalStateException("Acknowledgement transfer identifier mismatch");
                }
                String sourceUri = item.getDataMap().getString(
                        ActivityTransferProtocol.SOURCE_URI_KEY);
                Uri source = Uri.parse(sourceUri == null ? "" : sourceUri);
                if (!"wear".equals(source.getScheme())
                        || !ActivityTransferProtocol.isActivityPath(
                        activityPath,
                        source.getPath())) {
                    throw new IllegalStateException("Acknowledgement source URI is invalid");
                }

                receiptStore.acknowledge(transferId);
                Tasks.await(
                        Wearable.getDataClient(this).deleteDataItems(source),
                        DATA_LAYER_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS);
                Tasks.await(
                        Wearable.getDataClient(this)
                                .deleteDataItems(event.getDataItem().getUri()),
                        DATA_LAYER_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS);
            } catch (Exception error) {
                Log.e(TAG, "Could not apply activity transfer acknowledgement", error);
            }
        }
    }
}
