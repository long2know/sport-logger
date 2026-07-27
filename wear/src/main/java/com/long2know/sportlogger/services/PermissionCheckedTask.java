package com.long2know.sportlogger.services;

final class PermissionCheckedTask implements Runnable {
    interface PermissionCheck {
        boolean allRequiredPermissionsGranted();
    }

    private final PermissionCheck _permissionCheck;
    private final Runnable _permittedTask;
    private final Runnable _permissionLossTask;

    PermissionCheckedTask(
            PermissionCheck permissionCheck,
            Runnable permittedTask,
            Runnable permissionLossTask) {
        _permissionCheck = permissionCheck;
        _permittedTask = permittedTask;
        _permissionLossTask = permissionLossTask;
    }

    @Override
    public void run() {
        if (!_permissionCheck.allRequiredPermissionsGranted()) {
            _permissionLossTask.run();
            return;
        }
        _permittedTask.run();
    }
}
