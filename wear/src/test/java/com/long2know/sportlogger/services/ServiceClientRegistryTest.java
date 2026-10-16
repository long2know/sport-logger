package com.long2know.sportlogger.services;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ServiceClientRegistryTest {
    @Test
    public void activityRecreationReplacesClientWithoutDuplicateCallbacks() {
        ServiceClientRegistry<Runnable> registry = new ServiceClientRegistry<>();
        AtomicInteger oldActivityCalls = new AtomicInteger();
        AtomicInteger newActivityCalls = new AtomicInteger();
        Runnable oldActivity = oldActivityCalls::incrementAndGet;
        Runnable newActivity = newActivityCalls::incrementAndGet;

        registry.register(oldActivity);
        registry.register(oldActivity);
        registry.dispatch(Runnable::run);

        registry.unregister(oldActivity);
        registry.register(newActivity);
        registry.dispatch(Runnable::run);

        assertEquals(1, oldActivityCalls.get());
        assertEquals(1, newActivityCalls.get());
        registry.unregister(newActivity);
        assertTrue(registry.isEmpty());
    }
}
