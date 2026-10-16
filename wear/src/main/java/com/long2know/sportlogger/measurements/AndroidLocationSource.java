package com.long2know.sportlogger.measurements;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.HandlerThread;

import androidx.core.content.ContextCompat;

import com.long2know.utilities.models.LocationData;
import com.long2know.utilities.models.SharedData;

public final class AndroidLocationSource implements ResourceOwner.Source {
    private static final long MIN_TIME_MILLIS = 750L;
    private static final float MIN_DISTANCE_METERS = 0.1F;
    private static final float MAX_ACCURACY_METERS = 75F;

    private final Context context;
    private final LocationManager locationManager;

    public AndroidLocationSource(Context context) {
        this.context = context.getApplicationContext();
        this.locationManager =
                (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
    }

    @Override
    public void open(ResourceOwner.Ownership ownership, ResourceOwner.EventSink sink)
            throws ResourceException {
        if (locationManager == null) {
            throw unavailable("Location service is unavailable", false);
        }

        boolean fine = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) {
            throw new ResourceException(
                    new ResourceFailure(
                            "location",
                            ResourceFailure.Code.PERMISSION_DENIED,
                            "Location permission is not granted"),
                    true);
        }

        String provider = chooseProvider(fine);
        if (provider == null) {
            throw unavailable("No permitted location provider is enabled", true);
        }

        HandlerThread callbackThread = new HandlerThread("sport-logger-location");
        callbackThread.start();
        ownership.own("location-callback-thread", callbackThread::quitSafely);

        LocationListener listener = new LocationListener() {
            private Location lastLocation;
            private double totalDistance =
                    SharedData.getInstance().getData().TotalDistance;

            @Override
            public void onLocationChanged(Location location) {
                if (location == null
                        || (location.hasAccuracy() && location.getAccuracy() > MAX_ACCURACY_METERS)) {
                    return;
                }
                double distance = lastLocation == null ? 0D : lastLocation.distanceTo(location);
                totalDistance += Math.max(0D, distance);
                lastLocation = new Location(location);
                sink.onEvent(MeasurementEvent.location(
                        location.getElapsedRealtimeNanos(),
                        new LocationData(location, distance, totalDistance)));
            }

            @Override
            public void onProviderDisabled(String disabledProvider) {
                if (provider.equals(disabledProvider)) {
                    sink.onInvalidated(unavailable("Location provider was disabled", true));
                }
            }

            @Override
            public void onProviderEnabled(String enabledProvider) {
            }

            @Override
            public void onStatusChanged(String changedProvider, int status, Bundle extras) {
            }
        };

        try {
            locationManager.requestLocationUpdates(
                    provider,
                    MIN_TIME_MILLIS,
                    MIN_DISTANCE_METERS,
                    listener,
                    callbackThread.getLooper());
            ownership.own("location-updates", () -> locationManager.removeUpdates(listener));
        } catch (SecurityException error) {
            throw new ResourceException(
                    new ResourceFailure(
                            "location",
                            ResourceFailure.Code.PERMISSION_DENIED,
                            message(error)),
                    true);
        } catch (RuntimeException error) {
            throw new ResourceException(
                    new ResourceFailure(
                            "location",
                            ResourceFailure.Code.REGISTRATION_FAILED,
                            message(error)),
                    true);
        }
    }

    private String chooseProvider(boolean fine) {
        try {
            if (fine && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                return LocationManager.GPS_PROVIDER;
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                return LocationManager.NETWORK_PROVIDER;
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private static ResourceException unavailable(String message, boolean retryable) {
        return new ResourceException(
                new ResourceFailure(
                        "location",
                        ResourceFailure.Code.UNAVAILABLE,
                        message),
                retryable);
    }

    private static String message(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }
}
