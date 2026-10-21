package com.long2know.sportlogger;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.wear.ambient.AmbientModeSupport;
import androidx.wear.widget.drawer.WearableActionDrawerView;
import androidx.wear.widget.drawer.WearableNavigationDrawerView;

import com.long2know.sportlogger.acquisition.RecordingPermissionPolicy;
import com.long2know.sportlogger.acquisition.RouteStatus;
import com.long2know.sportlogger.acquisition.healthservices.HealthServicesExerciseSnapshot;
import com.long2know.sportlogger.recording.RecordingSnapshot;
import com.long2know.sportlogger.recording.CompletedRecordingSnapshot;
import com.long2know.sportlogger.recording.RecordingState;
import com.long2know.sportlogger.services.ISportLoggerServiceClient;
import com.long2know.sportlogger.services.SportLoggerService;
import com.long2know.utilities.models.SharedData;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends FragmentActivity implements
        AmbientModeSupport.AmbientCallbackProvider,
        MenuItem.OnMenuItemClickListener,
        WearableNavigationDrawerView.OnItemSelectedListener,
        ISportLoggerServiceClient {

    private static final String TAG = "MainActivity";
    private static final int PERMISSIONS_REQUEST = 1;
    private static final String SENSOR_FRAGMENT_TAG = "sensor";
    private static final String START_FRAGMENT_TAG = "start";
    private static final String END_FRAGMENT_TAG = "end";

    private final ServiceConnection loggingServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceDisconnected(ComponentName name) {
            loggingService = null;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (!bindRequested) {
                return;
            }
            loggingService = ((SportLoggerService.LocalBinder) service).getService();
            loggingService.registerClient(MainActivity.this);
            loggingService.refreshAcquisitionState();
        }
    };

    private SensorFragment sensorFragment;
    private StartActivityFragment startFragment;
    private EndActivityFragment endFragment;
    private FragmentManager fragmentManager;
    private WearableActionDrawerView wearableActionDrawer;
    private SportLoggerService loggingService;
    private boolean bindRequested;
    private RecordingState displayedState;
    private RouteStatus routeStatus = RouteStatus.UNKNOWN;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        AmbientModeSupport.attach(this);

        fragmentManager = getSupportFragmentManager();
        bindRestoredFragments();

        WearableNavigationDrawerView wearableNavigationDrawer =
                findViewById(R.id.top_navigation_drawer);
        wearableNavigationDrawer.setAdapter(new NavigationAdapter(this));
        wearableNavigationDrawer.addOnItemSelectedListener(this);

        wearableActionDrawer = findViewById(R.id.bottom_action_drawer);
        wearableActionDrawer.setOnMenuItemClickListener(this);
        wearableActionDrawer.setIsLocked(true);

        renderSharedState();
        requestRecordingPermissions();
    }

    @Override
    protected void onStart() {
        super.onStart();
        startAndBindService();
    }

    @Override
    protected void onStop() {
        if (loggingService != null) {
            loggingService.unregisterClient(this);
        }
        if (bindRequested) {
            unbindService(loggingServiceConnection);
            bindRequested = false;
        }
        loggingService = null;
        super.onStop();
    }

    @Override
    public void onLoggerUpdate(SharedData data) {
        if (sensorFragment.isAdded()) {
            sensorFragment.refresh();
        }
    }

    @Override
    public void onRouteStatusChanged(RouteStatus status) {
        routeStatus = status;
        sensorFragment.setRouteStatus(status);
    }

    @Override
    public void onAcquisitionStateChanged(
            HealthServicesExerciseSnapshot snapshot) {
    }

    @Override
    public void onLatestCompletedChanged(
            @Nullable CompletedRecordingSnapshot snapshot) {
    }

    @Override
    public void onRecordingStateChanged(RecordingSnapshot snapshot) {
        if (snapshot.getState() == displayedState) {
            if (sensorFragment.isAdded()) {
                sensorFragment.refresh();
            }
            return;
        }
        displayedState = snapshot.getState();
        switch (snapshot.getState()) {
            case RECORDING:
                showFragment(sensorFragment);
                sensorFragment.setRouteStatus(routeStatus);
                wearableActionDrawer.setIsLocked(false);
                wearableActionDrawer.getController().peekDrawer();
                break;
            case PAUSED:
            case INTERRUPTED:
            case STOPPING:
            case DISCARDING:
            case EXPORT_PENDING:
                showFragment(endFragment);
                wearableActionDrawer.getController().closeDrawer();
                break;
            case EXPORT_QUEUED:
                showFragment(startFragment);
                wearableActionDrawer.setIsLocked(true);
                wearableActionDrawer.getController().closeDrawer();
                sensorFragment.resetTimer();
                break;
            case IDLE:
                showFragment(startFragment);
                wearableActionDrawer.setIsLocked(true);
                wearableActionDrawer.getController().closeDrawer();
                sensorFragment.resetTimer();
                break;
            case ERROR:
            case STARTING:
            default:
                break;
        }
    }

    @Override
    public void onLoggerError(String code, String message) {
        Toast.makeText(this, code + ": " + message, Toast.LENGTH_LONG).show();
    }

    public void startNewActivity() {
        if (loggingService == null || !loggingService.startNewActivity()) {
            showServiceUnavailable();
        }
    }

    public void stopActivity() {
        if (loggingService == null || !loggingService.stopActivity()) {
            showServiceUnavailable();
        }
    }

    public void pauseActivity() {
        if (loggingService == null || !loggingService.pauseActivity()) {
            showServiceUnavailable();
        }
    }

    public void resumeActivity() {
        if (loggingService == null || !loggingService.resumeActivity()) {
            showServiceUnavailable();
        }
    }

    public void discardActivity() {
        if (loggingService == null || !loggingService.discardActivity()) {
            showServiceUnavailable();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSIONS_REQUEST) {
            return;
        }
        for (int grantResult : grantResults) {
            if (grantResult != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(
                        this,
                        R.string.recording_permissions_limited,
                        Toast.LENGTH_LONG).show();
                break;
            }
        }
        if (loggingService != null) {
            loggingService.refreshAcquisitionState();
        }
    }

    @Override
    public boolean onMenuItemClick(MenuItem menuItem) {
        if (menuItem.getItemId() == R.id.menu_pause) {
            pauseActivity();
        }
        return true;
    }

    @Override
    public void onItemSelected(int position) {
        Log.d(TAG, "Navigation item selected: " + position);
    }

    @Override
    public AmbientModeSupport.AmbientCallback getAmbientCallback() {
        return new AmbientCallback();
    }

    private void startAndBindService() {
        if (bindRequested) {
            return;
        }
        Intent serviceIntent = new Intent(this, SportLoggerService.class);
        startService(serviceIntent);
        bindRequested = bindService(
                serviceIntent,
                loggingServiceConnection,
                Context.BIND_AUTO_CREATE);
        if (!bindRequested) {
            showServiceUnavailable();
        }
    }

    private void renderSharedState() {
        SharedData sharedData = SharedData.getInstance();
        if (sharedData.isRecording() && !sharedData.isPaused()) {
            displayedState = RecordingState.RECORDING;
            showFragment(sensorFragment);
            wearableActionDrawer.setIsLocked(false);
        } else if (sharedData.isPaused()) {
            displayedState = RecordingState.PAUSED;
            showFragment(endFragment);
        } else {
            displayedState = RecordingState.IDLE;
            showFragment(startFragment);
        }
    }

    private void showFragment(Fragment fragment) {
        Fragment current = fragmentManager.findFragmentById(R.id.content_frame);
        if (current != null && current.getClass() == fragment.getClass()) {
            bindFragment(current);
            return;
        }
        if (!fragmentManager.isStateSaved()) {
            fragmentManager.beginTransaction()
                    .replace(R.id.content_frame, fragment, fragmentTag(fragment))
                    .commit();
        }
    }

    private void bindRestoredFragments() {
        Fragment current = fragmentManager.findFragmentById(R.id.content_frame);
        bindFragment(current);

        Fragment restoredSensor = fragmentManager.findFragmentByTag(SENSOR_FRAGMENT_TAG);
        if (sensorFragment == null && restoredSensor instanceof SensorFragment) {
            sensorFragment = (SensorFragment) restoredSensor;
        }
        Fragment restoredStart = fragmentManager.findFragmentByTag(START_FRAGMENT_TAG);
        if (startFragment == null && restoredStart instanceof StartActivityFragment) {
            startFragment = (StartActivityFragment) restoredStart;
        }
        Fragment restoredEnd = fragmentManager.findFragmentByTag(END_FRAGMENT_TAG);
        if (endFragment == null && restoredEnd instanceof EndActivityFragment) {
            endFragment = (EndActivityFragment) restoredEnd;
        }

        if (sensorFragment == null) {
            sensorFragment = new SensorFragment();
        }
        if (startFragment == null) {
            startFragment = new StartActivityFragment();
        }
        if (endFragment == null) {
            endFragment = new EndActivityFragment();
        }
    }

    private void bindFragment(Fragment fragment) {
        if (fragment instanceof SensorFragment) {
            sensorFragment = (SensorFragment) fragment;
        } else if (fragment instanceof StartActivityFragment) {
            startFragment = (StartActivityFragment) fragment;
        } else if (fragment instanceof EndActivityFragment) {
            endFragment = (EndActivityFragment) fragment;
        }
    }

    private static String fragmentTag(Fragment fragment) {
        if (fragment instanceof SensorFragment) {
            return SENSOR_FRAGMENT_TAG;
        }
        if (fragment instanceof StartActivityFragment) {
            return START_FRAGMENT_TAG;
        }
        if (fragment instanceof EndActivityFragment) {
            return END_FRAGMENT_TAG;
        }
        throw new IllegalArgumentException("Unknown recording fragment " + fragment.getClass());
    }

    private void requestRecordingPermissions() {
        List<String> missing = new ArrayList<>();
        for (String permission : RecordingPermissionPolicy.runtimePermissions(
                Build.VERSION.SDK_INT,
                true,
                true)) {
            addIfMissing(missing, permission);
        }
        if (!missing.isEmpty()) {
            ActivityCompat.requestPermissions(
                    this,
                    missing.toArray(new String[0]),
                    PERMISSIONS_REQUEST);
        }
    }

    private void addIfMissing(List<String> missing, String permission) {
        if (ActivityCompat.checkSelfPermission(this, permission)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(permission);
        }
    }

    private void showServiceUnavailable() {
        Toast.makeText(this, R.string.recording_service_unavailable, Toast.LENGTH_SHORT).show();
    }

    private final class AmbientCallback extends AmbientModeSupport.AmbientCallback {
        @Override
        public void onEnterAmbient(Bundle ambientDetails) {
            super.onEnterAmbient(ambientDetails);
            sensorFragment.onEnterAmbientInFragment(ambientDetails);
        }

        @Override
        public void onExitAmbient() {
            super.onExitAmbient();
            sensorFragment.onExitAmbientInFragment();
        }
    }

    private static final class NavigationAdapter
            extends WearableNavigationDrawerView.WearableNavigationDrawerAdapter {
        private final Context context;

        private NavigationAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getCount() {
            return 0;
        }

        @Override
        public String getItemText(int position) {
            return "";
        }

        @Override
        public Drawable getItemDrawable(int position) {
            return context.getDrawable(android.R.drawable.ic_menu_compass);
        }
    }
}
