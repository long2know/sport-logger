package com.long2know.sportlogger;

import android.Manifest;
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
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.wear.ambient.AmbientModeSupport;
import androidx.wear.widget.drawer.WearableActionDrawerView;
import androidx.wear.widget.drawer.WearableNavigationDrawerView;

import com.long2know.sportlogger.recording.RecordingSnapshot;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        AmbientModeSupport.attach(this);

        sensorFragment = new SensorFragment();
        startFragment = new StartActivityFragment();
        endFragment = new EndActivityFragment();
        fragmentManager = getSupportFragmentManager();

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
                wearableActionDrawer.setIsLocked(false);
                wearableActionDrawer.getController().peekDrawer();
                break;
            case PAUSED:
            case STOPPING:
            case DISCARDING:
            case EXPORT_PENDING:
                showFragment(endFragment);
                wearableActionDrawer.getController().closeDrawer();
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
                return;
            }
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
            return;
        }
        if (!fragmentManager.isStateSaved()) {
            fragmentManager.beginTransaction()
                    .replace(R.id.content_frame, fragment)
                    .commit();
        }
    }

    private void requestRecordingPermissions() {
        List<String> missing = new ArrayList<>();
        addIfMissing(missing, Manifest.permission.BODY_SENSORS);
        addIfMissing(missing, Manifest.permission.ACCESS_COARSE_LOCATION);
        addIfMissing(missing, Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            addIfMissing(missing, Manifest.permission.ACTIVITY_RECOGNITION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addIfMissing(missing, Manifest.permission.POST_NOTIFICATIONS);
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
