package com.long2know.sportlogger;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

public class CancellableCallbackLoopTest {
    @Test
    public void repeatedStartCreatesOneCallbackChain() {
        FakeScheduler scheduler = new FakeScheduler();
        CancellableCallbackLoop loop =
                new CancellableCallbackLoop(scheduler, () -> { }, 200L);

        loop.start();
        loop.start();

        assertEquals(1, scheduler.callbacks.size());
    }

    @Test
    public void stopCancelsThroughPostingSchedulerAndFencesStaleCallback() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger ticks = new AtomicInteger();
        CancellableCallbackLoop loop =
                new CancellableCallbackLoop(scheduler, ticks::incrementAndGet, 200L);

        loop.start();
        Runnable stale = scheduler.onlyCallback();
        loop.stop();

        assertEquals(1, scheduler.removeCalls);
        assertEquals(0, scheduler.callbacks.size());

        loop.start();
        Runnable current = scheduler.onlyCallback();
        assertNotSame(stale, current);
        stale.run();

        assertEquals(0, ticks.get());
        assertEquals(1, scheduler.callbacks.size());
        assertEquals(current, scheduler.onlyCallback());
    }

    @Test
    public void tickReschedulesOnlyWhileRunning() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger ticks = new AtomicInteger();
        CancellableCallbackLoop loop =
                new CancellableCallbackLoop(scheduler, ticks::incrementAndGet, 200L);

        loop.start();
        scheduler.runNext();
        assertEquals(1, ticks.get());
        assertEquals(1, scheduler.callbacks.size());

        loop.stop();
        assertEquals(0, scheduler.callbacks.size());
    }

    private static final class FakeScheduler
            implements CancellableCallbackLoop.Scheduler {
        final List<Runnable> callbacks = new ArrayList<>();
        int removeCalls;

        @Override
        public void postDelayed(Runnable callback, long delayMillis) {
            callbacks.add(callback);
        }

        @Override
        public void removeCallbacks(Runnable callback) {
            removeCalls++;
            callbacks.removeIf(candidate -> candidate == callback);
        }

        Runnable onlyCallback() {
            assertEquals(1, callbacks.size());
            return callbacks.get(0);
        }

        void runNext() {
            Runnable callback = callbacks.remove(0);
            callback.run();
        }
    }
}
