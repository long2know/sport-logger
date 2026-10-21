package com.long2know.sportlogger.recording;

import android.content.SharedPreferences;

import com.long2know.utilities.models.SportActivity;

import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SharedPreferencesRecordingJournalTest {
    @Test
    public void mainCallbackNeverWaitsForFiveSecondCommitAndLeaseHandoverFencesQueuedState()
            throws Exception {
        BlockingPreferences preferences = new BlockingPreferences();
        SharedPreferencesRecordingJournal oldJournal =
                new SharedPreferencesRecordingJournal(preferences.instance());
        SharedPreferencesRecordingJournal replacementJournal =
                new SharedPreferencesRecordingJournal(preferences.instance());
        BlockingCreateStore store = new BlockingCreateStore();
        ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1);
        LinkedBlockingQueue<Runnable> mainCallbacks = new LinkedBlockingQueue<>();
        List<RecordingState> delivered = Collections.synchronizedList(new ArrayList<>());
        RecordingController controller = new RecordingController(
                oldJournal,
                store,
                (activity, callback) -> callback.onQueued(),
                worker,
                mainCallbacks::add,
                () -> 1_000L,
                snapshot -> delivered.add(snapshot.getState()),
                attempt -> 1L);
        ExecutorService replacementExecutor = Executors.newSingleThreadExecutor();
        try {
            assertTrue(controller.start());
            assertTrue(store.createEntered.await(1, TimeUnit.SECONDS));
            Runnable startingCallback = mainCallbacks.poll(1, TimeUnit.SECONDS);
            assertTrue("STARTING callback was not queued", startingCallback != null);
            assertTrue(oldJournal.isCurrent(1L));

            preferences.blockNextCommit();
            store.releaseCreate.countDown();
            assertTrue(preferences.commitEntered.await(1, TimeUnit.SECONDS));
            long holdStarted = System.nanoTime();

            Future<Long> replacementLease =
                    replacementExecutor.submit(replacementJournal::acquireLease);
            Thread.sleep(100L);
            assertFalse("replacement lease bypassed in-flight commit", replacementLease.isDone());

            long callbackStarted = System.nanoTime();
            startingCallback.run();
            long callbackMillis =
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - callbackStarted);
            assertTrue(
                    "main callback waited " + callbackMillis + "ms behind journal commit",
                    callbackMillis < 100L);
            assertEquals(
                    Collections.singletonList(RecordingState.STARTING),
                    new ArrayList<>(delivered));

            long remainingNanos = TimeUnit.SECONDS.toNanos(5L)
                    - (System.nanoTime() - holdStarted);
            if (remainingNanos > 0L) {
                TimeUnit.NANOSECONDS.sleep(remainingNanos);
            }
            long heldMillis =
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - holdStarted);
            preferences.releaseCommit.countDown();

            long newLease = replacementLease.get(2, TimeUnit.SECONDS);
            assertEquals(2L, newLease);
            assertEquals(RecordingState.RECORDING, replacementJournal.read().getState());

            CountDownLatch workerReached = new CountDownLatch(1);
            worker.execute(workerReached::countDown);
            assertTrue(workerReached.await(1, TimeUnit.SECONDS));
            for (Runnable callback = mainCallbacks.poll();
                 callback != null;
                 callback = mainCallbacks.poll()) {
                callback.run();
            }
            assertEquals(
                    "queued state escaped replacement fencing",
                    Collections.singletonList(RecordingState.STARTING),
                    new ArrayList<>(delivered));
            assertThrows(
                    IllegalStateException.class,
                    () -> oldJournal.write(1L, recording(RecordingState.IDLE)));
            System.out.println(
                    "TIMING_EVIDENCE journalCommitHeldMillis="
                            + heldMillis
                            + " mainCallbackMillis="
                            + callbackMillis);
            assertTrue("journal commit held only " + heldMillis + "ms", heldMillis >= 5_000L);
        } finally {
            store.releaseCreate.countDown();
            preferences.releaseCommit.countDown();
            controller.closeAsync();
            worker.shutdownNow();
            replacementExecutor.shutdownNow();
        }
    }

    @Test
    public void writeCommitAndLeaseTransitionAreAtomicAcrossJournalInstances()
            throws Exception {
        BlockingPreferences preferences = new BlockingPreferences();
        SharedPreferencesRecordingJournal oldJournal =
                new SharedPreferencesRecordingJournal(preferences.instance());
        SharedPreferencesRecordingJournal replacementJournal =
                new SharedPreferencesRecordingJournal(preferences.instance());
        long oldLease = oldJournal.acquireLease();
        preferences.blockNextCommit();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> oldWrite = executor.submit(() -> oldJournal.write(
                    oldLease,
                    recording(RecordingState.STOPPING)));
            assertTrue(preferences.commitEntered.await(1, TimeUnit.SECONDS));

            Future<Long> replacementLease = executor.submit(replacementJournal::acquireLease);
            Thread.sleep(100L);
            assertFalse("replacement lease bypassed in-flight commit", replacementLease.isDone());

            preferences.releaseCommit.countDown();
            oldWrite.get(1, TimeUnit.SECONDS);
            long newLease = replacementLease.get(1, TimeUnit.SECONDS);
            assertEquals(oldLease + 1L, newLease);
            assertEquals(RecordingState.STOPPING, replacementJournal.read().getState());
            assertThrows(
                    IllegalStateException.class,
                    () -> oldJournal.write(oldLease, recording(RecordingState.IDLE)));
        } finally {
            preferences.releaseCommit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void replacementLeaseFencesQueuedCallbacksAndStaleControllerCommands()
            throws Exception {
        BlockingPreferences preferences = new BlockingPreferences();
        SharedPreferencesRecordingJournal oldJournal =
                new SharedPreferencesRecordingJournal(preferences.instance());
        SharedPreferencesRecordingJournal replacementJournal =
                new SharedPreferencesRecordingJournal(preferences.instance());
        ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1);
        List<Runnable> callbacks = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger delivered = new AtomicInteger();
        RecordingController controller = new RecordingController(
                oldJournal,
                new NoOpStore(),
                (activity, callback) -> callback.onQueued(),
                worker,
                callbacks::add,
                () -> 1_000L,
                snapshot -> delivered.incrementAndGet(),
                attempt -> 1L);
        try {
            assertTrue(controller.start());
            assertTrue(waitUntil(
                    () -> oldJournal.read().getState() == RecordingState.RECORDING,
                    1_000L));

            replacementJournal.acquireLease();
            List<Runnable> delayedCallbacks;
            synchronized (callbacks) {
                delayedCallbacks = new ArrayList<>(callbacks);
            }
            for (Runnable callback : delayedCallbacks) {
                callback.run();
            }
            assertEquals(0, delivered.get());

            assertTrue(controller.stop());
            Thread.sleep(50L);
            assertEquals(RecordingState.RECORDING, replacementJournal.read().getState());
        } finally {
            controller.closeAsync();
            worker.shutdownNow();
        }
    }

    @Test
    public void monotonicAnchorAndBootIdentityRoundTripWithoutChangingLegacyKeys()
            throws Exception {
        BlockingPreferences preferences = new BlockingPreferences();
        SharedPreferencesRecordingJournal journal =
                new SharedPreferencesRecordingJournal(preferences.instance());
        long lease = journal.acquireLease();
        PersistentRecording recording = new PersistentRecording(
                RecordingState.RECORDING,
                7,
                RecordingAuthority.CANONICAL,
                10_000L,
                11_000L,
                5_000L,
                5_000L,
                2_000L,
                2_000L,
                0L,
                9,
                null);

        journal.write(lease, recording);
        PersistentRecording restored = journal.read();

        assertEquals(RecordingState.RECORDING, restored.getState());
        assertEquals(RecordingAuthority.CANONICAL, restored.getAuthority());
        assertEquals(11_000L, restored.getActiveSinceMillis());
        assertEquals(5_000L, restored.getActiveSinceElapsedRealtimeMillis());
        assertEquals(5_000L, restored.getElapsedSinceElapsedRealtimeMillis());
        assertEquals(2_000L, restored.getAccumulatedMillis());
        assertEquals(2_000L, restored.getAccumulatedElapsedMillis());
        assertEquals(9, restored.getBootCount());
    }

    @Test
    public void missingAuthorityFromPreCutoverJournalDefaultsToLegacy()
            throws Exception {
        BlockingPreferences preferences = new BlockingPreferences();
        SharedPreferencesRecordingJournal journal =
                new SharedPreferencesRecordingJournal(preferences.instance());
        long lease = journal.acquireLease();
        journal.write(lease, recording(RecordingState.PAUSED));
        preferences.values.remove("authority");

        assertEquals(
                RecordingAuthority.LEGACY_COMPATIBILITY,
                journal.read().getAuthority());
    }

    private static PersistentRecording recording(RecordingState state) {
        if (state == RecordingState.IDLE) {
            return PersistentRecording.idle();
        }
        return new PersistentRecording(
                state,
                1,
                1_000L,
                0L,
                5_000L,
                6_000L,
                null);
    }

    private static boolean waitUntil(Check check, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (check.evaluate()) {
                return true;
            }
            Thread.sleep(5L);
        }
        return check.evaluate();
    }

    private interface Check {
        boolean evaluate() throws Exception;
    }

    private static class NoOpStore implements RecordingStore {
        @Override
        public int createActivity(long startedAtMillis) throws Exception {
            return 1;
        }

        @Override
        public void writeSample(int activityId) {
        }

        @Override
        public void finishActivity(int activityId, long endedAtMillis) {
        }

        @Override
        public void deleteActivity(int activityId) {
        }

        @Override
        public SportActivity loadActivity(int activityId) {
            SportActivity activity = new SportActivity();
            activity.Id = activityId;
            activity.StartTimeUTC = new Date(1_000L);
            return activity;
        }
    }

    private static final class BlockingCreateStore extends NoOpStore {
        private final CountDownLatch createEntered = new CountDownLatch(1);
        private final CountDownLatch releaseCreate = new CountDownLatch(1);

        @Override
        public int createActivity(long startedAtMillis) throws Exception {
            createEntered.countDown();
            if (!releaseCreate.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release activity creation");
            }
            return super.createActivity(startedAtMillis);
        }
    }

    @SuppressWarnings("unchecked")
    private static final class BlockingPreferences implements InvocationHandler {
        private final Map<String, Object> values = new HashMap<>();
        private final SharedPreferences instance;
        private volatile boolean blockNextCommit;
        private CountDownLatch commitEntered = new CountDownLatch(1);
        private CountDownLatch releaseCommit = new CountDownLatch(1);

        private BlockingPreferences() {
            instance = (SharedPreferences) Proxy.newProxyInstance(
                    SharedPreferences.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.class},
                    this);
        }

        private SharedPreferences instance() {
            return instance;
        }

        private void blockNextCommit() {
            commitEntered = new CountDownLatch(1);
            releaseCommit = new CountDownLatch(1);
            blockNextCommit = true;
        }

        @Override
        public synchronized Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            switch (name) {
                case "getAll":
                    return new HashMap<>(values);
                case "getString":
                case "getInt":
                case "getLong":
                case "getFloat":
                case "getBoolean":
                    return values.getOrDefault(args[0], args[1]);
                case "getStringSet":
                    Object stored = values.get(args[0]);
                    return stored == null
                            ? args[1]
                            : new HashSet<>((Set<String>) stored);
                case "contains":
                    return values.containsKey(args[0]);
                case "edit":
                    return editor();
                case "registerOnSharedPreferenceChangeListener":
                case "unregisterOnSharedPreferenceChangeListener":
                    return null;
                case "toString":
                    return "BlockingPreferences";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    throw new UnsupportedOperationException(name);
            }
        }

        private SharedPreferences.Editor editor() {
            Map<String, Object> changes = new HashMap<>();
            Set<String> removals = new HashSet<>();
            return (SharedPreferences.Editor) Proxy.newProxyInstance(
                    SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.startsWith("put")) {
                            Object value = args[1];
                            if (value instanceof Set) {
                                value = new HashSet<>((Set<?>) value);
                            }
                            changes.put((String) args[0], value);
                            removals.remove(args[0]);
                            return proxy;
                        }
                        if ("remove".equals(name)) {
                            removals.add((String) args[0]);
                            changes.remove(args[0]);
                            return proxy;
                        }
                        if ("clear".equals(name)) {
                            synchronized (BlockingPreferences.this) {
                                removals.addAll(values.keySet());
                            }
                            changes.clear();
                            return proxy;
                        }
                        if ("commit".equals(name) || "apply".equals(name)) {
                            commit(changes, removals);
                            return "commit".equals(name) ? true : null;
                        }
                        if ("toString".equals(name)) {
                            return "BlockingEditor";
                        }
                        throw new UnsupportedOperationException(name);
                    });
        }

        private void commit(Map<String, Object> changes, Set<String> removals)
                throws InterruptedException {
            if (blockNextCommit) {
                blockNextCommit = false;
                commitEntered.countDown();
                releaseCommit.await(5, TimeUnit.SECONDS);
            }
            synchronized (this) {
                for (String removal : removals) {
                    values.remove(removal);
                }
                values.putAll(changes);
            }
        }
    }
}
