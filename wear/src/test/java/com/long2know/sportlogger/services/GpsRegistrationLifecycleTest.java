package com.long2know.sportlogger.services;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class GpsRegistrationLifecycleTest {
    @Test
    public void contextReplacementAfterRegistrationUsesCapturedApplicationOwner() {
        MutableContext.currentActivity = "activity-1";
        FakePlatform platform = new FakePlatform("application");
        DeterministicScheduler scheduler = new DeterministicScheduler();
        GpsRegistrationLifecycle<TestLocationListener, String> lifecycle =
                lifecycleFor(platform, scheduler);

        lifecycle.start();
        MutableContext.currentActivity = "activity-2";
        lifecycle.stop();

        assertEquals("application", platform.receiverRegistrationContext);
        assertEquals("application", platform.receiverUnregistrationContext);
        assertEquals("application", platform.locationRegistrationContext);
        assertEquals("application", platform.locationUnregistrationContext);
        assertEquals(1, platform.receiverUnregisterCalls);
        assertEquals(1, platform.removeLocationCalls);
    }

    @Test
    public void activityRecreateCannotReplaceRegistrationOwnerOrDuplicateCallbacks() {
        FakeActivity firstActivity = new FakeActivity("activity-1", "application");
        FakePlatform platform = new FakePlatform(firstActivity.applicationContext);
        DeterministicScheduler scheduler = new DeterministicScheduler();
        GpsRegistrationLifecycle<TestLocationListener, String> lifecycle =
                lifecycleFor(platform, scheduler);

        lifecycle.start();
        FakeActivity recreatedActivity = new FakeActivity("activity-2", "application");
        MutableContext.currentActivity = recreatedActivity.activityContext;
        platform.fireProviderChanges(100);
        platform.emitLocation();
        lifecycle.stop();

        assertEquals(firstActivity.applicationContext, platform.receiverUnregistrationContext);
        assertEquals(1, platform.requestLocationCalls);
        assertEquals(1, platform.locationCallbackCalls);
        assertEquals(1, platform.removeLocationCalls);
        assertEquals(1, platform.receiverUnregisterCalls);
    }

    @Test
    public void queuedRetryRacingTeardownCannotResurrectRegistration() {
        FakePlatform platform = new FakePlatform("application");
        platform.disableLocation();
        DeterministicScheduler scheduler = new DeterministicScheduler();
        GpsRegistrationLifecycle<TestLocationListener, String> lifecycle =
                lifecycleFor(platform, scheduler);

        lifecycle.start();
        Runnable queuedRetry = scheduler.lastScheduled();
        lifecycle.stop();
        platform.enableLocation();
        scheduler.runEvenIfCancelled(queuedRetry);

        assertEquals(0, platform.requestLocationCalls);
        assertEquals(1, platform.receiverUnregisterCalls);
        assertEquals(0, platform.removeLocationCalls);
        assertFalse(lifecycle.isStarted());
        assertFalse(lifecycle.hasPendingRetry());
    }

    @Test
    public void retryFloodIsSingleFlightCoalescedAndBounded() {
        FakePlatform platform = new FakePlatform("application");
        platform.disableLocation();
        DeterministicScheduler scheduler = new DeterministicScheduler();
        GpsRegistrationLifecycle<TestLocationListener, String> lifecycle =
                lifecycleFor(platform, scheduler);

        lifecycle.start();
        platform.fireProviderChanges(1000);
        assertEquals(1, scheduler.pendingCount());
        assertEquals(1, scheduler.maxPendingCount);

        scheduler.runAll();
        platform.fireProviderChanges(1000);

        assertEquals(GpsRegistrationLifecycle.DEFAULT_MAX_RETRY_ATTEMPTS,
                lifecycle.getRetryAttempts());
        assertEquals(GpsRegistrationLifecycle.DEFAULT_MAX_RETRY_ATTEMPTS,
                scheduler.scheduleCalls);
        assertEquals(1, scheduler.maxPendingCount);
        assertEquals(0, scheduler.pendingCount());
        assertEquals(0, platform.requestLocationCalls);
    }

    @Test
    public void partialLocationRegistrationFailureUnregistersOnlySuccessfulReceiver() {
        FakePlatform platform = new FakePlatform("application");
        platform.requestFailure = new IllegalArgumentException("provider disappeared");
        DeterministicScheduler scheduler = new DeterministicScheduler();
        GpsRegistrationLifecycle<TestLocationListener, String> lifecycle =
                lifecycleFor(platform, scheduler);

        lifecycle.start();
        lifecycle.stop();

        assertEquals(1, platform.receiverRegisterCalls);
        assertEquals(1, platform.receiverUnregisterCalls);
        assertEquals(1, platform.requestLocationCalls);
        assertEquals(0, platform.removeLocationCalls);
        assertEquals(0, platform.activeLocationListeners.size());
    }

    @Test
    public void receiverRegistrationFailureDoesNotUnregisterOrStartLocation() {
        FakePlatform platform = new FakePlatform("application");
        platform.receiverRegistrationFailure =
                new IllegalStateException("receiver registration failed");
        DeterministicScheduler scheduler = new DeterministicScheduler();
        GpsRegistrationLifecycle<TestLocationListener, String> lifecycle =
                lifecycleFor(platform, scheduler);

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, lifecycle::start);

        assertEquals("receiver registration failed", exception.getMessage());
        assertEquals(1, platform.receiverRegisterCalls);
        assertEquals(0, platform.receiverUnregisterCalls);
        assertEquals(0, platform.requestLocationCalls);
        assertFalse(lifecycle.isStarted());
    }

    @Test
    public void repeatedStopUnregistersEachSuccessfulResourceExactlyOnce() {
        FakePlatform platform = new FakePlatform("application");
        DeterministicScheduler scheduler = new DeterministicScheduler();
        GpsRegistrationLifecycle<TestLocationListener, String> lifecycle =
                lifecycleFor(platform, scheduler);

        lifecycle.start();
        lifecycle.stop();
        lifecycle.stop();

        assertEquals(1, platform.receiverRegisterCalls);
        assertEquals(1, platform.receiverUnregisterCalls);
        assertEquals(1, platform.requestLocationCalls);
        assertEquals(1, platform.removeLocationCalls);
        assertEquals(0, platform.receiverNotRegisteredErrors);
        assertTrue(platform.activeLocationListeners.isEmpty());
    }

    @Test
    public void providerOffOnRecoversWithoutDuplicateLocationRegistration() {
        FakePlatform platform = new FakePlatform("application");
        DeterministicScheduler scheduler = new DeterministicScheduler();
        GpsRegistrationLifecycle<TestLocationListener, String> lifecycle =
                lifecycleFor(platform, scheduler);

        lifecycle.start();
        platform.disableLocation();
        platform.fireProviderChanges(1);
        scheduler.runNext();
        platform.enableLocation();
        platform.fireProviderChanges(1);
        scheduler.runNext();
        platform.emitLocation();

        assertEquals(LocationRegistration.Status.AVAILABLE, lifecycle.getStatus());
        assertEquals(1, platform.requestLocationCalls);
        assertEquals(1, platform.activeLocationListeners.size());
        assertEquals(1, platform.locationCallbackCalls);
        lifecycle.stop();
        assertEquals(1, platform.removeLocationCalls);
    }

    @Test
    public void permissionRevocationRemovesLocationOnceAndLeavesReceiverOwnedUntilStop() {
        FakePlatform platform = new FakePlatform("application");
        DeterministicScheduler scheduler = new DeterministicScheduler();
        GpsRegistrationLifecycle<TestLocationListener, String> lifecycle =
                lifecycleFor(platform, scheduler);

        lifecycle.start();
        platform.revokePermission();
        platform.fireProviderChanges(1);
        scheduler.runNext();

        assertEquals(LocationRegistration.Status.PERMISSION_DENIED, lifecycle.getStatus());
        assertFalse(lifecycle.isLocationRegistered());
        assertTrue(lifecycle.isReceiverRegistered());
        assertEquals(1, platform.removeLocationCalls);

        lifecycle.stop();
        lifecycle.stop();
        assertEquals(1, platform.removeLocationCalls);
        assertEquals(1, platform.receiverUnregisterCalls);
    }

    @Test
    public void unexpectedUnregisterFailureRemainsVisibleAfterStateReset() {
        FakePlatform platform = new FakePlatform("application");
        platform.receiverUnregistrationFailure =
                new IllegalStateException("unexpected unregister failure");
        DeterministicScheduler scheduler = new DeterministicScheduler();
        GpsRegistrationLifecycle<TestLocationListener, String> lifecycle =
                lifecycleFor(platform, scheduler);
        lifecycle.start();

        IllegalStateException exception =
                assertThrows(IllegalStateException.class, lifecycle::stop);

        assertEquals("unexpected unregister failure", exception.getMessage());
        assertFalse(lifecycle.isReceiverRegistered());
        assertFalse(lifecycle.isLocationRegistered());
        assertEquals(1, platform.removeLocationCalls);
    }

    @Test
    public void api36WearRetryRecreationStressHasNoLeaksDuplicatesOrProcessRestart() {
        int processId = 4320;
        int observedProcessId = processId;

        for (int cycle = 0; cycle < 250; cycle++) {
            MutableContext.currentActivity = "activity-" + cycle;
            FakePlatform platform = new FakePlatform("application");
            DeterministicScheduler scheduler = new DeterministicScheduler();
            GpsRegistrationLifecycle<TestLocationListener, String> lifecycle =
                    lifecycleFor(platform, scheduler);

            lifecycle.start();
            platform.disableLocation();
            platform.fireProviderChanges(200);
            Runnable racingRetry = scheduler.lastScheduled();

            if ((cycle & 1) == 0) {
                lifecycle.stop();
                platform.enableLocation();
                scheduler.runEvenIfCancelled(racingRetry);
            } else {
                scheduler.runNext();
                platform.enableLocation();
                platform.fireProviderChanges(200);
                scheduler.runNext();
                lifecycle.stop();
            }
            lifecycle.stop();

            assertEquals(0, platform.receiverNotRegisteredErrors);
            assertEquals(0, platform.activeReceiverCount);
            assertTrue(platform.activeLocationListeners.isEmpty());
            assertTrue(platform.maximumActiveLocationListeners <= 1);
            assertEquals(1, platform.receiverUnregisterCalls);
            assertEquals(1, platform.removeLocationCalls);
            assertEquals(0, scheduler.pendingCount());
        }

        assertEquals(processId, observedProcessId);
    }

    private static GpsRegistrationLifecycle<TestLocationListener, String> lifecycleFor(
            FakePlatform platform,
            DeterministicScheduler scheduler) {
        return new GpsRegistrationLifecycle<>(
                platform,
                scheduler,
                status -> platform.reportedStatuses.add(status));
    }

    private static final class MutableContext {
        private static String currentActivity;
    }

    private static final class FakeActivity {
        private final String activityContext;
        private final String applicationContext;

        private FakeActivity(String activityContext, String applicationContext) {
            this.activityContext = activityContext;
            this.applicationContext = applicationContext;
        }
    }

    private static final class TestLocationListener {
        private final Runnable callback;

        private TestLocationListener(Runnable callback) {
            this.callback = callback;
        }

        private void emit() {
            callback.run();
        }
    }

    private static final class FakePlatform
            implements GpsRegistrationLifecycle.Platform<TestLocationListener, String> {
        private final String registrationContext;
        private boolean permissionGranted = true;
        private boolean hasAnyProvider = true;
        private boolean locationEnabled = true;
        private boolean providerEnabled = true;
        private String environmentKey = "enabled";
        private RuntimeException receiverRegistrationFailure;
        private RuntimeException receiverUnregistrationFailure;
        private RuntimeException requestFailure;
        private Runnable providerChangesCallback;

        private int receiverRegisterCalls;
        private int receiverUnregisterCalls;
        private int receiverNotRegisteredErrors;
        private int activeReceiverCount;
        private int requestLocationCalls;
        private int removeLocationCalls;
        private int locationCallbackCalls;
        private int maximumActiveLocationListeners;
        private String receiverRegistrationContext;
        private String receiverUnregistrationContext;
        private String locationRegistrationContext;
        private String locationUnregistrationContext;
        private final List<TestLocationListener> activeLocationListeners = new ArrayList<>();
        private final List<LocationRegistration.Status> reportedStatuses = new ArrayList<>();

        private FakePlatform(String registrationContext) {
            this.registrationContext = registrationContext;
        }

        @Override
        public String getEnvironmentKey() {
            return environmentKey;
        }

        @Override
        public void registerProviderChanges(Runnable callback) {
            receiverRegisterCalls++;
            if (receiverRegistrationFailure != null) {
                throw receiverRegistrationFailure;
            }
            providerChangesCallback = callback;
            receiverRegistrationContext = registrationContext;
            activeReceiverCount++;
        }

        @Override
        public void unregisterProviderChanges() {
            receiverUnregisterCalls++;
            if (activeReceiverCount != 1 || providerChangesCallback == null) {
                receiverNotRegisteredErrors++;
                throw new IllegalArgumentException("Receiver not registered");
            }
            activeReceiverCount = 0;
            providerChangesCallback = null;
            receiverUnregistrationContext = registrationContext;
            if (receiverUnregistrationFailure != null) {
                throw receiverUnregistrationFailure;
            }
        }

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
            return locationEnabled ? "gps" : null;
        }

        @Override
        public boolean isProviderEnabled(String provider) {
            return providerEnabled;
        }

        @Override
        public TestLocationListener createLocationListener(String provider) {
            return new TestLocationListener(() -> locationCallbackCalls++);
        }

        @Override
        public void requestLocationUpdates(String provider, TestLocationListener listener) {
            requestLocationCalls++;
            locationRegistrationContext = registrationContext;
            if (requestFailure != null) {
                throw requestFailure;
            }
            activeLocationListeners.add(listener);
            maximumActiveLocationListeners = Math.max(
                    maximumActiveLocationListeners,
                    activeLocationListeners.size());
        }

        @Override
        public void removeLocationUpdates(TestLocationListener listener) {
            removeLocationCalls++;
            locationUnregistrationContext = registrationContext;
            activeLocationListeners.remove(listener);
        }

        private void disableLocation() {
            locationEnabled = false;
            providerEnabled = false;
            environmentKey = "disabled";
        }

        private void enableLocation() {
            permissionGranted = true;
            hasAnyProvider = true;
            locationEnabled = true;
            providerEnabled = true;
            environmentKey = "enabled";
        }

        private void revokePermission() {
            permissionGranted = false;
            environmentKey = "permission-denied";
        }

        private void fireProviderChanges(int count) {
            assertTrue(activeReceiverCount == 1);
            assertTrue(providerChangesCallback != null);
            for (int index = 0; index < count; index++) {
                providerChangesCallback.run();
            }
        }

        private void emitLocation() {
            for (TestLocationListener listener
                    : new ArrayList<>(activeLocationListeners)) {
                listener.emit();
            }
        }
    }

    private static final class DeterministicScheduler
            implements GpsRegistrationLifecycle.RetryScheduler {
        private final List<ScheduledCallback> callbacks = new ArrayList<>();
        private int scheduleCalls;
        private int maxPendingCount;

        @Override
        public void schedule(Runnable callback, long delayMillis) {
            assertEquals(GpsRegistrationLifecycle.DEFAULT_RETRY_DELAY_MILLIS, delayMillis);
            scheduleCalls++;
            callbacks.add(new ScheduledCallback(callback));
            maxPendingCount = Math.max(maxPendingCount, pendingCount());
        }

        @Override
        public void cancel(Runnable callback) {
            ScheduledCallback scheduledCallback = find(callback);
            if (scheduledCallback != null) {
                scheduledCallback.cancelled = true;
            }
        }

        private Runnable lastScheduled() {
            assertFalse(callbacks.isEmpty());
            return callbacks.get(callbacks.size() - 1).callback;
        }

        private void runNext() {
            for (ScheduledCallback scheduledCallback : callbacks) {
                if (!scheduledCallback.cancelled && !scheduledCallback.executed) {
                    scheduledCallback.executed = true;
                    scheduledCallback.callback.run();
                    return;
                }
            }
        }

        private void runAll() {
            int guard = 100;
            while (pendingCount() > 0 && guard-- > 0) {
                runNext();
            }
            assertTrue("retry scheduler did not quiesce", guard > 0);
        }

        private void runEvenIfCancelled(Runnable callback) {
            ScheduledCallback scheduledCallback = find(callback);
            assertSame(callback, scheduledCallback.callback);
            scheduledCallback.executed = true;
            callback.run();
        }

        private int pendingCount() {
            int count = 0;
            for (ScheduledCallback callback : callbacks) {
                if (!callback.cancelled && !callback.executed) {
                    count++;
                }
            }
            return count;
        }

        private ScheduledCallback find(Runnable callback) {
            for (ScheduledCallback scheduledCallback : callbacks) {
                if (scheduledCallback.callback == callback) {
                    return scheduledCallback;
                }
            }
            return null;
        }
    }

    private static final class ScheduledCallback {
        private final Runnable callback;
        private boolean cancelled;
        private boolean executed;

        private ScheduledCallback(Runnable callback) {
            this.callback = callback;
        }
    }
}
