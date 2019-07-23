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

import static androidx.constraintlayout.widget.Constraints.TAG;
import com.long2know.utilities.models.SportActivity;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class ListenerService extends WearableListenerService {

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
        try {

            for (DataEvent event : dataEvents) {
                if (event.getType() == DataEvent.TYPE_CHANGED &&
                        event.getDataItem().getUri().getPath().equals(getString(R.string.wear_path))) {
                    DataMapItem dataMapItem = DataMapItem.fromDataItem(event.getDataItem());
                    Asset sportActivityAsset = dataMapItem.getDataMap().getAsset("sportActivity");
                    SportActivity sportActivity = null;
                    try {
                        InputStream assetInputStream =
                                Tasks.await(Wearable.getDataClient(this)
                                        .getFdForAsset(sportActivityAsset))
                                        .getInputStream();

                        ByteArrayOutputStream os = new ByteArrayOutputStream();
                        byte[] buffer = new byte[0xFFFF];
                        for (int len = assetInputStream.read(buffer); len != -1; len = assetInputStream.read(buffer)) {
                            os.write(buffer, 0, len);
                        }

                        byte[] bytes = os.toByteArray();
                        sportActivity = SportActivity.deserialize(bytes);
                    } catch (Exception e) {
                        Log.e(TAG, "Could not deserialize activity");
                    }

                    // Do something with the activity
                    // Broadcast message to MainActivity for display
                    Intent activityIntent = new Intent();
                    activityIntent.setAction(Intent.ACTION_SEND);
                    activityIntent.putExtra("sportActivity", sportActivity);
                    LocalBroadcastManager.getInstance(this).sendBroadcast(activityIntent);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, e.toString());
        }
    }
}