package com.long2know.sportlogger.services;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class LocationRegistrationTest {
    @Test
    public void noProvidersDoesNotAttemptRegistration() {
        FakeBackend backend = new FakeBackend();
        backend.hasAnyProvider = false;
        LocationRegistration<TestLocationListener> registration = registrationFor(backend);

        assertEquals(LocationRegistration.Status.NO_PROVIDER, registration.start());
        assertEquals(0, backend.bestProviderCalls);
        assertEquals(0, backend.requestCalls);
        assertFalse(registration.isRegistered());
    }

    @Test
    public void nullBestProviderDoesNotAttemptRegistration() {
        FakeBackend backend = new FakeBackend();
        backend.bestProvider = null;
        LocationRegistration<TestLocationListener> registration = registrationFor(backend);

        assertEquals(LocationRegistration.Status.NO_PROVIDER, registration.start());
        assertEquals(1, backend.bestProviderCalls);
        assertEquals(0, backend.requestCalls);
        assertFalse(registration.isRegistered());
    }

    @Test
    public void disabledProviderDoesNotAttemptRegistration() {
        FakeBackend backend = new FakeBackend();
        backend.providerEnabled = false;
        LocationRegistration<TestLocationListener> registration = registrationFor(backend);

        assertEquals(LocationRegistration.Status.LOCATION_DISABLED, registration.start());
        assertEquals(0, backend.requestCalls);
        assertFalse(registration.isRegistered());
    }

    @Test
    public void providerDisappearingDuringRegistrationCleansPartialListener() {
        FakeBackend backend = new FakeBackend();
        backend.registerBeforeRequestFailure = true;
        backend.requestFailure = new IllegalArgumentException("provider disappeared");
        LocationRegistration<TestLocationListener> registration = registrationFor(backend);

        assertEquals(LocationRegistration.Status.NO_PROVIDER, registration.start());
        assertEquals(1, backend.requestCalls);
        assertEquals(1, backend.removeCalls);
        assertTrue(backend.activeListeners.isEmpty());
        assertFalse(registration.isRegistered());
    }

    @Test
    public void permissionRevokedDuringRegistrationCleansPartialListener() {
        FakeBackend backend = new FakeBackend();
        backend.registerBeforeRequestFailure = true;
        backend.requestFailure = new SecurityException("permission revoked");
        LocationRegistration<TestLocationListener> registration = registrationFor(backend);

        assertEquals(LocationRegistration.Status.PERMISSION_DENIED, registration.start());
        assertEquals(1, backend.requestCalls);
        assertEquals(1, backend.removeCalls);
        assertTrue(backend.activeListeners.isEmpty());
        assertFalse(registration.isRegistered());
    }

    @Test
    public void successfulRegistrationUsesOneEnabledNonNullProvider() {
        FakeBackend backend = new FakeBackend();
        LocationRegistration<TestLocationListener> registration = registrationFor(backend);

        assertEquals(LocationRegistration.Status.AVAILABLE, registration.start());
        assertEquals(1, backend.requestCalls);
        assertEquals("gps", backend.requestedProvider);
        assertEquals(1, backend.activeListeners.size());
        assertTrue(registration.isRegistered());
    }

    @Test
    public void repeatedStartAndStopAreIdempotentAndRestartable() {
        FakeBackend backend = new FakeBackend();
        LocationRegistration<TestLocationListener> registration = registrationFor(backend);

        registration.start();
        registration.start();
        assertEquals(1, backend.requestCalls);
        assertEquals(1, backend.activeListeners.size());

        registration.stop();
        registration.stop();
        assertEquals(1, backend.removeCalls);
        assertTrue(backend.activeListeners.isEmpty());
        assertEquals(LocationRegistration.Status.STOPPED, registration.getStatus());

        registration.start();
        assertEquals(2, backend.requestCalls);
        assertEquals(1, backend.activeListeners.size());
    }

    @Test
    public void reEnableAndRetryRecoverWithoutDuplicateRegistration() {
        FakeBackend backend = new FakeBackend();
        backend.locationEnabled = false;
        LocationRegistration<TestLocationListener> registration = registrationFor(backend);

        assertEquals(LocationRegistration.Status.LOCATION_DISABLED, registration.start());
        assertEquals(0, backend.requestCalls);

        backend.locationEnabled = true;
        assertEquals(LocationRegistration.Status.AVAILABLE, registration.start());
        assertEquals(1, backend.requestCalls);

        backend.providerEnabled = false;
        registration.onProviderDisabled("gps");
        assertEquals(LocationRegistration.Status.LOCATION_DISABLED, registration.getStatus());

        backend.providerEnabled = true;
        assertEquals(LocationRegistration.Status.AVAILABLE, registration.onProviderEnabled());
        assertEquals(1, backend.requestCalls);
        assertEquals(1, backend.activeListeners.size());
    }

    @Test
    public void repeatedStartDoesNotDuplicateLocationCallbacks() {
        FakeBackend backend = new FakeBackend();
        LocationRegistration<TestLocationListener> registration = registrationFor(backend);

        registration.start();
        registration.start();
        backend.emitLocation();

        assertEquals(1, backend.requestCalls);
        assertEquals(1, backend.callbackCount);
    }

    @Test
    public void unexpectedRegistrationFailureIsNotHidden() {
        FakeBackend backend = new FakeBackend();
        backend.requestFailure = new IllegalStateException("unexpected failure");
        LocationRegistration<TestLocationListener> registration = registrationFor(backend);

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, registration::start);

        assertEquals("unexpected failure", exception.getMessage());
        assertEquals(0, backend.removeCalls);
        assertFalse(registration.isRegistered());
    }

    private static LocationRegistration<TestLocationListener> registrationFor(
            FakeBackend backend) {
        return new LocationRegistration<>(backend, status -> backend.reportedStatuses.add(status));
    }

    private static final class TestLocationListener {
        private final Runnable callback;

        TestLocationListener(Runnable callback) {
            this.callback = callback;
        }

        void emitLocation() {
            callback.run();
        }
    }

    private static final class FakeBackend
            implements LocationRegistration.Backend<TestLocationListener> {
        boolean permissionGranted = true;
        boolean hasAnyProvider = true;
        boolean locationEnabled = true;
        boolean providerEnabled = true;
        String bestProvider = "gps";
        RuntimeException requestFailure;
        boolean registerBeforeRequestFailure;

        int bestProviderCalls;
        int requestCalls;
        int removeCalls;
        int callbackCount;
        String requestedProvider;
        final List<TestLocationListener> activeListeners = new ArrayList<>();
        final List<LocationRegistration.Status> reportedStatuses = new ArrayList<>();

        @Override
        public boolean hasLocationPermission() {
            return permissionGranted;
        }

        @Override
        public boolean hasAnyProvider() {
            return hasAnyProvider;
        }

        @Override
        public boolean isLocationEnabled() {
            return locationEnabled;
        }

        @Override
        public String getBestProvider() {
            bestProviderCalls++;
            return bestProvider;
        }

        @Override
        public boolean isProviderEnabled(String provider) {
            return providerEnabled;
        }

        @Override
        public TestLocationListener createLocationListener(String provider) {
            return new TestLocationListener(() -> callbackCount++);
        }

        @Override
        public void requestLocationUpdates(String provider, TestLocationListener listener) {
            requestCalls++;
            requestedProvider = provider;
            if (registerBeforeRequestFailure) {
                activeListeners.add(listener);
            }
            if (requestFailure != null) {
                throw requestFailure;
            }
            if (!activeListeners.contains(listener)) {
                activeListeners.add(listener);
            }
        }

        @Override
        public void removeLocationUpdates(TestLocationListener listener) {
            removeCalls++;
            activeListeners.remove(listener);
        }

        void emitLocation() {
            for (TestLocationListener listener : new ArrayList<>(activeListeners)) {
                listener.emitLocation();
            }
        }
    }
}
