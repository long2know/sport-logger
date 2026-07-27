package com.long2know.sportlogger.services;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecordingPersistenceBarrierTest {
    @Test
    public void replacementWaitsForPredecessorWriteThenReloadsIt()
            throws Exception {
        RecordingPersistenceBarrier barrier =
                new RecordingPersistenceBarrier();
        RecordingPersistenceBarrier.Epoch predecessor =
                barrier.reserveEpoch();
        AtomicReference<String> durable = new AtomicReference<>("initial");
        assertTrue(barrier.activate(
                predecessor, durable::get).activated());

        CountDownLatch writeEntered = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        AtomicBoolean writeSucceeded = new AtomicBoolean();
        Thread writer = new Thread(() -> writeSucceeded.set(
                barrier.write(predecessor, () -> {
                    writeEntered.countDown();
                    await(releaseWrite);
                    durable.set("predecessor");
                    return true;
                })));
        writer.start();
        assertTrue(writeEntered.await(1, TimeUnit.SECONDS));

        RecordingPersistenceBarrier.Epoch replacement =
                barrier.reserveEpoch();
        AtomicReference<RecordingPersistenceBarrier.Activation<String>>
                activation = new AtomicReference<>();
        CountDownLatch activationStarted = new CountDownLatch(1);
        CountDownLatch activationFinished = new CountDownLatch(1);
        Thread activator = new Thread(() -> {
            activationStarted.countDown();
            activation.set(barrier.activate(replacement, durable::get));
            activationFinished.countDown();
        });
        activator.start();

        assertTrue(activationStarted.await(1, TimeUnit.SECONDS));
        assertFalse(activationFinished.await(50, TimeUnit.MILLISECONDS));
        releaseWrite.countDown();
        writer.join(1_000L);
        activator.join(1_000L);

        assertTrue(writeSucceeded.get());
        assertFalse(writer.isAlive());
        assertFalse(activator.isAlive());
        assertTrue(activationFinished.await(1, TimeUnit.SECONDS));
        assertTrue(activation.get().activated());
        assertEquals("predecessor", activation.get().getValue());
    }

    @Test
    public void staleEpochCannotOverwriteReplacementOwnership() {
        RecordingPersistenceBarrier barrier =
                new RecordingPersistenceBarrier();
        AtomicReference<String> durable = new AtomicReference<>("initial");
        RecordingPersistenceBarrier.Epoch predecessor =
                barrier.reserveEpoch();
        assertTrue(barrier.activate(
                predecessor, durable::get).activated());

        RecordingPersistenceBarrier.Epoch replacement =
                barrier.reserveEpoch();
        assertTrue(barrier.activate(replacement, () -> {
            durable.set("replacement");
            return durable.get();
        }).activated());

        assertFalse(barrier.write(predecessor, () -> {
            durable.set("stale");
            return true;
        }));
        assertEquals("replacement", durable.get());
        assertEquals(
                "replacement",
                barrier.read(replacement, "stale", durable::get));
        assertEquals(
                "stale",
                barrier.read(predecessor, "stale", durable::get));
    }

    @Test
    public void failedActivationDoesNotPreventANewerServiceFromRecovering() {
        RecordingPersistenceBarrier barrier =
                new RecordingPersistenceBarrier();
        RecordingPersistenceBarrier.Activation<String> failed =
                barrier.activate(barrier.reserveEpoch(), () -> {
                    throw new IllegalStateException("store failed");
                });

        assertEquals(
                RecordingPersistenceBarrier.ActivationStatus.FAILED,
                failed.getStatus());
        assertTrue(failed.getFailure() instanceof IllegalStateException);

        RecordingPersistenceBarrier.Activation<String> recovered =
                barrier.activate(
                        barrier.reserveEpoch(), () -> "durable-state");
        assertTrue(recovered.activated());
        assertEquals("durable-state", recovered.getValue());
    }

    @Test
    public void processDeathReloadsDurableStateWithAFreshBarrier() {
        AtomicReference<String> durable = new AtomicReference<>("initial");
        RecordingPersistenceBarrier firstProcess =
                new RecordingPersistenceBarrier();
        RecordingPersistenceBarrier.Epoch firstEpoch =
                firstProcess.reserveEpoch();
        assertTrue(firstProcess.activate(
                firstEpoch, durable::get).activated());
        assertTrue(firstProcess.write(firstEpoch, () -> {
            durable.set("persisted");
            return true;
        }));

        RecordingPersistenceBarrier replacementProcess =
                new RecordingPersistenceBarrier();
        RecordingPersistenceBarrier.Activation<String> reloaded =
                replacementProcess.activate(
                        replacementProcess.reserveEpoch(), durable::get);

        assertTrue(reloaded.activated());
        assertEquals("persisted", reloaded.getValue());
    }

    private static void await(CountDownLatch latch) {
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
