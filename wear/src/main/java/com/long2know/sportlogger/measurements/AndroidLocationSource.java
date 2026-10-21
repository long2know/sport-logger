package com.long2know.sportlogger.measurements;

import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import com.long2know.sportlogger.acquisition.RecordingPermissionPolicy;
import com.long2know.sportlogger.acquisition.RouteStatus;
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
            emitStatus(sink, RouteStatus.UNAVAILABLE);
            throw unavailable("Location service is unavailable", false);
        }

        RecordingPermissionPolicy.RoutePermission routePermission =
                RecordingPermissionPolicy.routePermission(this::isGranted);
        if (routePermission == RecordingPermissionPolicy.RoutePermission.DENIED) {
            emitStatus(sink, RouteStatus.PERMISSION_DENIED);
            throw new ResourceException(
                    new ResourceFailure(
                            "location",
                            ResourceFailure.Code.PERMISSION_DENIED,
                            "Location permission is not granted"),
                    false);
        }
        if (routePermission == RecordingPermissionPolicy.RoutePermission.APPROXIMATE_ONLY) {
            emitStatus(sink, RouteStatus.APPROXIMATE_ONLY);
            throw new ResourceException(
                    new ResourceFailure(
                            "location",
                            ResourceFailure.Code.PERMISSION_DENIED,
                            "Precise location is required for route recording"),
                    false);
        }
        if (!isLocationEnabled()) {
            emitStatus(sink, RouteStatus.LOCATION_DISABLED);
            throw unavailable("Location is disabled", true);
        }
        if (!hasGpsProvider()) {
            emitStatus(sink, RouteStatus.NO_GNSS);
            throw unavailable("This watch has no GNSS provider", false);
        }
        if (!isGpsProviderEnabled()) {
            emitStatus(sink, RouteStatus.NO_COMPATIBLE_PROVIDER);
            throw unavailable("The GNSS provider is unavailable", true);
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
                sink.onEvent(MeasurementEvent.routeStatus(
                        location.getElapsedRealtimeNanos(),
                        RouteStatus.AVAILABLE));
                double distance = lastLocation == null ? 0D : lastLocation.distanceTo(location);
                totalDistance += Math.max(0D, distance);
                lastLocation = new Location(location);
                sink.onEvent(MeasurementEvent.location(
                        location.getElapsedRealtimeNanos(),
                        new LocationData(location, distance, totalDistance)));
            }

            @Override
            public void onProviderDisabled(String disabledProvider) {
                if (LocationManager.GPS_PROVIDER.equals(disabledProvider)) {
                    RouteStatus status = isLocationEnabled()
                            ? RouteStatus.NO_COMPATIBLE_PROVIDER
                            : RouteStatus.LOCATION_DISABLED;
                    emitStatus(sink, status);
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
                    LocationManager.GPS_PROVIDER,
                    MIN_TIME_MILLIS,
                    MIN_DISTANCE_METERS,
                    listener,
                    callbackThread.getLooper());
            ownership.own("location-updates", () -> locationManager.removeUpdates(listener));
            emitStatus(sink, RouteStatus.ACQUIRING);
        } catch (SecurityException error) {
            emitStatus(sink, RouteStatus.PERMISSION_DENIED);
            throw new ResourceException(
                    new ResourceFailure(
                            "location",
                            ResourceFailure.Code.PERMISSION_DENIED,
                            message(error)),
                    false);
        } catch (RuntimeException error) {
            emitStatus(sink, RouteStatus.UNAVAILABLE);
            throw new ResourceException(
                    new ResourceFailure(
                            "location",
                            ResourceFailure.Code.REGISTRATION_FAILED,
                            message(error)),
                    true);
        }
    }

    private boolean isGranted(String permission) {
        return ContextCompat.checkSelfPermission(context, permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isLocationEnabled() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return locationManager.isLocationEnabled();
            }
            return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private boolean hasGpsProvider() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return locationManager.hasProvider(LocationManager.GPS_PROVIDER);
            }
            return locationManager.getAllProviders().contains(LocationManager.GPS_PROVIDER);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private boolean isGpsProviderEnabled() {
        try {
            return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static void emitStatus(ResourceOwner.EventSink sink, RouteStatus status) {
        sink.onEvent(MeasurementEvent.routeStatus(
                SystemClock.elapsedRealtimeNanos(),
                status));
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
