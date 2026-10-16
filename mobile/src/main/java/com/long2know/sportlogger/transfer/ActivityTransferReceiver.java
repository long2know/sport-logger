package com.long2know.sportlogger.transfer;

import com.long2know.utilities.models.SportActivity;
import com.long2know.utilities.transfer.ActivityTransferProtocol;

import java.util.Objects;

public final class ActivityTransferReceiver {
    public interface Acknowledger {
        void acknowledge(
                String acknowledgementPath,
                String transferId,
                String sourceUri) throws Exception;
    }

    private final String activityPath;
    private final PhoneActivityStore store;
    private final Acknowledger acknowledger;

    public ActivityTransferReceiver(
            String activityPath,
            PhoneActivityStore store,
            Acknowledger acknowledger) {
        this.activityPath = Objects.requireNonNull(activityPath);
        this.store = Objects.requireNonNull(store);
        this.acknowledger = Objects.requireNonNull(acknowledger);
    }

    public SportActivity receive(
            String itemPath,
            String sourceNodeId,
            String sourceUri,
            String declaredTransferId,
            Long declaredActivityId,
            String declaredPayloadSha256,
            byte[] payload) throws Exception {
        if (!ActivityTransferProtocol.isActivityPath(activityPath, itemPath)) {
            throw new IllegalArgumentException("Unexpected activity transfer path");
        }
        SportActivity activity = SportActivity.deserialize(Objects.requireNonNull(payload));
        String expectedTransferId = ActivityTransferProtocol.transferId(activity, payload);
        String pathTransferId =
                ActivityTransferProtocol.transferIdFromActivityPath(activityPath, itemPath);
        verifyOptional("path", pathTransferId, expectedTransferId);
        verifyOptional("payload", declaredTransferId, expectedTransferId);
        if (declaredActivityId != null && declaredActivityId != activity.Id) {
            throw new IllegalStateException("Activity identifier does not match payload");
        }
        verifyOptional(
                "checksum",
                declaredPayloadSha256,
                ActivityTransferProtocol.payloadSha256(payload));

        store.persist(sourceNodeId, expectedTransferId, payload);
        PhoneActivityStore.PendingAcknowledgement pending =
                store.prepareAcknowledgement(
                        sourceNodeId,
                        expectedTransferId,
                        ActivityTransferProtocol.acknowledgementPath(
                                activityPath,
                                sourceNodeId,
                                expectedTransferId),
                        sourceUri);
        acknowledger.acknowledge(
                pending.getAcknowledgementPath(),
                pending.getTransferId(),
                pending.getSourceUri());
        store.acknowledgementQueued(pending);
        return activity;
    }

    private static void verifyOptional(String label, String actual, String expected) {
        if (actual != null && !actual.equals(expected)) {
            throw new IllegalStateException(
                    "Activity transfer " + label + " does not match payload");
        }
    }
}
