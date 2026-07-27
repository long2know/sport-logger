package com.long2know.sportlogger.services;

import java.util.concurrent.atomic.AtomicBoolean;

final class PermissionCheckedTask
        implements RecordingWriterCoordinator.Task {
    interface PermissionCheck {
        boolean allRequiredPermissionsGranted();
    }

    interface CancellationCheck {
        boolean isCancelled();
    }

    private final CancellationCheck _cancellationCheck;
    private final PermissionCheck _permissionCheck;
    private final Runnable _permittedTask;
    private final Runnable _permissionLossTask;
    private final Runnable _cleanupTask;
    private final AtomicBoolean _closed = new AtomicBoolean(false);

    PermissionCheckedTask(
            PermissionCheck permissionCheck,
            Runnable permittedTask,
            Runnable permissionLossTask) {
        this(() -> false, permissionCheck, permittedTask, permissionLossTask);
    }

    PermissionCheckedTask(
            CancellationCheck cancellationCheck,
            PermissionCheck permissionCheck,
            Runnable permittedTask,
            Runnable permissionLossTask) {
        this(
                cancellationCheck,
                permissionCheck,
                permittedTask,
                permissionLossTask,
                () -> { });
    }

    PermissionCheckedTask(
            CancellationCheck cancellationCheck,
            PermissionCheck permissionCheck,
            Runnable permittedTask,
            Runnable permissionLossTask,
            Runnable cleanupTask) {
        _cancellationCheck = cancellationCheck;
        _permissionCheck = permissionCheck;
        _permittedTask = permittedTask;
        _permissionLossTask = permissionLossTask;
        _cleanupTask = cleanupTask;
    }

    @Override
    public void run() {
        if (isCancelled()) {
            return;
        }
        if (!_permissionCheck.allRequiredPermissionsGranted()) {
            if (isCancelled()) {
                return;
            }
            _permissionLossTask.run();
            return;
        }
        if (isCancelled()) {
            return;
        }
        _permittedTask.run();
    }

    @Override
    public void close() {
        if (_closed.compareAndSet(false, true)) {
            _cleanupTask.run();
        }
    }

    private boolean isCancelled() {
        return _closed.get()
                || Thread.currentThread().isInterrupted()
                || _cancellationCheck.isCancelled();
    }
}
