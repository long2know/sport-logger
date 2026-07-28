package com.long2know.sportlogger.services;

import java.util.Objects;

final class LocationRegistration<L> {
    enum Status {
        STOPPED,
        AVAILABLE,
        NO_PROVIDER,
        LOCATION_DISABLED,
        PERMISSION_DENIED
    }

    interface Backend<L> {
        boolean hasLocationPermission();

        boolean hasAnyProvider();

        boolean isLocationEnabled();

        String getBestProvider();

        boolean isProviderEnabled(String provider);

        L createLocationListener(String provider);

        void requestLocationUpdates(String provider, L listener);

        void removeLocationUpdates(L listener);
    }

    interface StatusReporter {
        void onStatusChanged(Status status);
    }

    private final Backend<L> backend;
    private final StatusReporter statusReporter;

    private Status status = Status.STOPPED;
    private String registeredProvider;
    private L registeredListener;

    LocationRegistration(Backend<L> backend, StatusReporter statusReporter) {
        this.backend = Objects.requireNonNull(backend);
        this.statusReporter = Objects.requireNonNull(statusReporter);
    }

    synchronized Status start() {
        if (!backend.hasLocationPermission()) {
            clearRegistration();
            return transitionTo(Status.PERMISSION_DENIED);
        }

        if (registeredListener != null) {
            try {
                if (backend.isProviderEnabled(registeredProvider)) {
                    return transitionTo(Status.AVAILABLE);
                }
                return transitionTo(Status.LOCATION_DISABLED);
            } catch (SecurityException exception) {
                clearRegistration();
                return transitionTo(Status.PERMISSION_DENIED);
            } catch (IllegalArgumentException exception) {
                if (clearRegistration()) {
                    return transitionTo(Status.PERMISSION_DENIED);
                }
            }
        }

        final boolean hasAnyProvider;
        final boolean locationEnabled;
        final String provider;
        try {
            hasAnyProvider = backend.hasAnyProvider();
            locationEnabled = hasAnyProvider && backend.isLocationEnabled();
            provider = locationEnabled ? backend.getBestProvider() : null;
        } catch (SecurityException exception) {
            clearRegistration();
            return transitionTo(Status.PERMISSION_DENIED);
        }

        if (!hasAnyProvider) {
            return transitionTo(Status.NO_PROVIDER);
        }
        if (!locationEnabled) {
            return transitionTo(Status.LOCATION_DISABLED);
        }
        if (provider == null) {
            return transitionTo(Status.NO_PROVIDER);
        }

        try {
            if (!backend.isProviderEnabled(provider)) {
                return transitionTo(Status.LOCATION_DISABLED);
            }
        } catch (SecurityException exception) {
            return transitionTo(Status.PERMISSION_DENIED);
        } catch (IllegalArgumentException exception) {
            return transitionTo(Status.NO_PROVIDER);
        }

        final L listener;
        try {
            listener = Objects.requireNonNull(backend.createLocationListener(provider));
        } catch (SecurityException exception) {
            return transitionTo(Status.PERMISSION_DENIED);
        } catch (IllegalArgumentException exception) {
            return transitionTo(Status.NO_PROVIDER);
        }

        try {
            backend.requestLocationUpdates(provider, listener);
        } catch (SecurityException exception) {
            cleanUpCandidate(listener);
            return transitionTo(Status.PERMISSION_DENIED);
        } catch (IllegalArgumentException exception) {
            if (cleanUpCandidate(listener)) {
                return transitionTo(Status.PERMISSION_DENIED);
            }
            return transitionTo(Status.NO_PROVIDER);
        }

        registeredProvider = provider;
        registeredListener = listener;
        return transitionTo(Status.AVAILABLE);
    }

    synchronized void stop() {
        if (clearRegistration()) {
            transitionTo(Status.PERMISSION_DENIED);
        }
        transitionTo(Status.STOPPED);
    }

    synchronized void onProviderDisabled(String provider) {
        if (provider != null && provider.equals(registeredProvider)) {
            transitionTo(Status.LOCATION_DISABLED);
        }
    }

    synchronized Status onProviderEnabled() {
        return start();
    }

    synchronized Status getStatus() {
        return status;
    }

    synchronized boolean isRegistered() {
        return registeredListener != null;
    }

    private boolean clearRegistration() {
        L listener = registeredListener;
        registeredListener = null;
        registeredProvider = null;
        if (listener != null) {
            try {
                backend.removeLocationUpdates(listener);
            } catch (SecurityException ignored) {
                return true;
            }
        }
        return false;
    }

    private boolean cleanUpCandidate(L listener) {
        try {
            backend.removeLocationUpdates(listener);
        } catch (SecurityException ignored) {
            return true;
        }
        return false;
    }

    private Status transitionTo(Status nextStatus) {
        if (status != nextStatus) {
            status = nextStatus;
            statusReporter.onStatusChanged(nextStatus);
        }
        return status;
    }
}
