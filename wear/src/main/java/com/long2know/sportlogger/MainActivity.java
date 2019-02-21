package com.long2know.sportlogger;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.content.ContextCompat;
import android.support.wear.ambient.AmbientModeSupport;
import android.support.wear.widget.drawer.WearableActionDrawerView;
import android.support.wear.widget.drawer.WearableNavigationDrawerView;
import android.util.Log;
import static android.support.constraint.Constraints.TAG;

import android.view.MenuItem;
import android.os.Handler;
import android.os.Looper;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.long2know.sportlogger.services.ISportLoggerServiceClient;
import com.long2know.sportlogger.services.SportLoggerService;
import com.long2know.utilities.data_access.SqlLogger;
import com.long2know.utilities.models.Config;
import com.long2know.utilities.models.LocationData;
import com.long2know.utilities.models.Session;
import com.long2know.utilities.models.SharedData;
import com.long2know.utilities.models.SportActivity;

import java.util.concurrent.ScheduledExecutorService;

public class MainActivity extends FragmentActivity implements
        AmbientModeSupport.AmbientCallbackProvider,
        MenuItem.OnMenuItemClickListener,
        WearableNavigationDrawerView.OnItemSelectedListener,
        ActivityCompat.OnRequestPermissionsResultCallback,
        ISportLoggerServiceClient {

    private SensorFragment _sensorFragment;
    private StartActivityFragment _startFragment;
    private EndActivityFragment _endFragment;
    private FragmentManager _fragmentManager;
    private WearableNavigationDrawerView _wearableNavigationDrawer;
    private WearableActionDrawerView _wearableActionDrawer;

    private SportLoggerService _loggingService;
    private ServiceConnection _loggingServiceConnection;
    private static Intent _serviceIntent;

//    private SensorListener _sensorListener;
//    private GpsListener _locationListener;
    private ScheduledExecutorService _scheduler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Enables Ambient mode.
         AmbientModeSupport.attach(this);
        Config.activityContext = this;

        // Initialize the fragments and set set initial content.
        Bundle sargs = new Bundle();
        Bundle aargs = new Bundle();
        Bundle eargs = new Bundle();
        _sensorFragment = new SensorFragment();
        _sensorFragment.setArguments(sargs);
        _startFragment = new StartActivityFragment();
        _startFragment.setArguments(aargs);
        _endFragment = new EndActivityFragment();
        _endFragment.setArguments(eargs);
        _fragmentManager = this.getSupportFragmentManager();

//        _fragmentManager.beginTransaction().replace(R.id.content_frame, _sensorFragment).commit();

        // Top Navigation Drawer
        _wearableNavigationDrawer = findViewById(R.id.top_navigation_drawer);
        _wearableNavigationDrawer.setAdapter(new NavigationAdapter(this));
        // Peeks navigation drawer on the top.
//        _wearableNavigationDrawer.getController().peekDrawer();
        _wearableNavigationDrawer.addOnItemSelectedListener(this);

        // Bottom Action Drawer
        _wearableActionDrawer = findViewById(R.id.bottom_action_drawer);
        // Peeks action drawer on the bottom.
        _wearableActionDrawer.setOnMenuItemClickListener(this);

        // Start out making the drawer locked out of sight
        _wearableActionDrawer.setIsLocked(true);

        // Create a handler for the UI thread
        // Defines a Handler object that's attached to the UI thread
        Config.activityHandler = new Handler(Looper.getMainLooper()) {
            public void handleMessage(Message msg) {
                int messageType = msg.what;

                if (messageType == 0) {
                    LocationData data = (LocationData) msg.obj;
                    _sensorFragment.updateLocation(data);
                } else {
                    int sensorType = msg.arg1;
                    SensorEvent event = (SensorEvent) msg.obj;

                    switch (sensorType) {
                        case Sensor.TYPE_HEART_RATE: {
                            _sensorFragment.updateHeartRate(event.values[0]);
                            break;
                        }
                        case Sensor.TYPE_STEP_COUNTER: {
                            // _steps.setText(Float.toString(event.values[0]));
                            break;
                        }
                        case Sensor.TYPE_STEP_DETECTOR: {
                            _sensorFragment.updateSteps(SharedData.getInstance().getData().Steps);
                            break;
                        }
                        default: {
                            // Do nothing ..
                        }
                    }
                }
            }
        };

        int res = (int) (checkCallingPermission(Manifest.permission.BODY_SENSORS) ^
                checkCallingPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ^
                checkCallingPermission(Manifest.permission.ACCESS_FINE_LOCATION));

        if (res == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.BODY_SENSORS,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, 1);
        }

        // If the service indicates that a recording is in progress, continue
        SharedData shared = SharedData.getInstance();
        if (shared.IsRecording && !shared.IsPaused) {
            _fragmentManager.beginTransaction().replace(R.id.content_frame, _sensorFragment).commit();
            _wearableActionDrawer.getController().peekDrawer();
        } else if (shared.IsPaused) {
            _fragmentManager.beginTransaction().replace(R.id.content_frame, _endFragment).commit();
        } else {
            _fragmentManager.beginTransaction().replace(R.id.content_frame, _startFragment).commit();
        }

        _loggingServiceConnection = new ServiceConnection() {
            public void onServiceDisconnected(ComponentName name)            {
                _loggingService = null;
            }
            public void onServiceConnected(ComponentName name, IBinder service)            {
                _loggingService = ((SportLoggerService.LocalBinder) service).getService();
                SportLoggerService.setServiceClient(MainActivity.this);
            }
        };
    }

    @Override
    protected void onStart() {
        super.onStart();
        startAndBindService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startAndBindService();
    }

    @Override
    public void onDestroy() {
        if (SharedData.getInstance().IsRecording) {
            if (_loggingServiceConnection != null) {
                unbindService(_loggingServiceConnection);
            }
        } else {
            stopAndUnbindServiceIfRequired();
        }

        super.onDestroy();
    }

    public void onLoggerUpdate(SharedData data) {

    }

    // Start the logger service and bind the activity to the service
    private void startAndBindService() {
        _serviceIntent = new Intent(this, SportLoggerService.class);

        // Start the service in case it isn't already running
//        startForegroundService(_serviceIntent);
//        startService(_serviceIntent);
        ContextCompat.startForegroundService(this, _serviceIntent);

        // Now bind to service
        bindService(_serviceIntent, _loggingServiceConnection, Context.BIND_AUTO_CREATE);
        Session.setBoundToService(true);
    }

    // Start the logger service and bind the activity to the service
    private void stopAndUnbindServiceIfRequired() {
        if(Session.isBoundToService())        {
            unbindService(_loggingServiceConnection);
            Session.setBoundToService(false);
        }

        if(!Session.isStarted()) {
            //serviceIntent = new Intent(this, GpsLoggingService.class);
            stopService(_serviceIntent);
        }
    }

    public void startNewActivity() {
        _loggingService.startNewActivity();
        _sensorFragment.startTImer();
        _fragmentManager.beginTransaction().replace(R.id.content_frame, _sensorFragment).commit();
        _wearableActionDrawer.getController().peekDrawer();
    }

    public void stopActivity() {
        _loggingService.stopActivity();
        _sensorFragment.pauseTimer();
        _sensorFragment.resetTimer();

        // Send the activity to the phone
        SqlLogger sqlLogger = new SqlLogger();
        SportActivity activity = sqlLogger.getSportActivity(SharedData.getInstance().ActivityId);
        activity.SportTrackPoints = sqlLogger.getTrackPointsByActivity(SharedData.getInstance().ActivityId);

        try {
            // Transmit the activity to the phone
            // TODO: mark the transmission as complete
            byte[] bytes = SportActivity.serialize(activity);
            Asset asset = Asset.createFromBytes(bytes);
            PutDataMapRequest dataMap = PutDataMapRequest.create(getString(R.string.wear_path));
            dataMap.getDataMap().putAsset("sportActivity", asset);
            PutDataRequest request = dataMap.asPutDataRequest();
            Task<DataItem> putTask = Wearable.getDataClient(this).putDataItem(request);

//            PutDataRequest request = PutDataRequest.create(getString(R.string.wear_path));
//            request.putAsset("sportActivity", asset);
//            Task<DataItem> putTask = Wearable.getDataClient(this).putDataItem(request);
        }
        catch (Exception e) {
            Log.e(TAG,"Could not serialize activity");
        }

        _fragmentManager.beginTransaction().replace(R.id.content_frame, _startFragment).commit();
        _wearableActionDrawer.getController().closeDrawer();
    }

    public void pauseActivity() {
        _loggingService.pauseActivity();
        _sensorFragment.pauseTimer();
        _fragmentManager.beginTransaction().replace(R.id.content_frame, _endFragment).commit();
        _wearableActionDrawer.getController().closeDrawer();
    }

    public void resumeActivity() {
        _loggingService.resumeActivity();
        _sensorFragment.startTImer();
        _fragmentManager.beginTransaction().replace(R.id.content_frame, _sensorFragment).commit();
        _wearableActionDrawer.getController().peekDrawer();
    }

    public void discardActivity() {
        _loggingService.discardActivity();
        _sensorFragment.pauseTimer();
        _sensorFragment.resetTimer();
        _fragmentManager.beginTransaction().replace(R.id.content_frame, _startFragment).commit();
        _wearableActionDrawer.getController().closeDrawer();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        boolean flag = true;
        for (int i = 0; i < permissions.length; i++) {
            if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                flag = false;
            }
        }

        if (flag) {
//            _sensorListener = new SensorListener();
//            _locationListener = new GpsListener();
//
//            _sensorThread = new Thread(new SensorListener());
//            _locationThread = new Thread(new GpsListener());
//            _sensorThread.start();
//            _locationThread.start();
        }
    }

    @Override
    public boolean onMenuItemClick(MenuItem menuItem) {
        Log.d(TAG, "onMenuItemClick(): " + menuItem);
        final int itemId = menuItem.getItemId();

        switch (itemId) {
            case R.id.menu_pause: {
                this.pauseActivity();
                break;
            }
        }

//        String toastMessage = "";
//
//        switch (itemId) {
//            case R.id.menu_planet_name:
//                toastMessage = mSolarSystem.get(mSelectedPlanet).getName();
//                break;
//            case R.id.menu_number_of_moons:
//                toastMessage = mSolarSystem.get(mSelectedPlanet).getMoons();
//                break;
//            case R.id.menu_volume:
//                toastMessage = mSolarSystem.get(mSelectedPlanet).getVolume();
//                break;
//            case R.id.menu_surface_area:
//                toastMessage = mSolarSystem.get(mSelectedPlanet).getSurfaceArea();
//                break;
//        }
//
//        mWearableActionDrawer.getController().closeDrawer();

//        if (toastMessage.length() > 0) {
//            Toast toast = Toast.makeText(
//                    getApplicationContext(),
//                    toastMessage,
//                    Toast.LENGTH_SHORT);
//            toast.show();
//            return true;
//        } else {
//            return false;
//        }
        return true;
    }

    // Updates content when user changes between items in the navigation drawer.
    @Override
    public void onItemSelected(int position) {
        Log.d(TAG, "WearableNavigationDrawerView triggered onItemSelected(): " + position);
//        mSelectedPlanet = position;
//
//        String selectedPlanetImage = mSolarSystem.get(mSelectedPlanet).getImage();
//        int drawableId =
//                getResources().getIdentifier(selectedPlanetImage, "drawable", getPackageName());
//        mPlanetFragment.updatePlanet(drawableId);
    }

    @Override
    public AmbientModeSupport.AmbientCallback getAmbientCallback() {
        return new MyAmbientCallback();
    }

    private class MyAmbientCallback extends AmbientModeSupport.AmbientCallback {
        /**
         * Prepares the UI for ambient mode.
         */
        @Override
        public void onEnterAmbient(Bundle ambientDetails) {
            super.onEnterAmbient(ambientDetails);
            Log.d(TAG, "onEnterAmbient() " + ambientDetails);

            _sensorFragment.onEnterAmbientInFragment(ambientDetails);
//            mWearableNavigationDrawer.getController().closeDrawer();
//            mWearableActionDrawer.getController().closeDrawer();
        }

        /**
         * Restores the UI to active (non-ambient) mode.
         */
        @Override
        public void onExitAmbient() {
            super.onExitAmbient();
            Log.d(TAG, "onExitAmbient()");

            _sensorFragment.onExitAmbientInFragment();
//            mWearableActionDrawer.getController().peekDrawer();
        }


    }

    private final class NavigationAdapter
            extends WearableNavigationDrawerView.WearableNavigationDrawerAdapter {

        private final Context _context;

        public NavigationAdapter(Context context) {
            _context = context;
        }

        @Override
        public int getCount() {
            return 0;
        }

        @Override
        public String getItemText(int pos) {
            return "";
        }

        @Override
        public Drawable getItemDrawable(int pos) {
            return null;
//            String navigationIcon = mSolarSystem.get(pos).getNavigationIcon();
//
//            int drawableNavigationIconId =
//                    getResources().getIdentifier(navigationIcon, "drawable", getPackageName());
//
//            return _context.getDrawable(drawableNavigationIconId);
        }

        /**
         * Stops the service if it isn't logging. Also unbinds.
         */
        private void stopAndUnbindServiceIfRequired() {
            if(Session.isBoundToService()) {
                unbindService(_loggingServiceConnection);
                Session.setBoundToService(false);
            }

            if(!Session.isStarted())            {
                //serviceIntent = new Intent(this, GpsLoggingService.class);
                stopService(_serviceIntent);
            }

        }
    }
}