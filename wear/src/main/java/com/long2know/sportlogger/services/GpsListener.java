package com.long2know.sportlogger.services;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.long2know.utilities.models.LocationData;
import com.long2know.utilities.models.SharedData;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class GpsListener implements ManagedListener {
    private static final String TAG = "GpsListener";
    private static final String PREF_UNIQUE_ID =
            "PREF_UNIQUE_ID_LONGTOKNOW_SPORTLOGGER";
    private static final long MIN_TIME_MILLIS = 750L;
    private static final float MIN_DISTANCE_METERS = 0.1F;
    private static final float MIN_ACCURACY_METERS = 75F;

    private static String _deviceId;
    private static String _uniqueId;

    private final Context _context;
    private final Handler _uiHandler;
    private final Runnable _permissionFailureCallback;
    private final AtomicBoolean _ownerActive;
    private final CountDownLatch _ready = new CountDownLatch(1);
    private final CountDownLatch _stopped = new CountDownLatch(1);
    private final Object _lifecycleLock = new Object();

    private Looper _looper;
    private Handler _workerHandler;
    private LocationManager _locationManager;
    private LocationListener _locationListener;
    private volatile boolean _shutdownRequested;
    private boolean _isGpsLocked;

    GpsListener(
            Context context,
            Handler uiHandler,
            Runnable permissionFailureCallback,
            AtomicBoolean ownerActive) {
        _context = context.getApplicationContext();
        _uiHandler = uiHandler;
        _permissionFailureCallback = permissionFailureCallback;
        _ownerActive = ownerActive;
    }

    @Override
    public void run() {
        android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_BACKGROUND);
        try {
            Looper.prepare();
            synchronized (_lifecycleLock) {
                if (_shutdownRequested) {
                    return;
                }
                _looper = Looper.myLooper();
                _workerHandler = new Handler(_looper);
            }
            if (!_shutdownRequested && _ownerActive.get()) {
                startListeners();
            }
            _ready.countDown();
            if (!_shutdownRequested && _ownerActive.get()) {
                Looper.loop();
            }
        } finally {
            stopListeners();
            synchronized (_lifecycleLock) {
                _workerHandler = null;
                _looper = null;
            }
            _ready.countDown();
            _stopped.countDown();
        }
    }

    @Override
    public void requestShutdown() {
        _ownerActive.set(false);
        Handler handler;
        synchronized (_lifecycleLock) {
            _shutdownRequested = true;
            handler = _workerHandler;
            if (handler == null) {
                return;
            }
        }
        if (!handler.postAtFrontOfQueue(new Runnable() {
            @Override
            public void run() {
                Looper.myLooper().quitSafely();
            }
        })) {
            Looper looper;
            synchronized (_lifecycleLock) {
                looper = _looper;
            }
            if (looper != null) {
                looper.quitSafely();
            }
        }
    }

    @Override
    public boolean awaitStopped(long timeoutMillis) throws InterruptedException {
        return _stopped.await(Math.max(0L, timeoutMillis), TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean awaitReady(long timeoutMillis) throws InterruptedException {
        return _ready.await(Math.max(0L, timeoutMillis), TimeUnit.MILLISECONDS);
    }

    private void startListeners() {
        if (ContextCompat.checkSelfPermission(
                _context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            notifyPermissionFailure();
            return;
        }

        try {
            _deviceId = getUniqueId(_context);
            Criteria criteria = new Criteria();
            criteria.setAccuracy(Criteria.ACCURACY_FINE);
            _locationManager =
                    (LocationManager) _context.getSystemService(Context.LOCATION_SERVICE);
            if (_locationManager == null) {
                return;
            }
            String provider = _locationManager.getBestProvider(criteria, true);
            if (provider == null) {
                return;
            }

            Location currentLocation = _locationManager.getLastKnownLocation(provider);
            _locationListener = createLocationListener(currentLocation);
            _locationManager.requestLocationUpdates(
                    provider,
                    MIN_TIME_MILLIS,
                    MIN_DISTANCE_METERS,
                    _locationListener,
                    _looper);
        } catch (SecurityException exception) {
            Log.e(TAG, "Recording location permission was denied or revoked.", exception);
            notifyPermissionFailure();
        }
    }

    private void notifyPermissionFailure() {
        stopListeners();
        if (_ownerActive.compareAndSet(true, false)
                && _permissionFailureCallback != null) {
            _permissionFailureCallback.run();
        }
    }

    private void stopListeners() {
        if (_locationManager != null && _locationListener != null) {
            try {
                _locationManager.removeUpdates(_locationListener);
            } catch (SecurityException exception) {
                Log.w(TAG, "Location permission was revoked during listener shutdown.", exception);
            }
        }
        _locationListener = null;
        if (!_ownerActive.get()) {
            SharedData.getInstance().setLocation(new LocationData());
        }
    }

    static synchronized String getUniqueId(Context context) {
        if (_deviceId == null) {
            SharedPreferences sharedPrefs = context.getSharedPreferences(
                    PREF_UNIQUE_ID, Context.MODE_PRIVATE);
            _uniqueId = sharedPrefs.getString(PREF_UNIQUE_ID, null);
            if (_uniqueId == null) {
                _uniqueId = UUID.randomUUID().toString();
                sharedPrefs.edit().putString(PREF_UNIQUE_ID, _uniqueId).commit();
            }
            _deviceId = _uniqueId;
        }
        return _deviceId;
    }

    private LocationListener createLocationListener(final Location currentLocation) {
        return new LocationListener() {
            private Location _lastLocation = currentLocation;
            private double _totalDistance;

            @Override
            public void onLocationChanged(Location location) {
                if (!_ownerActive.get()
                        || _shutdownRequested
                        || location == null
                        || !location.hasAccuracy()
                        || location.getAccuracy() > MIN_ACCURACY_METERS) {
                    return;
                }

                double distance = calculateDistance(_lastLocation, location);
                _totalDistance += distance;
                LocationData data =
                        new LocationData(location, _totalDistance, distance);

                if (!_ownerActive.get()) {
                    return;
                }
                SharedData.getInstance().setLocation(data);
                Message message = _uiHandler.obtainMessage(0, 1, 1, data);
                message.sendToTarget();
                _lastLocation = location;
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
                if (LocationManager.GPS_PROVIDER.equalsIgnoreCase(provider)
                        && (status == LocationProvider.OUT_OF_SERVICE
                        || status == LocationProvider.TEMPORARILY_UNAVAILABLE)) {
                    _isGpsLocked = false;
                }
            }

            @Override
            public void onProviderEnabled(String provider) {
                if (LocationManager.GPS_PROVIDER.equalsIgnoreCase(provider)) {
                    _isGpsLocked = true;
                }
            }

            @Override
            public void onProviderDisabled(String provider) {
                if (LocationManager.GPS_PROVIDER.equalsIgnoreCase(provider)) {
                    _isGpsLocked = false;
                }
            }
        };
    }

    double calculateDistance(Location start, Location end) {
        if (start == null || end == null) {
            return 0.0;
        }
        double startLat = start.getLatitude();
        double startLon = start.getLongitude();
        double endLat = end.getLatitude();
        double endLon = end.getLongitude();
        if (startLat == 0.0 || startLon == 0.0 || endLat == 0.0 || endLon == 0.0) {
            return 0.0;
        }

        double latitude1 = Math.toRadians(startLat);
        double latitude2 = Math.toRadians(endLat);
        double longitudeDifference = Math.toRadians(startLon - endLon);
        double angularDistance =
                Math.sin(latitude1) * Math.sin(latitude2)
                        + Math.cos(latitude1)
                        * Math.cos(latitude2)
                        * Math.cos(longitudeDifference);
        angularDistance = Math.acos(angularDistance);
        return angularDistance * 6372.795 / 1000.0;
    }
}
