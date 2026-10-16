package com.long2know.sportlogger.transfer;

import android.content.Context;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

import com.long2know.utilities.transfer.ActivityTransferProtocol;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class WearDataLayerAcknowledger
        implements ActivityTransferReceiver.Acknowledger {
    private static final long DATA_LAYER_TIMEOUT_SECONDS = 30L;

    interface DataLayer {
        void queue(
                String acknowledgementPath,
                String transferId,
                String sourceUri,
                long attempt) throws Exception;
    }

    private final DataLayer dataLayer;

    public WearDataLayerAcknowledger(Context context) {
        this(new GoogleDataLayer(context.getApplicationContext()));
    }

    WearDataLayerAcknowledger(DataLayer dataLayer) {
        this.dataLayer = Objects.requireNonNull(dataLayer);
    }

    @Override
    public void acknowledge(
            String acknowledgementPath,
            String transferId,
            String sourceUri) throws Exception {
        dataLayer.queue(
                acknowledgementPath,
                transferId,
                sourceUri,
                System.nanoTime());
    }

    private static final class GoogleDataLayer implements DataLayer {
        private final Context context;

        private GoogleDataLayer(Context context) {
            this.context = context;
        }

        @Override
        public void queue(
                String acknowledgementPath,
                String transferId,
                String sourceUri,
                long attempt) throws Exception {
            PutDataMapRequest acknowledgement =
                    PutDataMapRequest.create(acknowledgementPath);
            acknowledgement.getDataMap().putString(
                    ActivityTransferProtocol.TRANSFER_ID_KEY,
                    transferId);
            acknowledgement.getDataMap().putString(
                    ActivityTransferProtocol.SOURCE_URI_KEY,
                    sourceUri);
            acknowledgement.getDataMap().putLong(
                    ActivityTransferProtocol.ATTEMPT_KEY,
                    attempt);
            Tasks.await(
                    Wearable.getDataClient(context).putDataItem(
                            acknowledgement.asPutDataRequest().setUrgent()),
                    DATA_LAYER_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
        }
    }
}
