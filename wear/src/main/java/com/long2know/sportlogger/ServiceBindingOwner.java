package com.long2know.sportlogger;

final class ServiceBindingOwner {
    private boolean bound;

    synchronized boolean isBound() {
        return bound;
    }

    synchronized void setBound(boolean bound) {
        this.bound = bound;
    }

    void unbindIfBound(Runnable unbindAction) {
        synchronized (this) {
            if (!bound) {
                return;
            }
            bound = false;
        }
        unbindAction.run();
    }
}
