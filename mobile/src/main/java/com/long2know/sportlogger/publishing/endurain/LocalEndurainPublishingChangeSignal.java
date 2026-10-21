package com.long2know.sportlogger.publishing.endurain;

import android.content.Context;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LocalEndurainPublishingChangeSignal
        implements EndurainPublishingChangeSignal {
    private static final CopyOnWriteArrayList<Registration> REGISTRATIONS =
            new CopyOnWriteArrayList<>();

    public LocalEndurainPublishingChangeSignal(Context context) {
        Objects.requireNonNull(context, "context").getApplicationContext();
    }

    @Override
    public void signal() {
        for (Registration registration : REGISTRATIONS) {
            registration.notifyListener();
        }
    }

    @Override
    public AutoCloseable observe(
            Executor executor,
            Runnable listener) {
        Registration registration = new Registration(
                Objects.requireNonNull(executor, "executor"),
                Objects.requireNonNull(listener, "listener"));
        REGISTRATIONS.add(registration);
        return () -> {
            if (registration.close()) {
                REGISTRATIONS.remove(registration);
            }
        };
    }

    private static final class Registration {
        private final Executor executor;
        private final Runnable listener;
        private final AtomicBoolean open = new AtomicBoolean(true);

        private Registration(Executor executor, Runnable listener) {
            this.executor = executor;
            this.listener = listener;
        }

        private void notifyListener() {
            if (!open.get()) {
                return;
            }
            try {
                executor.execute(listener);
            } catch (RuntimeException ignored) {
            }
        }

        private boolean close() {
            return open.compareAndSet(true, false);
        }
    }
}
