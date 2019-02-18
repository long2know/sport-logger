package com.long2know.sportlogger.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

import com.long2know.utilities.models.Config;
import com.long2know.sportlogger.MainActivity;
import com.long2know.sportlogger.R;
import com.long2know.utilities.models.SharedData;
import com.long2know.utilities.data_access.SqlLogger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static android.support.constraint.Constraints.TAG;

public class SportLoggerService extends  Service {

    private NotificationManager _notificationManager;
    private final IBinder _binder = new LocalBinder();
    public static ISportLoggerServiceClient _serviceClient;

    private Thread _sensorThread;
    private Thread _locationThread;
    private SensorListener _sensorListener;
    private GpsListener _locationListener;
    private ScheduledExecutorService _scheduler;
    private StopWatch _stopWatch = new StopWatch();

    // Below is the service framework methods
    @Override
    public void onCreate() {
        super.onCreate();

        Config.context = this;

        // Pass through any messages
        Config.handler = new Handler(Looper.getMainLooper()) {
            public void handleMessage(Message msg) {
                if (Config.activityHandler != null) {
                    Message completeMessage = Config.activityHandler.obtainMessage(msg.what, msg.arg1, msg.arg2, msg.obj);
                    completeMessage.sendToTarget();
                }
            }
        };

        _notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        _sensorListener = new SensorListener();
        _locationListener = new GpsListener();

        _sensorThread = new Thread(new SensorListener());
        _locationThread = new Thread(new GpsListener());
        _sensorThread.start();
        _locationThread.start();

//        startLoggerService();
//
//        // Display a notification about us starting. We put an icon in the
//        // status bar.
        showNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i("LocalService", "Received start id " + startId + ": " + intent);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return _binder;
    }

    @Override
    public void onDestroy() {
        _serviceClient = null;
        super.onDestroy();
    }

    private void showNotification() {
        // Open the app when notification is clicked
        Intent contentIntent = new Intent(this, MainActivity.class);
        contentIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pending = PendingIntent.getActivity(getBaseContext(), 0, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        String channelId = "long2know_sport_logger";
        CharSequence name = "long2know_channel";
        NotificationChannel channel = new NotificationChannel(channelId, name,NotificationManager.IMPORTANCE_DEFAULT);
        _notificationManager.createNotificationChannel(channel);
        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, channelId);
        notificationBuilder.setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_ALL)
                .setWhen(System.currentTimeMillis())
                .setTicker("Hearty365")
                .setContentTitle("SportLogger is running")
                .setContentText("Running in the background - tap notification to return to app.")
                .setContentInfo("Info")
                .setSmallIcon(R.drawable.ic_play_circle_outline_black_24dp)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(pending);

        try {
            startForeground(1, notificationBuilder.build());
//            _notificationManager.notify(1, notificationBuilder.build());
        } catch (Exception e) {
            Log.e(TAG, e.toString());
        }
    }

    public static void setServiceClient(ISportLoggerServiceClient client) {
        _serviceClient = client;
    }

    /**
     * Class for clients to access. Because we know this service always runs in
     * the same process as its clients, we don't need to deal with IPC.
     */
    public class LocalBinder extends Binder {
        public SportLoggerService getService() {
            return SportLoggerService.this;
        }
    }

    public void startNewActivity() {
        // We can force reading at specific intervals like this
        _scheduler = Executors.newScheduledThreadPool(1);
        _scheduler.scheduleAtFixedRate(new SqlLogger(), 0, 1, TimeUnit.SECONDS);
        _stopWatch.startTImer();
        SqlLogger.initDatabase();
        SharedData.getInstance().ActivityId = SqlLogger.createActivity();
        SharedData.getInstance().IsRecording = true;
        CharSequence text = "Starting new activity";
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    public void stopActivity() {
        // We don't want to block the UI
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    _scheduler.awaitTermination(300, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                }
            }
        });

        _stopWatch.pauseTimer();
        _stopWatch.resetTimer();

        CharSequence text = "Stopped activity";
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
        SharedData.getInstance().IsRecording = false;
        SharedData.getInstance().IsPaused = false;
    }

    public void pauseActivity() {
        // We don't want to block the UI
        SharedData.getInstance().IsPaused = true;
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    _scheduler.awaitTermination(300, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                }
            }
        });

        _stopWatch.pauseTimer();
        CharSequence text = "Paused activity";
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    public void resumeActivity() {
        // We can force reading at specific intervals like this
        _scheduler = Executors.newScheduledThreadPool(1);
        _scheduler.scheduleAtFixedRate(new SqlLogger(), 0, 1, TimeUnit.SECONDS);
        SharedData.getInstance().IsPaused = false;
        _stopWatch.startTImer();
        CharSequence text = "Resuming activity";
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    public void discardActivity() {
        // We don't want to block the UI
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    _scheduler.awaitTermination(300, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                }
                int id = SharedData.getInstance().ActivityId;
                SharedData.getInstance().IsRecording = false;

                SqlLogger sqlLogger = new SqlLogger();
                sqlLogger.deleteActivity(id);
            }
        });

        _stopWatch.pauseTimer();
        _stopWatch.resetTimer();

        SharedData.getInstance().IsRecording = false;
        SharedData.getInstance().IsPaused = false;

        CharSequence text = "Discarded activity";
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
