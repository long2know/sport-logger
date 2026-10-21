package com.long2know.sportlogger.publishing.endurain.factory;

import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository;
import com.long2know.sportlogger.publishing.endurain.EndurainCredentialGateway;
import com.long2know.sportlogger.publishing.endurain.EndurainDeliveryKey;
import com.long2know.sportlogger.publishing.endurain.EndurainPublishingCoordinator;
import com.long2know.sportlogger.publishing.endurain.EndurainPublishingChangeSignal;
import com.long2know.sportlogger.publishing.endurain.profile.EndurainConnectionProfile;
import com.long2know.sportlogger.publishing.endurain.profile.EndurainConnectionProfileStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EndurainPublishingController implements AutoCloseable {
    private final PhoneActivityRepository repository;
    private final EndurainConnectionProfileStore profiles;
    private final EndurainPublishingCoordinator coordinator;
    private final EndurainPublishingChangeSignal changes;
    private final ExecutorService worker;
    private final boolean ownsWorker;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final List<AutoCloseable> observations = new ArrayList<>();

    EndurainPublishingController(
            PhoneActivityRepository repository,
            EndurainConnectionProfileStore profiles,
            EndurainPublishingCoordinator coordinator,
            EndurainPublishingChangeSignal changes,
            ExecutorService worker,
            boolean ownsWorker) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.changes = Objects.requireNonNull(changes, "changes");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.ownsWorker = ownsWorker;
    }

    public CompletableFuture<EndurainConnectionProfile> saveConnection(
            String existingConnectionIdentity,
            String label,
            String httpsBaseUrl,
            boolean enabled) {
        return submit(() -> coordinator.saveConnection(
                existingConnectionIdentity,
                label,
                httpsBaseUrl,
                enabled));
    }

    public CompletableFuture<EndurainConnectionProfile> setConnectionEnabled(
            String connectionIdentity,
            boolean enabled) {
        return submit(() -> coordinator.setConnectionEnabled(
                connectionIdentity,
                enabled));
    }

    public CompletableFuture<EndurainCredentialGateway.State> saveCredential(
            String connectionIdentity,
            char[] apiKey) {
        Objects.requireNonNull(apiKey, "apiKey");
        char[] privateCopy = apiKey.clone();
        Arrays.fill(apiKey, '\0');
        return submit(() -> {
            try {
                return coordinator.saveCredential(
                        connectionIdentity,
                        privateCopy);
            } finally {
                Arrays.fill(privateCopy, '\0');
            }
        });
    }

    public CompletableFuture<EndurainCredentialGateway.State> resetCredential(
            String connectionIdentity) {
        return submit(() -> coordinator.resetCredential(connectionIdentity));
    }

    public CompletableFuture<EndurainPublishingSnapshot> statusList() {
        return submit(coordinator::snapshot);
    }

    public CompletableFuture<EndurainActionResult> queuePublish(
            String connectionIdentity,
            String sessionId,
            long revision) {
        return submit(() -> coordinator.queuePublish(
                connectionIdentity,
                sessionId,
                revision));
    }

    public CompletableFuture<EndurainActionResult> manualRetry(
            EndurainDeliveryKey deliveryKey) {
        return submit(() -> coordinator.manualRetry(deliveryKey));
    }

    public CompletableFuture<EndurainActionResult> reconcileRemoteIds(
            EndurainDeliveryKey deliveryKey,
            List<Long> confirmedRemoteIds) {
        List<Long> immutableIds = Collections.unmodifiableList(
                new ArrayList<>(confirmedRemoteIds));
        return submit(() -> coordinator.reconcileRemoteIds(
                deliveryKey,
                immutableIds));
    }

    public CompletableFuture<EndurainActionResult> confirmDuplicateRiskRetry(
            EndurainDeliveryKey deliveryKey) {
        return submit(() ->
                coordinator.confirmDuplicateRiskRetry(deliveryKey));
    }

    public CompletableFuture<EndurainActionResult> repairPreparedArtifact(
            EndurainDeliveryKey deliveryKey) {
        return submit(() ->
                coordinator.repairPreparedArtifact(deliveryKey));
    }

    public CompletableFuture<EndurainActionResult> requestRemoteEdit(
            EndurainDeliveryKey deliveryKey) {
        return submit(() ->
                coordinator.remoteEditUnsupported(deliveryKey));
    }

    public CompletableFuture<EndurainActionResult> requestRemoteDelete(
            EndurainDeliveryKey deliveryKey) {
        return submit(() ->
                coordinator.remoteDeleteUnsupported(deliveryKey));
    }

    public synchronized AutoCloseable observe(
            Executor callbackExecutor,
            Runnable listener) {
        requireOpen();
        AutoCloseable repositoryObservation =
                repository.observeChanges(callbackExecutor, listener);
        AutoCloseable profileObservation =
                profiles.observe(callbackExecutor, listener);
        AutoCloseable publishingObservation =
                changes.observe(callbackExecutor, listener);
        AutoCloseable combined = () -> {
            closeQuietly(repositoryObservation);
            closeQuietly(profileObservation);
            closeQuietly(publishingObservation);
        };
        observations.add(combined);
        return () -> {
            synchronized (EndurainPublishingController.this) {
                if (observations.remove(combined)) {
                    closeQuietly(combined);
                }
            }
        };
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (AutoCloseable observation : observations) {
            closeQuietly(observation);
        }
        observations.clear();
        try {
            worker.execute(repository::close);
        } catch (RuntimeException error) {
            repository.close();
        }
        if (ownsWorker) {
            worker.shutdown();
        }
    }

    private <T> CompletableFuture<T> submit(CheckedSupplier<T> operation) {
        if (closed.get()) {
            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(
                    new IllegalStateException(
                            "Endurain publishing controller is closed"));
            return failed;
        }
        return CompletableFuture.supplyAsync(() -> {
            requireOpen();
            try {
                return operation.get();
            } catch (RuntimeException error) {
                throw error;
            } catch (Exception error) {
                throw new PublishingOperationException(error);
            }
        }, worker);
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException(
                    "Endurain publishing controller is closed");
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    public static final class PublishingOperationException
            extends RuntimeException {
        private PublishingOperationException(Throwable cause) {
            super("Endurain publishing operation failed", cause);
        }
    }
}
