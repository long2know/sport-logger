package com.long2know.sportlogger.measurements;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ResourceOwner {
    public interface Registration {
        void close() throws Exception;
    }

    public interface Ownership {
        void own(String name, Registration registration);
    }

    public interface EventSink {
        void onEvent(MeasurementEvent event);

        void onFailure(ResourceFailure failure);

        void onInvalidated(ResourceException failure);
    }

    public interface Source {
        void open(Ownership ownership, EventSink sink) throws Exception;
    }

    public interface Listener {
        void onEvent(MeasurementEvent event);

        void onFailure(ResourceFailure failure);
    }

    public interface RetryPolicy {
        long delayMillis(int attempt);
    }

    private static final class OwnedRegistration {
        private final String name;
        private final Registration registration;

        private OwnedRegistration(String name, Registration registration) {
            this.name = name;
            this.registration = registration;
        }
    }

    private final String name;
    private final Source source;
    private final ScheduledExecutorService worker;
    private final Listener listener;
    private final RetryPolicy retryPolicy;
    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();
    private final Map<MeasurementEvent.Kind, AtomicLong> lastSequences =
            new EnumMap<>(MeasurementEvent.Kind.class);

    private List<OwnedRegistration> owned = Collections.emptyList();
    private ScheduledFuture<?> retryFuture;
    private int retryAttempt;

    public ResourceOwner(
            String name,
            Source source,
            ScheduledExecutorService worker,
            Listener listener,
            RetryPolicy retryPolicy) {
        this.name = Objects.requireNonNull(name);
        this.source = Objects.requireNonNull(source);
        this.worker = Objects.requireNonNull(worker);
        this.listener = Objects.requireNonNull(listener);
        this.retryPolicy = Objects.requireNonNull(retryPolicy);
        for (MeasurementEvent.Kind kind : MeasurementEvent.Kind.values()) {
            lastSequences.put(kind, new AtomicLong(Long.MIN_VALUE));
        }
    }

    public boolean startAsync() {
        if (!active.compareAndSet(false, true)) {
            return false;
        }
        long currentGeneration = generation.incrementAndGet();
        retryAttempt = 0;
        execute(() -> attemptOpen(currentGeneration));
        return true;
    }

    public boolean suspendAsync() {
        if (!active.compareAndSet(true, false)) {
            return false;
        }
        generation.incrementAndGet();
        cancelRetry();
        execute(() -> {
            closeAll(owned);
            owned = Collections.emptyList();
        });
        return true;
    }

    public boolean refreshAsync() {
        if (!active.get()) {
            return false;
        }
        long currentGeneration = generation.incrementAndGet();
        cancelRetry();
        retryAttempt = 0;
        execute(() -> {
            closeAll(owned);
            owned = Collections.emptyList();
            attemptOpen(currentGeneration);
        });
        return true;
    }

    public void stopAsync() {
        active.set(false);
        generation.incrementAndGet();
        cancelRetry();
        execute(() -> {
            closeAll(owned);
            owned = Collections.emptyList();
        });
        worker.shutdown();
    }

    private void attemptOpen(long expectedGeneration) {
        if (!isCurrent(expectedGeneration)) {
            return;
        }

        List<OwnedRegistration> acquired = new ArrayList<>();
        Ownership ownership = (resourceName, registration) -> acquired.add(
                new OwnedRegistration(resourceName, Objects.requireNonNull(registration)));
        try {
            source.open(ownership, createSink(expectedGeneration));
            if (!isCurrent(expectedGeneration)) {
                closeAll(acquired);
                return;
            }
            closeAll(owned);
            owned = acquired;
            retryAttempt = 0;
        } catch (ResourceException error) {
            closeAll(acquired);
            listener.onFailure(error.getFailure());
            if (error.isRetryable() && isCurrent(expectedGeneration)) {
                scheduleRetry(expectedGeneration);
            }
        } catch (Exception error) {
            closeAll(acquired);
            listener.onFailure(new ResourceFailure(
                    name,
                    ResourceFailure.Code.REGISTRATION_FAILED,
                    message(error)));
            if (isCurrent(expectedGeneration)) {
                scheduleRetry(expectedGeneration);
            }
        }
    }

    private EventSink createSink(long expectedGeneration) {
        return new EventSink() {
            @Override
            public void onEvent(MeasurementEvent event) {
                if (!isCurrent(expectedGeneration)) {
                    return;
                }
                AtomicLong lastSequence = lastSequences.get(event.getKind());
                long sequence = event.getSequence();
                while (true) {
                    long previous = lastSequence.get();
                    if (sequence <= previous) {
                        return;
                    }
                    if (lastSequence.compareAndSet(previous, sequence)) {
                        listener.onEvent(event);
                        return;
                    }
                }
            }

            @Override
            public void onFailure(ResourceFailure failure) {
                if (isCurrent(expectedGeneration)) {
                    listener.onFailure(failure);
                }
            }

            @Override
            public void onInvalidated(ResourceException failure) {
                if (!isCurrent(expectedGeneration)) {
                    return;
                }
                execute(() -> {
                    if (!isCurrent(expectedGeneration)) {
                        return;
                    }
                    listener.onFailure(failure.getFailure());
                    closeAll(owned);
                    owned = Collections.emptyList();
                    if (failure.isRetryable()) {
                        scheduleRetry(expectedGeneration);
                    }
                });
            }
        };
    }

    private void scheduleRetry(long expectedGeneration) {
        ScheduledFuture<?> retry = retryFuture;
        if (retry != null) {
            retry.cancel(false);
        }
        long delay = Math.max(0L, retryPolicy.delayMillis(++retryAttempt));
        retryFuture = worker.schedule(
                () -> attemptOpen(expectedGeneration),
                delay,
                TimeUnit.MILLISECONDS);
    }

    private void cancelRetry() {
        ScheduledFuture<?> retry = retryFuture;
        retryFuture = null;
        if (retry != null) {
            retry.cancel(false);
        }
    }

    private void closeAll(List<OwnedRegistration> registrations) {
        for (int index = registrations.size() - 1; index >= 0; index--) {
            OwnedRegistration ownedRegistration = registrations.get(index);
            try {
                ownedRegistration.registration.close();
            } catch (Exception error) {
                listener.onFailure(new ResourceFailure(
                        ownedRegistration.name,
                        ResourceFailure.Code.CLEANUP_FAILED,
                        message(error)));
            }
        }
    }

    private boolean isCurrent(long expectedGeneration) {
        return active.get() && generation.get() == expectedGeneration;
    }

    private void execute(Runnable runnable) {
        try {
            worker.execute(runnable);
        } catch (RejectedExecutionException ignored) {
            // The owner is already stopping; generation fencing makes the work obsolete.
        }
    }

    private static String message(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }
}
