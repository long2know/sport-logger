package com.long2know.sportlogger.services;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OwnedListenerRegistryTest {
    @Test
    public void replacementStartsOnlyAfterPreviousOwnerStops() {
        OwnedListenerRegistry<FakeLifecycle> registry = new OwnedListenerRegistry<>();
        FakeLifecycle oldOwner = new FakeLifecycle();
        FakeLifecycle replacement = new FakeLifecycle();

        assertEquals(
                LifecycleTermination.TERMINATED,
                registry.replace(oldOwner, 50));
        assertEquals(
                LifecycleTermination.TERMINATED,
                registry.replace(replacement, 50));

        assertEquals(1, oldOwner.shutdownCalls);
        assertEquals(1, replacement.startCalls);
        assertTrue(registry.isOwner(replacement));
    }

    @Test
    public void timedOutOwnerPreventsReplacement() {
        OwnedListenerRegistry<FakeLifecycle> registry = new OwnedListenerRegistry<>();
        FakeLifecycle oldOwner = new FakeLifecycle();
        FakeLifecycle replacement = new FakeLifecycle();
        registry.replace(oldOwner, 50);
        oldOwner.shutdownResult = LifecycleTermination.TIMED_OUT;

        assertEquals(
                LifecycleTermination.TIMED_OUT,
                registry.replace(replacement, 50));
        assertEquals(0, replacement.startCalls);
        assertTrue(registry.isOwner(oldOwner));
    }

    @Test
    public void replacementStartupFailureRemainsOwnerUntilShutdown() {
        OwnedListenerRegistry<FakeLifecycle> registry = new OwnedListenerRegistry<>();
        FakeLifecycle replacement = new FakeLifecycle();
        replacement.startResult = LifecycleTermination.TIMED_OUT;

        assertEquals(
                LifecycleTermination.TIMED_OUT,
                registry.replace(replacement, 50));
        assertTrue(registry.isOwner(replacement));
    }

    @Test
    public void staleReleaseCannotClearOrStopReplacement() {
        OwnedListenerRegistry<FakeLifecycle> registry = new OwnedListenerRegistry<>();
        FakeLifecycle oldOwner = new FakeLifecycle();
        FakeLifecycle replacement = new FakeLifecycle();
        registry.replace(oldOwner, 50);
        registry.replace(replacement, 50);

        assertEquals(
                LifecycleTermination.TERMINATED,
                registry.release(oldOwner, 50));

        assertEquals(1, oldOwner.shutdownCalls);
        assertEquals(0, replacement.shutdownCalls);
        assertTrue(registry.isOwner(replacement));
        assertFalse(registry.isOwner(oldOwner));
    }

    @Test
    public void staleOperationCannotStopOrReplaceTheCurrentListenerOwner() {
        OwnedListenerRegistry<FakeLifecycle> registry = new OwnedListenerRegistry<>();
        FakeLifecycle currentOwner = new FakeLifecycle();
        FakeLifecycle staleReplacement = new FakeLifecycle();
        registry.replace(currentOwner, 50);

        assertEquals(
                LifecycleTermination.FAILED,
                registry.replace(staleReplacement, () -> false, 50));

        assertEquals(0, currentOwner.shutdownCalls);
        assertEquals(0, staleReplacement.startCalls);
        assertTrue(registry.isOwner(currentOwner));
    }

    private static final class FakeLifecycle implements BoundedLifecycle {
        LifecycleTermination shutdownResult = LifecycleTermination.TERMINATED;
        LifecycleTermination startResult = LifecycleTermination.TERMINATED;
        int startCalls;
        int shutdownCalls;

        @Override
        public LifecycleTermination start(long timeoutMillis) {
            startCalls++;
            return startResult;
        }

        @Override
        public LifecycleTermination shutdown(long timeoutMillis) {
            shutdownCalls++;
            return shutdownResult;
        }
    }
}
