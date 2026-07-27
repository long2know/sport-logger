package com.long2know.sportlogger.services;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecordingStartCommitTest {
    @Test
    public void preemptionCannotPauseBetweenOwnershipCommitAndStopwatchStart()
            throws Exception {
        Object ownershipLock = new Object();
        AtomicBoolean owns = new AtomicBoolean(true);
        AtomicBoolean stopwatchRunning = new AtomicBoolean(false);
        AtomicBoolean committed = new AtomicBoolean(false);
        CountDownLatch stateCommitEntered = new CountDownLatch(1);
        CountDownLatch releaseStateCommit = new CountDownLatch(1);
        CountDownLatch preemptionAttempted = new CountDownLatch(1);
        CountDownLatch preemptionCompleted = new CountDownLatch(1);

        Thread starter = new Thread(() -> committed.set(
                RecordingStartCommit.commit(
                        ownershipLock,
                        owns::get,
                        () -> {
                            stateCommitEntered.countDown();
                            awaitUninterruptibly(releaseStateCommit);
                            return true;
                        },
                        () -> stopwatchRunning.set(true))));
        starter.start();
        assertTrue(stateCommitEntered.await(1, TimeUnit.SECONDS));

        Thread preemptor = new Thread(() -> {
            preemptionAttempted.countDown();
            synchronized (ownershipLock) {
                owns.set(false);
            }
            stopwatchRunning.set(false);
            preemptionCompleted.countDown();
        });
        preemptor.start();
        assertTrue(preemptionAttempted.await(1, TimeUnit.SECONDS));
        assertFalse(preemptionCompleted.await(50, TimeUnit.MILLISECONDS));

        releaseStateCommit.countDown();
        starter.join(1_000L);
        preemptor.join(1_000L);

        assertTrue(committed.get());
        assertTrue(preemptionCompleted.await(1, TimeUnit.SECONDS));
        assertFalse(stopwatchRunning.get());
    }

    @Test
    public void staleCompletionCannotCommitOrStartStopwatch() {
        AtomicBoolean stateCommitted = new AtomicBoolean(false);
        AtomicBoolean stopwatchStarted = new AtomicBoolean(false);

        boolean committed = RecordingStartCommit.commit(
                new Object(),
                () -> false,
                () -> {
                    stateCommitted.set(true);
                    return true;
                },
                () -> stopwatchStarted.set(true));

        assertFalse(committed);
        assertFalse(stateCommitted.get());
        assertFalse(stopwatchStarted.get());
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
