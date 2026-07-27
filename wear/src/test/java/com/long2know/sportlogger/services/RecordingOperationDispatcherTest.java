package com.long2know.sportlogger.services;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecordingOperationDispatcherTest {
    @Test
    public void acceptedMainThreadRequestDoesNotRunLifecycleWorkInline() {
        QueuedExecutor executor = new QueuedExecutor();
        RecordingOperationDispatcher dispatcher =
                new RecordingOperationDispatcher(executor);
        RecordingOperationDispatcher.Token token = dispatcher.begin(
                RecordingOperationResult.Operation.STOP, 41, 7L).getToken();
        AtomicInteger effects = new AtomicInteger();

        assertEquals(
                RecordingOperationDispatcher.DispatchStatus.SCHEDULED,
                dispatcher.tryExecute(token, effects::incrementAndGet));

        assertEquals(0, effects.get());
        assertEquals(1, executor.tasks.size());
        executor.runNext();
        assertEquals(1, effects.get());
    }

    @Test
    public void duplicateTerminalClickReturnsTheExistingPendingToken() {
        RecordingOperationDispatcher dispatcher =
                new RecordingOperationDispatcher(new QueuedExecutor());
        RecordingOperationDispatcher.BeginResult stop = dispatcher.begin(
                RecordingOperationResult.Operation.STOP, 51, 8L);
        RecordingOperationDispatcher.BeginResult duplicate = dispatcher.begin(
                RecordingOperationResult.Operation.DISCARD, 51, 8L);

        assertEquals(
                RecordingOperationDispatcher.BeginStatus.ACCEPTED,
                stop.getStatus());
        assertEquals(
                RecordingOperationDispatcher.BeginStatus.PENDING,
                duplicate.getStatus());
        assertEquals(
                stop.getToken().getOperationToken(),
                duplicate.getToken().getOperationToken());
        assertEquals(
                RecordingOperationResult.Operation.STOP,
                duplicate.getToken().getOperation());
    }

    @Test
    public void permissionLossPreemptsStopWithoutRunningStaleTerminalEffects() {
        QueuedExecutor executor = new QueuedExecutor();
        RecordingOperationDispatcher dispatcher =
                new RecordingOperationDispatcher(executor);
        RecordingOperationDispatcher.Token stop = dispatcher.begin(
                RecordingOperationResult.Operation.STOP, 61, 9L).getToken();
        AtomicInteger terminalEffects = new AtomicInteger();
        AtomicInteger permissionCleanup = new AtomicInteger();
        dispatcher.tryExecute(stop, terminalEffects::incrementAndGet);

        dispatcher.invalidateActive();
        RecordingOperationDispatcher.Token permission = dispatcher.begin(
                RecordingOperationResult.Operation.PERMISSION_LOSS,
                61,
                9L).getToken();
        dispatcher.tryExecute(permission, permissionCleanup::incrementAndGet);
        executor.runAll();

        assertEquals(0, terminalEffects.get());
        assertEquals(1, permissionCleanup.get());
    }

    @Test
    public void staleOperationCompletionCannotRunAfterNewTokenOwnsPipeline() {
        QueuedExecutor executor = new QueuedExecutor();
        RecordingOperationDispatcher dispatcher =
                new RecordingOperationDispatcher(executor);
        RecordingOperationDispatcher.Token first = dispatcher.begin(
                RecordingOperationResult.Operation.STOP, 71, 10L).getToken();
        AtomicInteger staleEffects = new AtomicInteger();
        dispatcher.tryExecute(first, staleEffects::incrementAndGet);

        dispatcher.invalidateActive();
        RecordingOperationDispatcher.Token replacement = dispatcher.begin(
                RecordingOperationResult.Operation.RECOVER, 71, 10L).getToken();
        AtomicInteger replacementEffects = new AtomicInteger();
        dispatcher.tryExecute(replacement, replacementEffects::incrementAndGet);
        executor.runAll();

        assertEquals(0, staleEffects.get());
        assertEquals(1, replacementEffects.get());
    }

    @Test
    public void writerFailureRacingDestroyCannotEscapeRejectedExecution() {
        AtomicReference<RecordingOperationDispatcher> reference =
                new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        RecordingOperationDispatcher.Executor executor =
                new RecordingOperationDispatcher.Executor() {
                    @Override
                    public void execute(Runnable task) {
                        reference.get().close();
                        throw new RejectedExecutionException("destroyed");
                    }

                    @Override
                    public void shutdownNow() {
                    }
                };
        RecordingOperationDispatcher dispatcher =
                new RecordingOperationDispatcher(executor);
        reference.set(dispatcher);
        RecordingOperationDispatcher.Token failureCleanup = dispatcher.begin(
                RecordingOperationResult.Operation.NONE, 81, 11L).getToken();

        assertEquals(
                RecordingOperationDispatcher.DispatchStatus.REJECTED,
                dispatcher.tryExecute(
                        failureCleanup, callbacks::incrementAndGet));
        assertTrue(dispatcher.isClosed());
        assertEquals(0, callbacks.get());
    }

    @Test
    public void destructionInvalidatesGenerationBeforeExecutorShutdown() {
        AtomicReference<RecordingOperationDispatcher> reference =
                new AtomicReference<>();
        AtomicBoolean invalidatedBeforeShutdown = new AtomicBoolean();
        RecordingOperationDispatcher.Executor executor =
                new RecordingOperationDispatcher.Executor() {
                    @Override
                    public void execute(Runnable task) {
                    }

                    @Override
                    public void shutdownNow() {
                        invalidatedBeforeShutdown.set(
                                reference.get().isClosed());
                    }
                };
        RecordingOperationDispatcher dispatcher =
                new RecordingOperationDispatcher(executor);
        reference.set(dispatcher);
        long generation = dispatcher.getServiceGeneration();

        assertTrue(dispatcher.close());

        assertTrue(invalidatedBeforeShutdown.get());
        assertFalse(dispatcher.isServiceGenerationCurrent(generation));
        assertEquals(
                RecordingOperationDispatcher.DispatchStatus.STALE,
                dispatcher.tryExecute(generation, () -> { }));
    }

    @Test
    public void queuedLateCallbackCannotMutateReplacementGeneration() {
        QueuedExecutor oldExecutor = new QueuedExecutor();
        RecordingOperationDispatcher oldDispatcher =
                new RecordingOperationDispatcher(oldExecutor);
        long oldGeneration = oldDispatcher.getServiceGeneration();
        AtomicInteger staleNotifications = new AtomicInteger();
        oldDispatcher.tryExecute(
                oldGeneration, staleNotifications::incrementAndGet);
        oldDispatcher.close();

        QueuedExecutor replacementExecutor = new QueuedExecutor();
        RecordingOperationDispatcher replacement =
                new RecordingOperationDispatcher(replacementExecutor);
        AtomicInteger replacementNotifications = new AtomicInteger();
        replacement.tryExecute(
                replacement.getServiceGeneration(),
                replacementNotifications::incrementAndGet);

        oldExecutor.runAll();
        replacementExecutor.runAll();

        assertEquals(0, staleNotifications.get());
        assertEquals(1, replacementNotifications.get());
    }

    private static final class QueuedExecutor
            implements RecordingOperationDispatcher.Executor {
        final List<Runnable> tasks = new ArrayList<>();
        boolean shutdown;

        @Override
        public void execute(Runnable task) {
            if (shutdown) {
                throw new RejectedExecutionException("shutdown");
            }
            tasks.add(task);
        }

        @Override
        public void shutdownNow() {
            shutdown = true;
        }

        void runNext() {
            tasks.remove(0).run();
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                runNext();
            }
        }
    }
}
