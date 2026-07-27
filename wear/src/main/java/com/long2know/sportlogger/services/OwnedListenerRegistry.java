package com.long2know.sportlogger.services;

final class OwnedListenerRegistry<T extends BoundedLifecycle> {
    interface OwnershipClaim {
        boolean claim();
    }

    private T _owner;
    private long _version;

    LifecycleTermination replace(T replacement, long timeoutMillis) {
        return replace(replacement, null, timeoutMillis);
    }

    LifecycleTermination replace(
            T replacement,
            OwnershipClaim ownershipClaim,
            long timeoutMillis) {
        T previous;
        long version;
        synchronized (this) {
            if (ownershipClaim != null && !ownershipClaim.claim()) {
                return LifecycleTermination.FAILED;
            }
            if (_owner == replacement) {
                return LifecycleTermination.TERMINATED;
            }
            previous = _owner;
            version = _version;
        }

        if (previous != null) {
            LifecycleTermination termination = previous.shutdown(timeoutMillis);
            if (!termination.succeeded()) {
                return termination;
            }
        }

        synchronized (this) {
            if ((ownershipClaim != null && !ownershipClaim.claim())
                    || _version != version
                    || _owner != previous) {
                return LifecycleTermination.FAILED;
            }
            _owner = replacement;
            _version++;
        }
        try {
            return replacement.start(timeoutMillis);
        } catch (RuntimeException exception) {
            return LifecycleTermination.FAILED;
        }
    }

    LifecycleTermination release(T owner, long timeoutMillis) {
        long version;
        synchronized (this) {
            if (_owner != owner) {
                return LifecycleTermination.TERMINATED;
            }
            version = _version;
        }

        LifecycleTermination termination = owner.shutdown(timeoutMillis);
        if (termination.succeeded()) {
            synchronized (this) {
                if (_version == version && _owner == owner) {
                    _owner = null;
                    _version++;
                }
            }
        }
        return termination;
    }

    synchronized boolean isOwner(T owner) {
        return _owner == owner;
    }
}
