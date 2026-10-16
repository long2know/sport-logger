package com.long2know.sportlogger.recording;

import android.content.Context;

import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.long2know.utilities.models.SportActivity;
import com.long2know.utilities.transfer.ActivityTransferProtocol;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

public final class WearDataLayerExporter implements RecordingExporter {
    interface Completion {
        void onSuccess();

        void onFailure(Throwable error);
    }

    interface DataItemWriter {
        void put(
                String path,
                byte[] payload,
                int activityId,
                String transferId,
                String payloadSha256,
                long attempt,
                boolean legacyAlias,
                Completion completion);
    }

    private final String path;
    private final Executor callbackExecutor;
    private final TransferReceiptStore receiptStore;
    private final DataItemWriter writer;
    private final AtomicLong attemptSequence = new AtomicLong();

    public WearDataLayerExporter(Context context, String path, Executor callbackExecutor) {
        Context applicationContext = context.getApplicationContext();
        this.path = Objects.requireNonNull(path);
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor);
        this.receiptStore = new SharedPreferencesTransferReceiptStore(applicationContext);
        this.writer = (itemPath,
                       payload,
                       activityId,
                       transferId,
                       payloadSha256,
                       attempt,
                       legacyAlias,
                       completion) -> {
            PutDataMapRequest dataMap = PutDataMapRequest.create(itemPath);
            dataMap.getDataMap().putAsset(
                    ActivityTransferProtocol.ASSET_KEY,
                    Asset.createFromBytes(payload));
            dataMap.getDataMap().putLong(
                    ActivityTransferProtocol.ACTIVITY_ID_KEY,
                    activityId);
            dataMap.getDataMap().putString(
                    ActivityTransferProtocol.TRANSFER_ID_KEY,
                    transferId);
            dataMap.getDataMap().putString(
                    ActivityTransferProtocol.PAYLOAD_SHA256_KEY,
                    payloadSha256);
            dataMap.getDataMap().putLong(
                    ActivityTransferProtocol.ATTEMPT_KEY,
                    attempt);
            dataMap.getDataMap().putBoolean(
                    ActivityTransferProtocol.LEGACY_ALIAS_KEY,
                    legacyAlias);
            PutDataRequest request = dataMap.asPutDataRequest().setUrgent();
            Wearable.getDataClient(applicationContext)
                    .putDataItem(request)
                    .addOnSuccessListener(callbackExecutor, ignored -> completion.onSuccess())
                    .addOnFailureListener(callbackExecutor, completion::onFailure);
        };
    }

    WearDataLayerExporter(
            String path,
            Executor callbackExecutor,
            TransferReceiptStore receiptStore,
            DataItemWriter writer) {
        this.path = Objects.requireNonNull(path);
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor);
        this.receiptStore = Objects.requireNonNull(receiptStore);
        this.writer = Objects.requireNonNull(writer);
    }

    @Override
    public void export(SportActivity activity, Callback callback) {
        try {
            byte[] bytes = SportActivity.serialize(activity);
            String transferId = ActivityTransferProtocol.transferId(activity, bytes);
            if (receiptStore.consumeAcknowledgement(transferId)) {
                callbackExecutor.execute(callback::onAcknowledged);
                return;
            }
            writer.put(
                    ActivityTransferProtocol.activityPath(path, transferId),
                    bytes,
                    activity.Id,
                    transferId,
                    ActivityTransferProtocol.payloadSha256(bytes),
                    attemptSequence.incrementAndGet(),
                    false,
                    new Completion() {
                        @Override
                        public void onSuccess() {
                            callbackExecutor.execute(() -> {
                                try {
                                    if (receiptStore.consumeAcknowledgement(transferId)) {
                                        callback.onAcknowledged();
                                    } else {
                                        putLegacyAlias(
                                                activity,
                                                bytes,
                                                transferId,
                                                callback);
                                    }
                                } catch (Exception error) {
                                    callback.onFailure(error);
                                }
                            });
                        }

                        @Override
                        public void onFailure(Throwable error) {
                            callbackExecutor.execute(() -> callback.onFailure(error));
                        }
                    });
        } catch (Exception error) {
            callbackExecutor.execute(() -> callback.onFailure(error));
        }
    }

    private void putLegacyAlias(
            SportActivity activity,
            byte[] bytes,
            String transferId,
            Callback callback) {
        writer.put(
                path,
                bytes,
                activity.Id,
                transferId,
                ActivityTransferProtocol.payloadSha256(bytes),
                attemptSequence.incrementAndGet(),
                true,
                new Completion() {
                    @Override
                    public void onSuccess() {
                        callbackExecutor.execute(() -> {
                            try {
                                if (receiptStore.consumeAcknowledgement(transferId)) {
                                    callback.onAcknowledged();
                                } else {
                                    callback.onQueued();
                                }
                            } catch (Exception error) {
                                callback.onFailure(error);
                            }
                        });
                    }

                    @Override
                    public void onFailure(Throwable error) {
                        callbackExecutor.execute(() -> callback.onFailure(error));
                    }
                });
    }
}
