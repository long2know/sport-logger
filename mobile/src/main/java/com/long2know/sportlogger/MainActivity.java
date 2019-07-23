package com.long2know.sportlogger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.ListView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.Calendar;
import com.long2know.utilities.models.SportActivity;
import static androidx.constraintlayout.widget.Constraints.TAG;

public class MainActivity extends AppCompatActivity {
    private ListView activityList;
    static final int LOGIN_REQUEST = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Intent intent = new Intent(MainActivity.this, LoginActivity.class);
        startActivityForResult(intent, LOGIN_REQUEST);

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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // Check which request we're responding to
        if (requestCode == LOGIN_REQUEST) {
            // Make sure the request was successful
            if (resultCode == RESULT_OK) {
                Bundle bundle = getIntent().getExtras();

                //Calculate date of expiration
                Calendar calendar = Calendar.getInstance();

                SharedPreferences preferences = MainActivity.this.getSharedPreferences("oauth_tokens", 0);
                long expiredDate = preferences.getLong("expires_in", 0);
                if (calendar.getTimeInMillis() > expiredDate) {
                    // We're expired!
                }

                String accessToken = preferences.getString("access_token", "");
                String refreshToken = preferences.getString("refresh_token", "");

                Toast.makeText(getApplicationContext(), "Finished logging in! " + accessToken, Toast.LENGTH_LONG).show();
            }
        }
    }
}
