package com.long2know.sportlogger.services;

import com.long2know.utilities.models.SharedData;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

public class StopWatchTest {
    @Test
    public void pauseAndResetAreIdempotentAndCancelCallbacks() {
        FakeCallbackScheduler callbacks = new FakeCallbackScheduler();
        FakeTimeSource time = new FakeTimeSource();
        StopWatch stopWatch = new StopWatch(callbacks, time);

        time.now = 1_000L;
        stopWatch.startTImer();
        assertEquals(1, callbacks.size());

        time.now = 2_500L;
        callbacks.runNext();
        assertEquals("00:00:01", SharedData.getInstance().Duration);
        assertEquals(1, callbacks.size());

        stopWatch.pauseTimer();
        stopWatch.pauseTimer();
        stopWatch.resetTimer();
        stopWatch.resetTimer();

        assertEquals(0, callbacks.size());
        assertEquals("00:00:00", SharedData.getInstance().Duration);
    }

    @Test
    public void staleCallbackCannotWriteOrRescheduleIntoLaterRecording() {
        FakeCallbackScheduler callbacks = new FakeCallbackScheduler();
        FakeTimeSource time = new FakeTimeSource();
        StopWatch stopWatch = new StopWatch(callbacks, time);

        time.now = 1_000L;
        stopWatch.startTImer();
        Runnable staleCallback = callbacks.onlyCallback();

        stopWatch.pauseTimer();
        stopWatch.resetTimer();

        time.now = 5_000L;
        stopWatch.startTImer();
        Runnable currentCallback = callbacks.onlyCallback();
        assertNotSame(staleCallback, currentCallback);

        staleCallback.run();

        assertEquals(1, callbacks.size());
        assertEquals(currentCallback, callbacks.onlyCallback());
        assertEquals("00:00:00", SharedData.getInstance().Duration);

        time.now = 7_000L;
        callbacks.runNext();
        assertEquals("00:00:02", SharedData.getInstance().Duration);
        assertEquals(1, callbacks.size());
    }

    @Test
    public void repeatedStartDoesNotQueueDuplicateCallbacks() {
        FakeCallbackScheduler callbacks = new FakeCallbackScheduler();
        FakeTimeSource time = new FakeTimeSource();
        StopWatch stopWatch = new StopWatch(callbacks, time);

        stopWatch.startTImer();
        stopWatch.startTImer();

        assertEquals(1, callbacks.size());
    }

    private static final class FakeCallbackScheduler
            implements StopWatch.CallbackScheduler {
        private final List<Runnable> _callbacks = new ArrayList<>();

        @Override
        public void postDelayed(Runnable callback, long delayMillis) {
            _callbacks.add(callback);
        }

        @Override
        public void removeCallbacks(Runnable callback) {
            _callbacks.removeIf(candidate -> candidate == callback);
        }

        int size() {
            return _callbacks.size();
        }

        Runnable onlyCallback() {
            assertEquals(1, _callbacks.size());
            return _callbacks.get(0);
        }

        void runNext() {
            Runnable callback = _callbacks.remove(0);
            callback.run();
        }
    }

    private static final class FakeTimeSource implements StopWatch.TimeSource {
        long now;

        @Override
        public long uptimeMillis() {
            return now;
        }
    }
}
