package com.long2know.sportlogger.services;

final class OwnedListenerRegistry<T extends BoundedLifecycle> {
    private T _owner;

    synchronized LifecycleTermination replace(T replacement, long timeoutMillis) {
        if (_owner == replacement) {
            return LifecycleTermination.TERMINATED;
        }
        if (_owner != null) {
            LifecycleTermination termination = _owner.shutdown(timeoutMillis);
            if (!termination.succeeded()) {
                return termination;
            }
        }

        _owner = replacement;
        try {
            LifecycleTermination startup = replacement.start(timeoutMillis);
            return startup;
        } catch (RuntimeException exception) {
            return LifecycleTermination.FAILED;
        }
    }

    synchronized LifecycleTermination release(T owner, long timeoutMillis) {
        if (_owner != owner) {
            return LifecycleTermination.TERMINATED;
        }

        LifecycleTermination termination = owner.shutdown(timeoutMillis);
        if (termination.succeeded() && _owner == owner) {
            _owner = null;
        }
        return termination;
    }

    synchronized boolean isOwner(T owner) {
        return _owner == owner;
    }
}
