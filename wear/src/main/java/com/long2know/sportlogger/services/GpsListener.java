package com.long2know.sportlogger.services;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.long2know.utilities.models.Config;
import com.long2know.utilities.models.LocationData;
import com.long2know.utilities.models.SharedData;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Objects;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class GpsListener implements ListenerThreadOwner.ManagedListener {
    private static final String TAG = "GpsListener";

    private static String _deviceId;
    private static final long _minTimeMillis = 750L;
    private static final float _minDistanceMeters = 0.1f;
    private static final float _minAccuracyMeters = 75.0f;
    private static String _uniqueId;
    private static final String PREF_UNIQUE_ID = "PREF_UNIQUE_ID_LONGTOKNOW_SPORTLOGGER";

    private final Context _registrationContext;
    private final Handler _handler;
    private final Object _lifecycleLock = new Object();
    private final CountDownLatch _stopped = new CountDownLatch(1);

    private boolean _runStarted;
    private volatile boolean _stopRequested;
    private Handler _workerHandler;
    private Looper _workerLooper;
    private LocationManager _locationManager;
    private Criteria _criteria;
    private GpsRegistrationLifecycle<LocationListener, LocationEnvironment> _registrationLifecycle;
    private GnssStatus.Callback _gnssStatusListener;
    private GnssMeasurementsEvent.Callback _gnssMeasurementsListener;
    private GnssStatus _gnssStatus;
    private boolean _isGpsLocked;

    public GpsListener(Context context, Handler handler) {
        Context applicationContext =
                Objects.requireNonNull(context, "context").getApplicationContext();
        _registrationContext = Objects.requireNonNull(
                applicationContext,
                "GpsListener requires an application context");
        _handler = Objects.requireNonNull(handler, "handler");
    }

    @Override
    public void run() {
        synchronized (_lifecycleLock) {
            if (_runStarted) {
                throw new IllegalStateException("GpsListener instances may only be run once");
            }
            _runStarted = true;
        }

        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        Looper.prepare();
        Handler workerHandler = new Handler(Objects.requireNonNull(Looper.myLooper()));

        try {
            synchronized (_lifecycleLock) {
                _workerHandler = workerHandler;
                _workerLooper = workerHandler.getLooper();
                if (!_stopRequested) {
                    initializeRegistration(workerHandler);
                    _registrationLifecycle.start();
                }
            }

            if (!_stopRequested) {
                Looper.loop();
            }
        } finally {
            try {
                synchronized (_lifecycleLock) {
                    try {
                        if (_registrationLifecycle != null) {
                            _registrationLifecycle.stop();
                        }
                    } finally {
                        workerHandler.removeCallbacksAndMessages(null);
                        _workerHandler = null;
                        _workerLooper = null;
                    }
                }
            } finally {
                try {
                    SharedData.getInstance().setLocation(new LocationData());
                } finally {
                    _stopped.countDown();
                }
            }
        }
    }

    @Override
    public void requestStop() {
        RuntimeException failure = null;
        Looper workerLooper;
        synchronized (_lifecycleLock) {
            if (_stopRequested) {
                return;
            }
            _stopRequested = true;
            if (_registrationLifecycle != null) {
                try {
                    _registrationLifecycle.stop();
                } catch (RuntimeException exception) {
                    failure = exception;
                }
            }
            workerLooper = _workerLooper;
        }

        if (workerLooper != null) {
            workerLooper.quitSafely();
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public boolean awaitStopped(long timeout, TimeUnit unit) throws InterruptedException {
        return _stopped.await(timeout, unit);
    }

    private void initializeRegistration(Handler workerHandler) {
        _deviceId = getUniqueId(_registrationContext);
        _criteria = new Criteria();
        _criteria.setAccuracy(Criteria.ACCURACY_FINE);
        _locationManager =
                (LocationManager) _registrationContext.getSystemService(Context.LOCATION_SERVICE);

        AndroidLocationPlatform platform =
                new AndroidLocationPlatform(workerHandler, workerHandler.getLooper());
        _registrationLifecycle = new GpsRegistrationLifecycle<>(
                platform,
                new HandlerRetryScheduler(workerHandler),
                this::reportRouteStatus);
    }

    public synchronized static String getUniqueId(Context context) {
        if (_deviceId == null) {
            SharedPreferences sharedPrefs = context.getSharedPreferences(
                    PREF_UNIQUE_ID,
                    Context.MODE_PRIVATE);
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
        return new LocationListener() {
            private Location _lastLocation = currentLocation;
            private double _totalDistance;

            @Override
            public void onLocationChanged(Location location) {
                if (location != null
                        && location.hasAccuracy()
                        && location.getAccuracy() <= _minAccuracyMeters) {
                    GregorianCalendar greg = new GregorianCalendar();
                    TimeZone tz = greg.getTimeZone();
                    int offset = tz.getOffset(System.currentTimeMillis());
                    greg.add(Calendar.SECOND, (offset / 1000) * -1);
                    Config.DotnetTimestampFormat.format(greg.getTime());
                    double distance =
                            _lastLocation == null ? 0.0 : calculateDistance(_lastLocation, location);
                    _totalDistance += distance;
                    LocationData data = new LocationData(location, _totalDistance, distance);
                    Message completeMessage = _handler.obtainMessage(0, 1, 1, data);
                    completeMessage.sendToTarget();
                    _lastLocation = location;

                    SharedData.getInstance().setLocation(data);
                }
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
                if (provider.equalsIgnoreCase(LocationManager.GPS_PROVIDER)
                        && (status == LocationProvider.OUT_OF_SERVICE
                        || status == LocationProvider.TEMPORARILY_UNAVAILABLE)) {
                    _isGpsLocked = false;
                }
            }

            @Override
            public void onProviderEnabled(String provider) {
                if (provider.equalsIgnoreCase(LocationManager.GPS_PROVIDER)) {
                    _isGpsLocked = true;
                }
                signalEnvironmentChanged();
            }

            @Override
            public void onProviderDisabled(String provider) {
                if (provider.equalsIgnoreCase(LocationManager.GPS_PROVIDER)) {
                    _isGpsLocked = false;
                }
                signalEnvironmentChanged();
            }
        };
    }

    private void signalEnvironmentChanged() {
        GpsRegistrationLifecycle<LocationListener, LocationEnvironment> registrationLifecycle;
        synchronized (_lifecycleLock) {
            registrationLifecycle = _registrationLifecycle;
        }
        if (registrationLifecycle != null) {
            registrationLifecycle.onEnvironmentChanged();
        }
    }

    private final class AndroidLocationPlatform
            implements GpsRegistrationLifecycle.Platform<LocationListener, LocationEnvironment> {
        private final Handler registrationHandler;
        private final Looper registrationLooper;
        private BroadcastReceiver registeredReceiver;

        private AndroidLocationPlatform(Handler registrationHandler, Looper registrationLooper) {
            this.registrationHandler = registrationHandler;
            this.registrationLooper = registrationLooper;
        }

        @Override
        public LocationEnvironment getEnvironmentKey() {
            boolean permissionGranted = hasLocationPermission();
            if (!permissionGranted || _locationManager == null) {
                return new LocationEnvironment(
                        permissionGranted,
                        false,
                        false,
                        null,
                        false,
                        "");
            }

            boolean hasAnyProvider = false;
            boolean locationEnabled = false;
            String provider = null;
            boolean providerEnabled = false;
            String providerSignature = "";
            try {
                List<String> providers = _locationManager.getAllProviders();
                hasAnyProvider = providers != null && !providers.isEmpty();
                providerSignature = providers == null
                        ? ""
                        : new TreeSet<>(providers).toString();
                locationEnabled = hasAnyProvider && isLocationEnabled();
                provider = locationEnabled ? getBestProvider() : null;
                providerEnabled = provider != null && isProviderEnabled(provider);
            } catch (SecurityException exception) {
                permissionGranted = false;
                hasAnyProvider = false;
                locationEnabled = false;
                provider = null;
                providerEnabled = false;
            } catch (IllegalArgumentException exception) {
                provider = null;
                providerEnabled = false;
            }

            return new LocationEnvironment(
                    permissionGranted,
                    hasAnyProvider,
                    locationEnabled,
                    provider,
                    providerEnabled,
                    providerSignature);
        }

        @Override
        public void registerProviderChanges(Runnable callback) {
            if (registeredReceiver != null) {
                throw new IllegalStateException("Provider receiver already registered");
            }

            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (LocationManager.MODE_CHANGED_ACTION.equals(action)
                            || LocationManager.PROVIDERS_CHANGED_ACTION.equals(action)) {
                        callback.run();
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(LocationManager.MODE_CHANGED_ACTION);
            filter.addAction(LocationManager.PROVIDERS_CHANGED_ACTION);
            ContextCompat.registerReceiver(
                    _registrationContext,
                    receiver,
                    filter,
                    null,
                    registrationHandler,
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            registeredReceiver = receiver;
        }

        @Override
        public void unregisterProviderChanges() {
            BroadcastReceiver receiver = registeredReceiver;
            if (receiver == null) {
                throw new IllegalStateException("Provider receiver is not registered");
            }
            registeredReceiver = null;
            _registrationContext.unregisterReceiver(receiver);
        }

        @Override
        public boolean hasLocationPermission() {
            return ContextCompat.checkSelfPermission(
                    _registrationContext,
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
                    listener,
                    registrationLooper);
        }

        @Override
        public void removeLocationUpdates(LocationListener listener) {
            _locationManager.removeUpdates(listener);
        }
    }

    private static final class HandlerRetryScheduler
            implements GpsRegistrationLifecycle.RetryScheduler {
        private final Handler handler;

        private HandlerRetryScheduler(Handler handler) {
            this.handler = handler;
        }

        @Override
        public void schedule(Runnable callback, long delayMillis) {
            if (!handler.postDelayed(callback, delayMillis)) {
                throw new IllegalStateException("Location retry handler is shutting down");
            }
        }

        @Override
        public void cancel(Runnable callback) {
            handler.removeCallbacks(callback);
        }
    }

    private static final class LocationEnvironment {
        private final boolean permissionGranted;
        private final boolean hasAnyProvider;
        private final boolean locationEnabled;
        private final String provider;
        private final boolean providerEnabled;
        private final String providerSignature;

        private LocationEnvironment(
                boolean permissionGranted,
                boolean hasAnyProvider,
                boolean locationEnabled,
                String provider,
                boolean providerEnabled,
                String providerSignature) {
            this.permissionGranted = permissionGranted;
            this.hasAnyProvider = hasAnyProvider;
            this.locationEnabled = locationEnabled;
            this.provider = provider;
            this.providerEnabled = providerEnabled;
            this.providerSignature = providerSignature;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof LocationEnvironment)) {
                return false;
            }
            LocationEnvironment that = (LocationEnvironment) other;
            return permissionGranted == that.permissionGranted
                    && hasAnyProvider == that.hasAnyProvider
                    && locationEnabled == that.locationEnabled
                    && providerEnabled == that.providerEnabled
                    && Objects.equals(provider, that.provider)
                    && Objects.equals(providerSignature, that.providerSignature);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    permissionGranted,
                    hasAnyProvider,
                    locationEnabled,
                    provider,
                    providerEnabled,
                    providerSignature);
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

    @SuppressWarnings("MissingPermission")
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

        _locationManager.registerGnssStatusCallback(_gnssStatusListener, _workerHandler);
    }

    @SuppressWarnings("MissingPermission")
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
                Log.d(
                        TAG,
                        "GnssMeasurementsEvent.Callback.onStatusChanged() - " + statusMessage);
            }
        };

        _locationManager.registerGnssMeasurementsCallback(
                _gnssMeasurementsListener,
                _workerHandler);
    }

    public double calculateDistance(Location start, Location end) {
        double distance = 0.0;
        double factor = 1000.0;

        double startLat = start.getLatitude();
        double startLon = start.getLongitude();
        double endLat = end.getLatitude();
        double endLon = end.getLongitude();

        if (startLat != 0.0 && startLon != 0.0 && endLat != 0.0 && endLon != 0.0) {
            double lat1 = Math.toRadians(startLat);
            double lon1 = Math.toRadians(startLon);
            double lat2 = Math.toRadians(endLat);
            double lon2 = Math.toRadians(endLon);

            double longitudeDistance = Math.toRadians(startLon - endLon);
            double angularDistance = Math.sin(lat1) * Math.sin(lat2)
                    + Math.cos(lat1) * Math.cos(lat2) * Math.cos(longitudeDistance);
            angularDistance = Math.acos(angularDistance);
            distance = angularDistance * 6372.795;
        }

        return distance / factor;
    }

    private boolean hasGps() {
        return _registrationContext
                .getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS);
    }
}
