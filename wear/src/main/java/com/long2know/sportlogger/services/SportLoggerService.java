package com.long2know.sportlogger.services;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.long2know.utilities.models.Config;
import com.long2know.sportlogger.MainActivity;
import com.long2know.sportlogger.R;
import com.long2know.sportlogger.RecordingPermissions;
import com.long2know.utilities.models.SharedData;
import com.long2know.utilities.data_access.SqlLogger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SportLoggerService extends Service {
    private static final String NOTIFICATION_CHANNEL_ID = "long2know_sport_logger";
    private static final int NOTIFICATION_ID = 1;
    private static final String TAG = "SportLoggerService";

    private NotificationManager _notificationManager;
    private final IBinder _binder = new LocalBinder();
    public static ISportLoggerServiceClient _serviceClient;

    private Thread _sensorThread;
    private Thread _locationThread;
    private SensorListener _sensorListener;
    private GpsListener _locationListener;
    private ScheduledExecutorService _scheduler;
    private StopWatch _stopWatch = new StopWatch();
    private boolean _permissionLossHandled;

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
        if (!RecordingPermissions.allRequiredForRecordingGranted(this)) {
            handleRecordingPermissionLoss();
            return;
        }

        try {
            showNotification();
        } catch (SecurityException exception) {
            Log.e(TAG, "Recording permissions were revoked before foreground startup.", exception);
            handleRecordingPermissionLoss();
            return;
        }

        _sensorListener = new SensorListener(new Runnable() {
            @Override
            public void run() {
                handleRecordingPermissionLoss();
            }
        });
        _locationListener = new GpsListener();

        _sensorThread = new Thread(_sensorListener);
        _locationThread = new Thread(_locationListener);
        _sensorThread.start();
        _locationThread.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i("LocalService", "Received start id " + startId + ": " + intent);
        if (!RecordingPermissions.allRequiredForRecordingGranted(this)) {
            handleRecordingPermissionLoss();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return _binder;
    }

    @Override
    public void onDestroy() {
        if (_scheduler != null) {
            AsyncTask.execute(new Runnable() {
                @Override
                public void run() {
                try {
                    _scheduler.awaitTermination(100, TimeUnit.MILLISECONDS);
                    _scheduler.shutdownNow();
                } catch (InterruptedException e) { }
                }
            });
        }

        Handler sensorHandler = SensorListener.WorkerHandler;
        if (sensorHandler != null) {
            Message lmsg = sensorHandler.obtainMessage(0);
            sensorHandler.sendMessage(lmsg);
        }

        Handler locationHandler = GpsListener.WorkerHandler;
        if (locationHandler != null) {
            Message gmsg = locationHandler.obtainMessage(0);
            locationHandler.sendMessage(gmsg);
        }

        if (_sensorThread != null) {
            _sensorThread.interrupt();
        }
        if (_locationThread != null) {
            _locationThread.interrupt();
        }

        _serviceClient = null;
        super.onDestroy();
    }

    private void showNotification() {
        // Open the app when notification is clicked
        Intent contentIntent = new Intent(this, MainActivity.class);
        contentIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pending = PendingIntent.getActivity(
                this,
                0,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_description));
        _notificationManager.createNotificationChannel(channel);
        NotificationCompat.Builder notificationBuilder =
                new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID);
        notificationBuilder.setAutoCancel(true)
                .setWhen(System.currentTimeMillis())
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(R.drawable.ic_play_circle_outline_black_24dp)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .setContentIntent(pending);

        int serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notificationBuilder.build(),
                serviceTypes);
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

    public boolean startNewActivity() {
        if (!RecordingPermissions.allRequiredForRecordingGranted(this)) {
            handleRecordingPermissionLoss();
            return false;
        }

        // We can force reading at specific intervals like this
        _scheduler = Executors.newScheduledThreadPool(1);
        _scheduler.scheduleAtFixedRate(createRecordingTask(), 0, 1, TimeUnit.SECONDS);
        _stopWatch.startTImer();
        SqlLogger.initDatabase();
        SharedData.getInstance().ActivityId = SqlLogger.createActivity();
        SharedData.getInstance().IsRecording = true;
        SharedData.getInstance().IsPaused = false;
        CharSequence text = "Starting new activity";
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
        return true;
    }

    public void stopActivity() {
        // We don't want to block the UI
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    _scheduler.awaitTermination(300, TimeUnit.MILLISECONDS);
                    _scheduler.shutdownNow();
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
                    _scheduler.shutdownNow();
                } catch (InterruptedException e) {
                }
            }
        });

        _stopWatch.pauseTimer();
        CharSequence text = "Paused activity";
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    public boolean resumeActivity() {
        if (!RecordingPermissions.allRequiredForRecordingGranted(this)) {
            handleRecordingPermissionLoss();
            return false;
        }

        // We can force reading at specific intervals like this
        _scheduler = Executors.newScheduledThreadPool(1);
        _scheduler.scheduleAtFixedRate(createRecordingTask(), 0, 1, TimeUnit.SECONDS);
        SharedData.getInstance().IsPaused = false;
        _stopWatch.startTImer();
        CharSequence text = "Resuming activity";
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
        return true;
    }

    public void discardActivity() {
        SharedData.getInstance().IsRecording = false;
        SharedData.getInstance().IsPaused = false;

        // We don't want to block the UI
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    _scheduler.awaitTermination(300, TimeUnit.MILLISECONDS);
                    _scheduler.shutdownNow();
                } catch (InterruptedException e) {
                }
                int id = SharedData.getInstance().ActivityId;

                SqlLogger sqlLogger = new SqlLogger();
                sqlLogger.deleteActivity(id);
            }
        });

        _stopWatch.pauseTimer();
        _stopWatch.resetTimer();

        CharSequence text = "Discarded activity";
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private Runnable createRecordingTask() {
        final SqlLogger sqlLogger = new SqlLogger();
        return new PermissionCheckedTask(
                new PermissionCheckedTask.PermissionCheck() {
                    @Override
                    public boolean allRequiredPermissionsGranted() {
                        return RecordingPermissions.allRequiredForRecordingGranted(
                                SportLoggerService.this);
                    }
                },
                sqlLogger,
                new Runnable() {
                    @Override
                    public void run() {
                        handleRecordingPermissionLoss();
                    }
                });
    }

    private synchronized void handleRecordingPermissionLoss() {
        if (_permissionLossHandled) {
            return;
        }
        _permissionLossHandled = true;

        SharedData shared = SharedData.getInstance();
        shared.IsRecording = false;
        shared.IsPaused = false;
        shared.setHeartRate(0);

        if (_scheduler != null) {
            _scheduler.shutdownNow();
            _scheduler = null;
        }

        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (_serviceClient != null) {
                    _serviceClient.onRecordingPermissionLost();
                }
                stopSelf();
            }
        });
    }
}
