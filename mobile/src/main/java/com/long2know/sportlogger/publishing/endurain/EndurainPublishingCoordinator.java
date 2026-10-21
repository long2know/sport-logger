package com.long2know.sportlogger.publishing.endurain;

import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository;
import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository.ConnectorDelivery;
import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository.ConnectorState;
import com.long2know.sportlogger.publishing.endurain.artifact.PreparedTcxArtifactStore;
import com.long2know.sportlogger.publishing.endurain.artifact.PreparedTcxArtifactStore.ArtifactManifest;
import com.long2know.sportlogger.publishing.endurain.artifact.PreparedTcxArtifactStore.PreparedArtifact;
import com.long2know.sportlogger.publishing.endurain.artifact.PreparedTcxArtifactStore.PreparedArtifactException;
import com.long2know.sportlogger.publishing.endurain.artifact.PreparedTcxArtifactStore.Verification;
import com.long2know.sportlogger.publishing.endurain.factory.EndurainActionResult;
import com.long2know.sportlogger.publishing.endurain.factory.EndurainConnectionStatus;
import com.long2know.sportlogger.publishing.endurain.factory.EndurainDeliveryStatus;
import com.long2know.sportlogger.publishing.endurain.factory.EndurainPublishingSnapshot;
import com.long2know.sportlogger.publishing.endurain.profile.EndurainConnectionProfile;
import com.long2know.sportlogger.publishing.endurain.profile.EndurainConnectionProfileStore;
import com.long2know.sportlogger.publishing.endurain.scheduler.EndurainPublishingSchedule;
import com.long2know.sportlogger.security.CredentialStoreException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

public final class EndurainPublishingCoordinator {
    private static final long LEASE_DURATION_MILLIS = 15L * 60L * 1_000L;
    private static final long PRE_TRANSMISSION_RETRY_MILLIS = 60_000L;
    private static final long MAX_RETRY_DELAY_MILLIS =
            7L * 24L * 60L * 60L * 1_000L;
    private static final String LEASE_OWNER = "endurain-publisher-v1";

    private final PhoneActivityRepository repository;
    private final EndurainConnectionProfileStore profiles;
    private final PreparedTcxArtifactStore artifacts;
    private final EndurainCredentialGateway credentials;
    private final EndurainTransportGateway transport;
    private final EndurainPublishingSchedule schedule;
    private final EndurainPublishingChangeSignal changes;
    private final LongSupplier clock;
    private final LongSupplier leaseTokens;

    public EndurainPublishingCoordinator(
            PhoneActivityRepository repository,
            EndurainConnectionProfileStore profiles,
            PreparedTcxArtifactStore artifacts,
            EndurainCredentialGateway credentials,
            EndurainTransportGateway transport,
            EndurainPublishingSchedule schedule,
            EndurainPublishingChangeSignal changes,
            LongSupplier clock,
            LongSupplier leaseTokens) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.schedule = Objects.requireNonNull(schedule, "schedule");
        this.changes = Objects.requireNonNull(changes, "changes");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseTokens = Objects.requireNonNull(leaseTokens, "leaseTokens");
    }

    public EndurainConnectionProfile saveConnection(
            String existingConnectionIdentity,
            String label,
            String baseUrl,
            boolean enabled) {
        EndurainConnectionProfile profile = profiles.save(
                existingConnectionIdentity,
                label,
                baseUrl,
                enabled);
        long now = clock.getAsLong();
        schedule.scheduleRecoveryAt(now);
        if (enabled) {
            schedule.schedulePublishAt(now);
        }
        changes.signal();
        return profile;
    }

    public EndurainConnectionProfile setConnectionEnabled(
            String connectionIdentity,
            boolean enabled) {
        EndurainConnectionProfile profile =
                profiles.setEnabled(connectionIdentity, enabled);
        if (enabled) {
            long now = clock.getAsLong();
            schedule.scheduleRecoveryAt(now);
            schedule.schedulePublishAt(now);
        }
        changes.signal();
        return profile;
    }

    public EndurainCredentialGateway.State saveCredential(
            String connectionIdentity,
            char[] apiKey) throws CredentialStoreException {
        Objects.requireNonNull(apiKey, "apiKey");
        EndurainConnectionProfile profile = requireProfile(connectionIdentity);
        try {
            credentials.store(profile, apiKey);
        } finally {
            Arrays.fill(apiKey, '\0');
        }
        schedule.schedulePublishAt(clock.getAsLong());
        changes.signal();
        return EndurainCredentialGateway.State.AVAILABLE;
    }

    public EndurainCredentialGateway.State resetCredential(
            String connectionIdentity) throws CredentialStoreException {
        EndurainConnectionProfile profile = requireProfile(connectionIdentity);
        credentials.reset(profile);
        changes.signal();
        return EndurainCredentialGateway.State.NOT_CONFIGURED;
    }

    public EndurainActionResult queuePublish(
            String connectionIdentity,
            String sessionId,
            long revision) throws PreparedArtifactException {
        EndurainConnectionProfile profile = requireProfile(connectionIdentity);
        long now = clock.getAsLong();
        schedule.scheduleRecoveryAt(now + LEASE_DURATION_MILLIS);
        PreparedArtifact prepared = artifacts.prepare(
                repository,
                profile,
                sessionId,
                revision,
                now);
        ArtifactManifest manifest = prepared.getManifest();
        repository.prepareConnectorDelivery(
                manifest.getKey().toRepositoryKey(),
                manifest.getCredentialReference(),
                manifest.getArtifactLength(),
                manifest.getArtifactSha256(),
                manifest.getCreatedAtUtcMs());
        schedule.scheduleRecoveryAt(now);
        if (profile.isEnabled()) {
            schedule.schedulePublishAt(now);
        }
        changes.signal();
        return EndurainActionResult.of(
                EndurainActionResult.Code.QUEUED,
                manifest.getKey(),
                profile.isEnabled()
                        ? "Endurain upload is queued"
                        : "Endurain upload is prepared and waits for the connection to be enabled");
    }

    public EndurainActionResult manualRetry(EndurainDeliveryKey key)
            throws PreparedArtifactException {
        ConnectorDelivery delivery = requireDelivery(key);
        long now = clock.getAsLong();
        if (delivery.getState() == ConnectorState.PENDING) {
            Long retryNotBefore = delivery.getRetryNotBeforeUtcMs();
            if (retryNotBefore != null && retryNotBefore > now) {
                return EndurainActionResult.of(
                        EndurainActionResult.Code.RETRY_NOT_DUE,
                        key,
                        "The server-supplied retry time has not elapsed");
            }
            schedule.schedulePublishAt(now);
            changes.signal();
            return EndurainActionResult.of(
                    EndurainActionResult.Code.QUEUED,
                    key,
                    "Endurain upload is queued");
        }
        if (delivery.getState() == ConnectorState.UNKNOWN_DELIVERY
                || delivery.getState() == ConnectorState.DUPLICATE_REVIEW) {
            return EndurainActionResult.of(
                    EndurainActionResult.Code.RECONCILIATION_REQUIRED,
                    key,
                    "Confirm remote IDs or explicitly accept duplicate risk before retrying");
        }
        if (delivery.getState() != ConnectorState.FAILED) {
            return EndurainActionResult.of(
                    EndurainActionResult.Code.INVALID_STATE,
                    key,
                    "This Endurain delivery cannot be retried");
        }
        ArtifactManifest manifest =
                artifacts.loadManifest(PreparedTcxArtifactStore.artifactId(key));
        if (!artifacts.verify(manifest).isReady()) {
            return EndurainActionResult.of(
                    EndurainActionResult.Code.ARTIFACT_REPAIR_REQUIRED,
                    key,
                    "The frozen TCX artifact must be repaired before retry");
        }
        EndurainConnectionProfile profile =
                matchingProfile(manifest);
        if (profile == null
                || credentialState(profile)
                != EndurainCredentialGateway.State.AVAILABLE) {
            return EndurainActionResult.of(
                    EndurainActionResult.Code.CREDENTIAL_REQUIRED,
                    key,
                    "The scoped Endurain API key must be re-entered before retry");
        }
        long leaseToken = nextLeaseToken();
        if (!repository.claimConnectorDelivery(
                key.toRepositoryKey(),
                ConnectorState.FAILED,
                leaseToken,
                LEASE_OWNER,
                now + LEASE_DURATION_MILLIS,
                now)) {
            return EndurainActionResult.of(
                    EndurainActionResult.Code.INVALID_STATE,
                    key,
                    "The Endurain delivery is already being updated");
        }
        repository.transitionConnectorDelivery(
                key.toRepositoryKey(),
                leaseToken,
                ConnectorState.FAILED,
                ConnectorState.PENDING,
                null,
                null,
                null,
                now);
        schedule.schedulePublishAt(now);
        changes.signal();
        return EndurainActionResult.of(
                EndurainActionResult.Code.QUEUED,
                key,
                "Endurain upload is queued");
    }

    public EndurainActionResult reconcileRemoteIds(
            EndurainDeliveryKey key,
            List<Long> remoteIds) {
        repository.reconcileRemoteIds(
                key.toRepositoryKey(),
                remoteIds,
                clock.getAsLong());
        changes.signal();
        return EndurainActionResult.of(
                EndurainActionResult.Code.COMPLETED,
                key,
                "Confirmed Endurain remote IDs were recorded");
    }

    public EndurainActionResult confirmDuplicateRiskRetry(
            EndurainDeliveryKey key) throws PreparedArtifactException {
        ConnectorDelivery delivery = requireDelivery(key);
        if (delivery.getState() != ConnectorState.UNKNOWN_DELIVERY
                && delivery.getState() != ConnectorState.DUPLICATE_REVIEW) {
            return EndurainActionResult.of(
                    EndurainActionResult.Code.INVALID_STATE,
                    key,
                    "This delivery is not waiting for duplicate-risk confirmation");
        }
        ArtifactManifest manifest =
                artifacts.loadManifest(PreparedTcxArtifactStore.artifactId(key));
        if (!artifacts.verify(manifest).isReady()) {
            return EndurainActionResult.of(
                    EndurainActionResult.Code.ARTIFACT_REPAIR_REQUIRED,
                    key,
                    "The frozen TCX artifact must be repaired before retry");
        }
        EndurainConnectionProfile profile = matchingProfile(manifest);
        if (profile == null
                || credentialState(profile)
                != EndurainCredentialGateway.State.AVAILABLE) {
            return EndurainActionResult.of(
                    EndurainActionResult.Code.CREDENTIAL_REQUIRED,
                    key,
                    "The scoped Endurain API key must be re-entered before retry");
        }
        repository.authorizeDuplicateRiskRetry(
                key.toRepositoryKey(),
                clock.getAsLong());
        schedule.schedulePublishAt(clock.getAsLong());
        changes.signal();
        return EndurainActionResult.of(
                EndurainActionResult.Code.QUEUED,
                key,
                "Duplicate-risk retry was explicitly authorized");
    }

    public EndurainActionResult repairPreparedArtifact(
            EndurainDeliveryKey key) throws PreparedArtifactException {
        ConnectorDelivery delivery = requireDelivery(key);
        if (delivery.getState() != ConnectorState.FAILED) {
            return EndurainActionResult.of(
                    EndurainActionResult.Code.INVALID_STATE,
                    key,
                    "Prepared artifact repair is only available after a visible failure");
        }
        artifacts.repair(
                repository,
                PreparedTcxArtifactStore.artifactId(key));
        changes.signal();
        return EndurainActionResult.of(
                EndurainActionResult.Code.COMPLETED,
                key,
                "The frozen TCX artifact was restored and verified");
    }

    public EndurainActionResult remoteEditUnsupported(
            EndurainDeliveryKey key) {
        requireDelivery(key);
        return EndurainActionResult.of(
                EndurainActionResult.Code.UNSUPPORTED_REMOTE_EDIT,
                key,
                "Endurain remote activity editing is not implemented");
    }

    public EndurainActionResult remoteDeleteUnsupported(
            EndurainDeliveryKey key) {
        requireDelivery(key);
        repository.markConnectorDeleteUnsupported(
                key.toRepositoryKey(),
                clock.getAsLong());
        changes.signal();
        return EndurainActionResult.of(
                EndurainActionResult.Code.UNSUPPORTED_REMOTE_DELETE,
                key,
                "Endurain remote activity deletion is not implemented; ownership is retained");
    }

    public EndurainPublishingSnapshot snapshot()
            throws PreparedArtifactException {
        List<EndurainConnectionStatus> connectionStatuses =
                new ArrayList<>();
        for (EndurainConnectionProfile profile : profiles.list()) {
            connectionStatuses.add(new EndurainConnectionStatus(
                    profile,
                    credentialState(profile)));
        }
        List<EndurainDeliveryStatus> deliveryStatuses =
                new ArrayList<>();
        for (ArtifactManifest manifest : artifacts.listManifests()) {
            ConnectorDelivery delivery =
                    repository.connectorDelivery(
                            manifest.getKey().toRepositoryKey());
            Verification verification = artifacts.verify(manifest);
            EndurainConnectionProfile profile = matchingProfile(manifest);
            EndurainCredentialGateway.State credentialState =
                    profile == null
                            ? EndurainCredentialGateway.State.NOT_CONFIGURED
                            : credentialState(profile);
            deliveryStatuses.add(new EndurainDeliveryStatus(
                    manifest.getArtifactId(),
                    manifest.getKey(),
                    delivery == null
                            ? EndurainDeliveryStatus.State.PREPARED
                            : displayState(delivery.getState()),
                    EndurainDeliveryStatus.ArtifactState.valueOf(
                            verification.getState().name()),
                    credentialState,
                    delivery == null
                            ? Collections.emptyList()
                            : delivery.getRemoteIds(),
                    delivery == null
                            ? null
                            : delivery.getRetryNotBeforeUtcMs(),
                    delivery == null
                            ? null
                            : delivery.getLastFailureCode(),
                    delivery == null
                            ? null
                            : delivery.getLastFailureMessage(),
                    delivery != null && delivery.isDeleteUnsupported()));
        }
        return new EndurainPublishingSnapshot(
                connectionStatuses,
                deliveryStatuses);
    }

    public RunResult runDue(boolean allowNetwork, int limit)
            throws PreparedArtifactException {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException(
                    "Endurain publishing limit must be between 1 and 100");
        }
        int inspected = 0;
        int delivered = 0;
        int failed = 0;
        int unknown = 0;
        boolean changed = false;
        Long nextPublishAt = null;
        for (ArtifactManifest manifest : artifacts.listManifests()) {
            if (inspected == limit) {
                nextPublishAt = earlier(nextPublishAt, clock.getAsLong());
                break;
            }
            inspected++;
            PhoneActivityRepository.ConnectorKey repositoryKey =
                    manifest.getKey().toRepositoryKey();
            ConnectorDelivery delivery =
                    repository.connectorDelivery(repositoryKey);
            if (delivery == null) {
                repository.prepareConnectorDelivery(
                        repositoryKey,
                        manifest.getCredentialReference(),
                        manifest.getArtifactLength(),
                        manifest.getArtifactSha256(),
                        manifest.getCreatedAtUtcMs());
                changed = true;
            }
            if (repository.recoverExpiredConnectorClaimAsUnknown(
                    repositoryKey,
                    clock.getAsLong())) {
                unknown++;
                changed = true;
            }
            delivery = repository.connectorDelivery(repositoryKey);
            if (delivery.getState() != ConnectorState.PENDING) {
                continue;
            }
            long now = clock.getAsLong();
            Long retryNotBefore = delivery.getRetryNotBeforeUtcMs();
            if (retryNotBefore != null && retryNotBefore > now) {
                nextPublishAt = earlier(nextPublishAt, retryNotBefore);
                continue;
            }
            EndurainConnectionProfile profile = matchingProfile(manifest);
            if (profile == null) {
                if (failBeforeNetwork(
                        manifest.getKey(),
                        "CONNECTION_NOT_CONFIGURED",
                        "The prepared Endurain connection is not configured",
                        now)) {
                    failed++;
                    changed = true;
                }
                continue;
            }
            if (!profile.isEnabled()) {
                continue;
            }
            if (!allowNetwork) {
                nextPublishAt = earlier(nextPublishAt, now);
                continue;
            }
            Verification verification = artifacts.verify(manifest);
            if (!verification.isReady()) {
                if (failBeforeNetwork(
                        manifest.getKey(),
                        "ARTIFACT_" + verification.getState().name(),
                        "The frozen TCX artifact failed verification",
                        now)) {
                    failed++;
                    changed = true;
                }
                continue;
            }
            try (EndurainCredentialGateway.Access access =
                         credentials.load(profile)) {
                if (access.getState()
                        != EndurainCredentialGateway.State.AVAILABLE) {
                    if (failBeforeNetwork(
                            manifest.getKey(),
                            "CREDENTIAL_" + access.getState().name(),
                            "The scoped Endurain credential is unavailable",
                            now)) {
                        failed++;
                        changed = true;
                    }
                    continue;
                }
                long leaseToken = nextLeaseToken();
                long leaseExpiresAt = now + LEASE_DURATION_MILLIS;
                schedule.scheduleRecoveryAt(leaseExpiresAt);
                if (!repository.claimConnectorDelivery(
                        repositoryKey,
                        ConnectorState.PENDING,
                        leaseToken,
                        LEASE_OWNER,
                        leaseExpiresAt,
                        now)) {
                    continue;
                }
                Verification claimedVerification = artifacts.verify(manifest);
                if (!claimedVerification.isReady()) {
                    repository.transitionConnectorDelivery(
                            repositoryKey,
                            leaseToken,
                            ConnectorState.PENDING,
                            ConnectorState.FAILED,
                            null,
                            "ARTIFACT_" + claimedVerification.getState().name(),
                            "The frozen TCX artifact changed before transmission",
                            now);
                    failed++;
                    changed = true;
                    continue;
                }
                EndurainTransportGateway.UploadOutcome outcome;
                try {
                    outcome = transport.upload(
                            profile.toConnectionConfig(),
                            claimedVerification.getUploadArtifact(),
                            access.getCredential());
                } catch (RuntimeException error) {
                    repository.transitionConnectorDelivery(
                            repositoryKey,
                            leaseToken,
                            ConnectorState.PENDING,
                            ConnectorState.UNKNOWN_DELIVERY,
                            null,
                            "TRANSPORT_EXCEPTION",
                            "Transport stopped without trustworthy delivery evidence",
                            clock.getAsLong());
                    unknown++;
                    changed = true;
                    continue;
                }
                long completedAt = clock.getAsLong();
                switch (outcome.getState()) {
                    case DELIVERED:
                        repository.recordConnectorSuccess(
                                repositoryKey,
                                leaseToken,
                                outcome.getRemoteIds(),
                                completedAt);
                        delivered++;
                        changed = true;
                        break;
                    case RETRYABLE:
                        long retryAt =
                                completedAt + PRE_TRANSMISSION_RETRY_MILLIS;
                        repository.transitionConnectorDelivery(
                                repositoryKey,
                                leaseToken,
                                ConnectorState.PENDING,
                                ConnectorState.PENDING,
                                retryAt,
                                "PRE_TRANSMISSION_RETRYABLE",
                                "No request body was transmitted",
                                completedAt);
                        nextPublishAt = earlier(nextPublishAt, retryAt);
                        changed = true;
                        break;
                    case RATE_LIMITED:
                        long rateLimitAt = completedAt
                                + boundedRetryDelay(outcome.getRetryAfter());
                        repository.transitionConnectorDelivery(
                                repositoryKey,
                                leaseToken,
                                ConnectorState.PENDING,
                                ConnectorState.PENDING,
                                rateLimitAt,
                                "RATE_LIMITED",
                                "Endurain supplied a bounded retry delay",
                                completedAt);
                        nextPublishAt = earlier(nextPublishAt, rateLimitAt);
                        changed = true;
                        break;
                    case UNKNOWN_DELIVERY:
                        repository.transitionConnectorDelivery(
                                repositoryKey,
                                leaseToken,
                                ConnectorState.PENDING,
                                ConnectorState.UNKNOWN_DELIVERY,
                                null,
                                "UNKNOWN_" + outcome.getReason(),
                                "Delivery requires explicit reconciliation or duplicate-risk confirmation",
                                completedAt);
                        unknown++;
                        changed = true;
                        break;
                    case AUTHENTICATION_REQUIRED:
                    case VALIDATION_REJECTED:
                    case PERMANENT_FAILURE:
                        repository.transitionConnectorDelivery(
                                repositoryKey,
                                leaseToken,
                                ConnectorState.PENDING,
                                ConnectorState.FAILED,
                                null,
                                outcome.getState().name()
                                        + "_"
                                        + outcome.getReason(),
                                "Endurain rejected the prepared upload",
                                completedAt);
                        failed++;
                        changed = true;
                        break;
                    default:
                        throw new AssertionError(outcome.getState());
                }
            }
        }
        if (changed) {
            changes.signal();
        }
        return new RunResult(
                inspected,
                delivered,
                failed,
                unknown,
                nextPublishAt);
    }

    private boolean failBeforeNetwork(
            EndurainDeliveryKey key,
            String failureCode,
            String failureMessage,
            long nowUtcMs) {
        long leaseToken = nextLeaseToken();
        long leaseExpiresAt = nowUtcMs + LEASE_DURATION_MILLIS;
        schedule.scheduleRecoveryAt(leaseExpiresAt);
        if (!repository.claimConnectorDelivery(
                key.toRepositoryKey(),
                ConnectorState.PENDING,
                leaseToken,
                LEASE_OWNER,
                leaseExpiresAt,
                nowUtcMs)) {
            return false;
        }
        repository.transitionConnectorDelivery(
                key.toRepositoryKey(),
                leaseToken,
                ConnectorState.PENDING,
                ConnectorState.FAILED,
                null,
                failureCode,
                failureMessage,
                nowUtcMs);
        return true;
    }

    private EndurainConnectionProfile matchingProfile(
            ArtifactManifest manifest) {
        EndurainConnectionProfile profile =
                profiles.get(manifest.getKey().getConnectionIdentity());
        if (profile == null
                || !profile.getNormalizedBaseUrl().equals(
                manifest.getKey().getNormalizedBaseUrl())
                || !profile.getCredentialReference().equals(
                manifest.getCredentialReference())) {
            return null;
        }
        return profile;
    }

    private EndurainConnectionProfile requireProfile(
            String connectionIdentity) {
        EndurainConnectionProfile profile = profiles.get(connectionIdentity);
        if (profile == null) {
            throw new IllegalArgumentException(
                    "Endurain connection does not exist");
        }
        return profile;
    }

    private ConnectorDelivery requireDelivery(EndurainDeliveryKey key) {
        Objects.requireNonNull(key, "key");
        ConnectorDelivery delivery =
                repository.connectorDelivery(key.toRepositoryKey());
        if (delivery == null) {
            throw new IllegalArgumentException(
                    "Endurain delivery does not exist");
        }
        return delivery;
    }

    private EndurainCredentialGateway.State credentialState(
            EndurainConnectionProfile profile) {
        try (EndurainCredentialGateway.Access access =
                     credentials.load(profile)) {
            return access.getState();
        }
    }

    private long nextLeaseToken() {
        long value = leaseTokens.getAsLong();
        if (value == 0L) {
            value = 1L;
        }
        return value;
    }

    private static long boundedRetryDelay(Duration retryAfter) {
        if (retryAfter == null) {
            return PRE_TRANSMISSION_RETRY_MILLIS;
        }
        long millis;
        try {
            millis = retryAfter.toMillis();
        } catch (ArithmeticException error) {
            millis = MAX_RETRY_DELAY_MILLIS;
        }
        return Math.max(
                1_000L,
                Math.min(MAX_RETRY_DELAY_MILLIS, millis));
    }

    private static Long earlier(Long current, long candidate) {
        return current == null || candidate < current
                ? candidate
                : current;
    }

    private static EndurainDeliveryStatus.State displayState(
            ConnectorState state) {
        switch (state) {
            case DELIVERED:
                return EndurainDeliveryStatus.State.UPLOADED;
            case PENDING:
                return EndurainDeliveryStatus.State.PENDING;
            case UNKNOWN_DELIVERY:
                return EndurainDeliveryStatus.State.UNKNOWN_DELIVERY;
            case DUPLICATE_REVIEW:
                return EndurainDeliveryStatus.State.DUPLICATE_REVIEW;
            case FAILED:
                return EndurainDeliveryStatus.State.FAILED;
            default:
                throw new AssertionError(state);
        }
    }

    public static final class RunResult {
        private final int inspectedCount;
        private final int deliveredCount;
        private final int failedCount;
        private final int unknownCount;
        private final Long nextPublishAtUtcMs;

        private RunResult(
                int inspectedCount,
                int deliveredCount,
                int failedCount,
                int unknownCount,
                Long nextPublishAtUtcMs) {
            this.inspectedCount = inspectedCount;
            this.deliveredCount = deliveredCount;
            this.failedCount = failedCount;
            this.unknownCount = unknownCount;
            this.nextPublishAtUtcMs = nextPublishAtUtcMs;
        }

        public int getInspectedCount() {
            return inspectedCount;
        }

        public int getDeliveredCount() {
            return deliveredCount;
        }

        public int getFailedCount() {
            return failedCount;
        }

        public int getUnknownCount() {
            return unknownCount;
        }

        public Long getNextPublishAtUtcMs() {
            return nextPublishAtUtcMs;
        }
    }

    public static LongSupplier monotonicLeaseTokens() {
        AtomicLong values = new AtomicLong(System.nanoTime());
        return () -> {
            long value = values.incrementAndGet();
            return value == 0L ? values.incrementAndGet() : value;
        };
    }
}
