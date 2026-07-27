package com.long2know.sportlogger.services;

final class PermissionCheckedTask implements Runnable {
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
        _cancellationCheck = cancellationCheck;
        _permissionCheck = permissionCheck;
        _permittedTask = permittedTask;
        _permissionLossTask = permissionLossTask;
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

    private boolean isCancelled() {
        return Thread.currentThread().isInterrupted() || _cancellationCheck.isCancelled();
    }
}
