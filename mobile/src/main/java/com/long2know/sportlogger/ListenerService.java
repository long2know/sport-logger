package com.long2know.sportlogger;

import android.content.Intent;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import static android.support.constraint.Constraints.TAG;
import com.long2know.utilities.SportActivity;

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
        }
        else {
            super.onMessageReceived(messageEvent);
        }
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        for (DataEvent event : dataEvents) {
            if (event.getType() == DataEvent.TYPE_CHANGED &&
                    event.getDataItem().getUri().getPath().equals(getString(R.string.wear_path))) {
                DataItem item = event.getDataItem();
                DataMapItem dataMapItem = DataMapItem.fromDataItem(event.getDataItem());
                Asset sportActivityAsset = dataMapItem.getDataMap().getAsset("sportActivity");
                SportActivity sportActivity = null;
                try {
                    sportActivity = SportActivity.deserialize(sportActivityAsset.getData());
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
    }

}