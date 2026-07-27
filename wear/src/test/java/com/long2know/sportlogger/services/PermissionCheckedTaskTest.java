package com.long2know.sportlogger.services;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class PermissionCheckedTaskTest {
    @Test
    public void grantedPermissionsRunRecordingTask() {
        AtomicInteger recordingRuns = new AtomicInteger();
        AtomicInteger permissionLossRuns = new AtomicInteger();
        PermissionCheckedTask task = new PermissionCheckedTask(
                () -> true,
                recordingRuns::incrementAndGet,
                permissionLossRuns::incrementAndGet);

        task.run();

        assertEquals(1, recordingRuns.get());
        assertEquals(0, permissionLossRuns.get());
    }

    @Test
    public void deniedPermissionsStopBeforeRecordingTask() {
        AtomicInteger recordingRuns = new AtomicInteger();
        AtomicInteger permissionLossRuns = new AtomicInteger();
        PermissionCheckedTask task = new PermissionCheckedTask(
                () -> false,
                recordingRuns::incrementAndGet,
                permissionLossRuns::incrementAndGet);

        task.run();

        assertEquals(0, recordingRuns.get());
        assertEquals(1, permissionLossRuns.get());
    }

    @Test
    public void interruptedTaskDoesNotWriteOrReportPermissionLoss() {
        AtomicInteger recordingRuns = new AtomicInteger();
        AtomicInteger permissionLossRuns = new AtomicInteger();
        PermissionCheckedTask task = new PermissionCheckedTask(
                () -> true,
                recordingRuns::incrementAndGet,
                permissionLossRuns::incrementAndGet);

        Thread.currentThread().interrupt();
        try {
            task.run();
        } finally {
            Thread.interrupted();
        }

        assertEquals(0, recordingRuns.get());
        assertEquals(0, permissionLossRuns.get());
    }

    @Test
    public void cancelledGenerationDoesNotMasqueradeAsPermissionLoss() {
        AtomicInteger recordingRuns = new AtomicInteger();
        AtomicInteger permissionLossRuns = new AtomicInteger();
        PermissionCheckedTask task = new PermissionCheckedTask(
                () -> true,
                () -> false,
                recordingRuns::incrementAndGet,
                permissionLossRuns::incrementAndGet);

        task.run();

        assertEquals(0, recordingRuns.get());
        assertEquals(0, permissionLossRuns.get());
    }
}
