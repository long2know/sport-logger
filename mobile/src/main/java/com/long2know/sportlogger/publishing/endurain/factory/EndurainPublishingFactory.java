package com.long2know.sportlogger.publishing.endurain.factory;

import android.content.Context;

import com.long2know.sportlogger.connector.endurain.EndurainHttpsTransport;
import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository;
import com.long2know.sportlogger.publishing.endurain.EndurainPublishingCoordinator;
import com.long2know.sportlogger.publishing.endurain.HttpsEndurainTransportGateway;
import com.long2know.sportlogger.publishing.endurain.SecureEndurainCredentialGateway;
import com.long2know.sportlogger.publishing.endurain.artifact.PreparedTcxArtifactStore;
import com.long2know.sportlogger.publishing.endurain.profile.EndurainConnectionProfileStore;
import com.long2know.sportlogger.publishing.endurain.scheduler.EndurainPublishingScheduler;
import com.long2know.sportlogger.security.EndurainCredentialStore;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public final class EndurainPublishingFactory {
    private EndurainPublishingFactory() {
    }

    public static CompletableFuture<EndurainPublishingController> create(
            Context context) {
        Context applicationContext =
                Objects.requireNonNull(context, "context").getApplicationContext();
        ExecutorService worker =
                Executors.newSingleThreadExecutor(new PublishingThreadFactory());
        CompletableFuture<EndurainPublishingController> result =
                CompletableFuture.supplyAsync(
                        () -> createBlocking(
                                applicationContext,
                                worker,
                                true,
                                true),
                        worker);
        result.whenComplete((controller, error) -> {
            if (error != null) {
                worker.shutdown();
            }
        });
        return result;
    }

    public static CompletableFuture<EndurainPublishingController> create(
            Context context,
            ExecutorService worker) {
        Context applicationContext =
                Objects.requireNonNull(context, "context").getApplicationContext();
        Objects.requireNonNull(worker, "worker");
        return CompletableFuture.supplyAsync(
                () -> createBlocking(
                        applicationContext,
                        worker,
                        false,
                        true),
                worker);
    }

    public static EndurainPublishingCoordinator.RunResult runScheduled(
            Context context,
            boolean allowNetwork) throws Exception {
        Context applicationContext =
                Objects.requireNonNull(context, "context").getApplicationContext();
        try (PhoneActivityRepository repository =
                     PhoneActivityRepository.open(applicationContext)) {
            EndurainPublishingCoordinator coordinator = coordinator(
                    applicationContext,
                    repository);
            return coordinator.runDue(allowNetwork, 25);
        }
    }

    private static EndurainPublishingController createBlocking(
            Context context,
            ExecutorService worker,
            boolean ownsWorker,
            boolean scheduleStartupRecovery) {
        PhoneActivityRepository repository =
                PhoneActivityRepository.open(context);
        try {
            EndurainConnectionProfileStore profiles =
                    EndurainConnectionProfileStore.forApplication(context);
            EndurainPublishingCoordinator coordinator = coordinator(
                    context,
                    repository,
                    profiles);
            if (scheduleStartupRecovery) {
                EndurainPublishingScheduler scheduler =
                        new EndurainPublishingScheduler(context);
                long now = System.currentTimeMillis();
                scheduler.scheduleRecoveryAt(now);
                scheduler.schedulePublishAt(now);
            }
            return new EndurainPublishingController(
                    repository,
                    profiles,
                    coordinator,
                    worker,
                    ownsWorker);
        } catch (RuntimeException error) {
            repository.close();
            throw error;
        }
    }

    private static EndurainPublishingCoordinator coordinator(
            Context context,
            PhoneActivityRepository repository) {
        return coordinator(
                context,
                repository,
                EndurainConnectionProfileStore.forApplication(context));
    }

    private static EndurainPublishingCoordinator coordinator(
            Context context,
            PhoneActivityRepository repository,
            EndurainConnectionProfileStore profiles) {
        EndurainPublishingScheduler scheduler =
                new EndurainPublishingScheduler(context);
        return new EndurainPublishingCoordinator(
                repository,
                profiles,
                PreparedTcxArtifactStore.forApplication(context),
                new SecureEndurainCredentialGateway(
                        EndurainCredentialStore.forApplication(context)),
                new HttpsEndurainTransportGateway(
                        new EndurainHttpsTransport()),
                scheduler,
                System::currentTimeMillis,
                EndurainPublishingCoordinator.monotonicLeaseTokens());
    }

    private static final class PublishingThreadFactory
            implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(
                    runnable,
                    "sportlogger-endurain-publishing");
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        }
    }
}
