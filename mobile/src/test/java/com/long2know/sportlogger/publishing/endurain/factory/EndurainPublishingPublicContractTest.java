package com.long2know.sportlogger.publishing.endurain.factory;

import android.content.Context;

import com.long2know.sportlogger.publishing.endurain.EndurainDeliveryKey;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class EndurainPublishingPublicContractTest {
    @Test
    public void factoryAndControllerExposeTypedAsyncUiBoundary()
            throws Exception {
        assertEquals(
                CompletableFuture.class,
                EndurainPublishingFactory.class
                        .getMethod("create", Context.class)
                        .getReturnType());
        assertEquals(
                CompletableFuture.class,
                EndurainPublishingFactory.class
                        .getMethod(
                                "create",
                                Context.class,
                                ExecutorService.class)
                        .getReturnType());
        assertFuture("saveConnection",
                String.class, String.class, String.class, boolean.class);
        assertFuture("setConnectionEnabled", String.class, boolean.class);
        assertFuture("saveCredential", String.class, char[].class);
        assertFuture("resetCredential", String.class);
        assertFuture("statusList");
        assertFuture("queuePublish", String.class, String.class, long.class);
        assertFuture("manualRetry", EndurainDeliveryKey.class);
        assertFuture(
                "reconcileRemoteIds",
                EndurainDeliveryKey.class,
                List.class);
        assertFuture(
                "confirmDuplicateRiskRetry",
                EndurainDeliveryKey.class);
        assertFuture("repairPreparedArtifact", EndurainDeliveryKey.class);
        assertFuture("requestRemoteEdit", EndurainDeliveryKey.class);
        assertFuture("requestRemoteDelete", EndurainDeliveryKey.class);
        assertEquals(
                AutoCloseable.class,
                EndurainPublishingController.class
                        .getMethod("observe", Executor.class, Runnable.class)
                        .getReturnType());
    }

    @Test
    public void uiContractDoesNotClaimRemoteMutationSupport() {
        EndurainDeliveryStatus status = new EndurainDeliveryStatus(
                "artifact",
                new EndurainDeliveryKey(
                        "f37dca54-d36f-4de2-b62e-b36e8d3f32b2",
                        1L,
                        "https://endurain.example",
                        "connection"),
                EndurainDeliveryStatus.State.UPLOADED,
                EndurainDeliveryStatus.ArtifactState.READY,
                com.long2know.sportlogger.publishing.endurain
                        .EndurainCredentialGateway.State.AVAILABLE,
                List.of(17L),
                null,
                null,
                null,
                true);

        assertFalse(status.isRemoteEditSupported());
        assertFalse(status.isRemoteDeleteSupported());
        assertEquals(List.of(17L), status.getRemoteIds());
    }

    private static void assertFuture(
            String method,
            Class<?>... parameterTypes) throws Exception {
        assertEquals(
                CompletableFuture.class,
                EndurainPublishingController.class
                        .getMethod(method, parameterTypes)
                        .getReturnType());
    }
}
