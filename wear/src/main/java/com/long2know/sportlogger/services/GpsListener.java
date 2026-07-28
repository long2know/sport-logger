package com.long2know.sportlogger.services;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.location.LocationProvider;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.concurrent.ScheduledExecutorService;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;

import com.long2know.utilities.models.Config;
import com.long2know.utilities.models.LocationData;
import com.long2know.utilities.models.SharedData;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.UUID;

public class GpsListener implements Runnable  {
    private static final String TAG = "GpsListener";
    private static final int MESSAGE_STOP = 0;
    private static final int MESSAGE_RETRY = 1;

    public static Handler WorkerHandler;
    private Handler _handler;
    private ScheduledExecutorService _scheduler;
    private static String _deviceId;

    private static long _minTimeMillis = 750;
    private static float _minDistanceMeters = (float) 0.1;
    private static float _minAccuracyMeters = 75;

    private LocationManager _locationManager;
    private Criteria _criteria;
    private LocationRegistration<LocationListener> _locationRegistration;
    private GnssStatus.Callback _gnssStatusListener;
    private GnssMeasurementsEvent.Callback _gnssMeasurementsListener;
    private GnssStatus _gnssStatus;

    private boolean _isGpsLocked = false;
    private boolean _providerReceiverRegistered;

    private static String _uniqueId = null;
    private static final String PREF_UNIQUE_ID = "PREF_UNIQUE_ID_LONGTOKNOW_SPORTLOGGER";
    private boolean _isStarted = true;
    private final BroadcastReceiver _providersChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (LocationManager.MODE_CHANGED_ACTION.equals(action)
                    || LocationManager.PROVIDERS_CHANGED_ACTION.equals(action)) {
                Handler workerHandler = WorkerHandler;
                if (workerHandler != null) {
                    workerHandler.sendEmptyMessage(MESSAGE_RETRY);
                }
            }
        }
    };

    // Defines the code to run for this task.
    @Override
    public void run() {
        // Moves the current Thread into the background
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);

        Looper.prepare();
        _handler = Config.handler;

        WorkerHandler = new Handler(Looper.myLooper()) {
            public void handleMessage(Message msg) {
                Log.d(TAG, "Received a message!");
                // For now, the only messages are start/stop
                if (msg.what == MESSAGE_STOP) {
                    // The sensors are started by default
                    if (_isStarted) {
                        _isStarted = false;
                        stopListeners();
                        unregisterProviderChangesReceiver();
                        Looper.myLooper().quit();
                    }
                } else if (msg.what == MESSAGE_RETRY && _isStarted) {
                    startListeners();
                }
            }
        };

        registerProviderChangesReceiver();
        try {
            startListeners();
            Looper.loop();
        } finally {
            stopListeners();
            unregisterProviderChangesReceiver();
        }
    }

    public void startListeners() {
        // Get a unique ID for the device
        _deviceId = getUniqueId(Config.context);

        if (_locationRegistration == null) {
            _criteria = new Criteria();
            _criteria.setAccuracy(Criteria.ACCURACY_FINE);
//        criteria.setAltitudeRequired(true);
//        criteria.setBearingRequired(true);
//        criteria.setCostAllowed(true);
//        criteria.setPowerRequirement(Criteria.POWER_LOW);

            _locationManager =
                    (LocationManager) Config.context.getSystemService(Context.LOCATION_SERVICE);
            _locationRegistration = new LocationRegistration<>(
                    new AndroidLocationBackend(),
                    this::reportRouteStatus);
        }
        _locationRegistration.start();
//        initDatabase();
    }

    public void stopListeners()    {
        if (_locationRegistration != null) {
            _locationRegistration.stop();
        }
        SharedData singleton = SharedData.getInstance();
        singleton.setLocation(new LocationData());
    }

    public synchronized static String getUniqueId(Context context) {
        if (_deviceId == null) {
            SharedPreferences sharedPrefs = context.getSharedPreferences(
                    PREF_UNIQUE_ID, Context.MODE_PRIVATE);
            _uniqueId = sharedPrefs.getString(PREF_UNIQUE_ID, null);
            if (_uniqueId == null) {
                _uniqueId = UUID.randomUUID().toString();
                Editor editor = sharedPrefs.edit();
                editor.putString(PREF_UNIQUE_ID, _uniqueId);
                editor.commit();
            }
        }
        return _uniqueId;
    }

    public LocationListener createLocationListener(final Location currentLocation) {
        LocationListener listener = new LocationListener() {

            Location _lastLocation = currentLocation;
            double _totalDistance = 0;

            @Override
            public void onLocationChanged(Location location) {
                if (location != null
                        && location.hasAccuracy()
                        && location.getAccuracy() <= _minAccuracyMeters) {
                    GregorianCalendar greg = new GregorianCalendar();
                    TimeZone tz = greg.getTimeZone();
                    int offset = tz.getOffset(System.currentTimeMillis());
                    greg.add(Calendar.SECOND, (offset / 1000) * -1);
                    String ts = Config.DotnetTimestampFormat.format(greg.getTime());
                    double distance =
                            _lastLocation == null ? 0.0 : calculateDistance(_lastLocation, location);
                    _totalDistance += distance;
                    LocationData data = new LocationData(location, _totalDistance, distance);
                    Message completeMessage = _handler.obtainMessage(0, 1, 1, data);
                    completeMessage.sendToTarget();
                    _lastLocation = location;

                    SharedData singleton = SharedData.getInstance();
                    singleton.setLocation(data);
                }
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
                if (provider.equalsIgnoreCase("gps")) {
                    if (status == LocationProvider.OUT_OF_SERVICE || status == LocationProvider.TEMPORARILY_UNAVAILABLE) {
                        // We lost our lock
                        _isGpsLocked = false;
                    }
                }
            }

            @Override
            public void onProviderEnabled(String provider) {
                if (provider.equalsIgnoreCase("gps")) {
                    // Our provider is enabled
                    _isGpsLocked = true;
                }
                if (_locationRegistration != null) {
                    _locationRegistration.onProviderEnabled();
                }
            }

            @Override
            public void onProviderDisabled(String provider) {
                if (provider.equalsIgnoreCase("gps")) {
                    // We lost our lock
                    _isGpsLocked = false;
                }
                if (_locationRegistration != null) {
                    _locationRegistration.onProviderDisabled(provider);
                }
            }
        };

        return listener;
    }

    private final class AndroidLocationBackend
            implements LocationRegistration.Backend<LocationListener> {
        @Override
        public boolean hasLocationPermission() {
            return ContextCompat.checkSelfPermission(
                    Config.context,
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public boolean hasAnyProvider() {
            return _locationManager != null && !_locationManager.getAllProviders().isEmpty();
        }

        @Override
        public boolean isLocationEnabled() {
            if (_locationManager == null) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return _locationManager.isLocationEnabled();
            }
            return !_locationManager.getProviders(true).isEmpty();
        }

        @Override
        public String getBestProvider() {
            return _locationManager == null
                    ? null
                    : _locationManager.getBestProvider(_criteria, true);
        }

        @Override
        public boolean isProviderEnabled(String provider) {
            return _locationManager != null && _locationManager.isProviderEnabled(provider);
        }

        @Override
        @SuppressWarnings("MissingPermission")
        public LocationListener createLocationListener(String provider) {
            Location currentLocation = _locationManager.getLastKnownLocation(provider);
            return GpsListener.this.createLocationListener(currentLocation);
        }

        @Override
        @SuppressWarnings("MissingPermission")
        public void requestLocationUpdates(String provider, LocationListener listener) {
            _locationManager.requestLocationUpdates(
                    provider,
                    _minTimeMillis,
                    _minDistanceMeters,
                    listener);
        }

        @Override
        public void removeLocationUpdates(LocationListener listener) {
            _locationManager.removeUpdates(listener);
        }
    }

    private void reportRouteStatus(LocationRegistration.Status status) {
        switch (status) {
            case AVAILABLE:
                Log.i(TAG, "Location route available.");
                break;
            case NO_PROVIDER:
                Log.w(TAG, "No compatible location provider; route recording is unavailable.");
                break;
            case LOCATION_DISABLED:
                Log.w(TAG, "Location is disabled; recording continues without route updates.");
                break;
            case PERMISSION_DENIED:
                Log.w(TAG, "Location permission is unavailable; route recording is unavailable.");
                break;
            case STOPPED:
                Log.d(TAG, "Location route listener stopped.");
                break;
        }
    }

    private void registerProviderChangesReceiver() {
        if (_providerReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(LocationManager.MODE_CHANGED_ACTION);
        filter.addAction(LocationManager.PROVIDERS_CHANGED_ACTION);
        ContextCompat.registerReceiver(
                Config.context,
                _providersChangedReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        _providerReceiverRegistered = true;
    }

    private void unregisterProviderChangesReceiver() {
        if (!_providerReceiverRegistered) {
            return;
        }
        Config.context.unregisterReceiver(_providersChangedReceiver);
        _providerReceiverRegistered = false;
    }

    @SuppressWarnings({"MissingPermission"})
    private void addGnssStatusListener() {
        _gnssStatusListener = new GnssStatus.Callback() {
            @Override
            public void onStarted() {
            }

            @Override
            public void onStopped() {
            }

            @Override
            public void onFirstFix(int ttffMillis) {
            }

            @Override
            public void onSatelliteStatusChanged(GnssStatus status) {
                _gnssStatus = status;
            }
        };

        _locationManager.registerGnssStatusCallback(_gnssStatusListener);
    }

    @SuppressWarnings({"MissingPermission"})
    private void addGnssMeasurementsListener() {
        _gnssMeasurementsListener = new GnssMeasurementsEvent.Callback() {
            @Override
            public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) {
            }

            @Override
            public void onStatusChanged(int status) {
                final String statusMessage;
                switch (status) {
                    case STATUS_LOCATION_DISABLED:
                        statusMessage = "disabled";
                        break;
                    case STATUS_NOT_SUPPORTED:
                        statusMessage = "not supported";
                        break;
                    case STATUS_READY:
                        statusMessage = "ready";
                        break;
                    default:
                        statusMessage = "unknown";
                }
                Log.d(TAG, "GnssMeasurementsEvent.Callback.onStatusChanged() - " + statusMessage);
            }
        };

        _locationManager.registerGnssMeasurementsCallback(_gnssMeasurementsListener);
    }

    public double calculateDistance(Location start, Location end)    {
        double distance = 0.0;
        boolean convertToMeters = true;
        double factor = convertToMeters ? 1000.0 : 1.0;

        double startLat = start.getLatitude();
        double startLon = start.getLongitude();
        double endLat = end.getLatitude();
        double endLon = end.getLongitude();

        if (startLat != 0.0 && startLon != 0.0 && endLat != 0.0 && endLon != 0.0)
        {
            double lat1 = Math.toRadians(startLat);
            double lon1 = Math.toRadians(startLon);
            double lat2 = Math.toRadians(endLat);
            double lon2 = Math.toRadians(endLon);

            double longdis = Math.toRadians(startLon - endLon); //calculating longitudinal difference
            double angudis = Math.sin(lat1) * Math.sin(lat2) + Math.cos(lat1) * Math.cos(lat2) * Math.cos(longdis);
            angudis = Math.acos(angudis); //converted back to radians
            distance = angudis * 6372.795; //multiplied by the radius of the Earth
        }

        // Distance will be in KM, but convert to meters if desired
        return distance / factor;
    }

    // Determine if the watch has GPS
    private boolean hasGps() {
        return Config.context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS);
    }
}
