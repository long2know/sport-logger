package com.long2know.sportlogger.recording;

import android.content.Context;

import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.long2know.utilities.models.SportActivity;

import java.util.Objects;
import java.util.concurrent.Executor;

public final class WearDataLayerExporter implements RecordingExporter {
    private final Context context;
    private final String path;
    private final Executor callbackExecutor;

    public WearDataLayerExporter(Context context, String path, Executor callbackExecutor) {
        this.context = context.getApplicationContext();
        this.path = Objects.requireNonNull(path);
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor);
    }

    @Override
    public void export(SportActivity activity, Callback callback) {
        try {
            byte[] bytes = SportActivity.serialize(activity);
            PutDataMapRequest dataMap = PutDataMapRequest.create(path);
            dataMap.getDataMap().putAsset("sportActivity", Asset.createFromBytes(bytes));
            dataMap.getDataMap().putLong("activityId", activity.Id);
            PutDataRequest request = dataMap.asPutDataRequest().setUrgent();
            Wearable.getDataClient(context)
                    .putDataItem(request)
                    .addOnSuccessListener(callbackExecutor, ignored -> callback.onSuccess())
                    .addOnFailureListener(callbackExecutor, callback::onFailure);
        } catch (Exception error) {
            callbackExecutor.execute(() -> callback.onFailure(error));
        }
    }
}
