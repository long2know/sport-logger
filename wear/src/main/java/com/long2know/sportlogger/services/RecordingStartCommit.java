package com.long2know.sportlogger.services;

final class RecordingStartCommit {
    interface OwnershipClaim {
        boolean owns();
    }

    interface StateCommit {
        boolean commit();
    }

    private RecordingStartCommit() {
    }

    static boolean commit(
            Object ownershipLock,
            OwnershipClaim ownershipClaim,
            StateCommit stateCommit,
            Runnable stopwatchStart) {
        synchronized (ownershipLock) {
            if (!ownershipClaim.owns() || !stateCommit.commit()) {
                return false;
            }
            stopwatchStart.run();
            return true;
        }
    }
}
