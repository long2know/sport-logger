package com.long2know.sportlogger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.widget.ListView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.long2know.utilities.models.SportActivity;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private ListView activityList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

//        activityList = (ListView) findViewById(R.id.activity_list);
//        WorkoutAdapter adapter = new WorkoutAdapter(workoutRows);
//        workoutList.setAdapter(adapter);

        // Register the local broadcast receiver
        IntentFilter messageFilter = new IntentFilter(Intent.ACTION_SEND);
        MessageReceiver messageReceiver = new MessageReceiver();
        LocalBroadcastManager.getInstance(this).registerReceiver(messageReceiver, messageFilter);
    }

    //setup a broadcast receiver to receive the messages from the wear device via the listenerService.
    public class MessageReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
             if (intent.hasExtra("sportActivity")) {
                SportActivity sportActivity = (SportActivity) intent.getExtras().getSerializable("sportActivity");

                 CharSequence text = "Received a sport activity with " + Integer.toString(sportActivity.SportTrackPoints.size())
                         + " track points.";
                 Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show();
            }
            String message = intent.getStringExtra("message");
            Log.v(TAG, "Main activity received message: " + message);
        }
    }
}
