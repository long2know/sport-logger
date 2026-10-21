package com.long2know.sportlogger.transfer;

import android.content.Context;
import android.content.Intent;

import com.long2know.utilities.contract.proto.CommitAck;

public final class PhoneSyncEvents {
    public static final String ACTION_CANONICAL_ACTIVITY_CHANGED =
            "com.long2know.sportlogger.action.CANONICAL_ACTIVITY_CHANGED";
    public static final String ACTION_CANONICAL_SYNC_FAILED =
            "com.long2know.sportlogger.action.CANONICAL_SYNC_FAILED";
    public static final String EXTRA_TRANSFER_ID = "transfer_id";
    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_ACCEPTED_REVISION = "accepted_revision";
    public static final String EXTRA_ACK_RESULT = "ack_result";
    public static final String EXTRA_REPOSITORY_CHANGED = "repository_changed";
    public static final String EXTRA_ACK_DELIVERED = "ack_delivered";
    public static final String EXTRA_ERROR_CODE = "error_code";

    private PhoneSyncEvents() {
    }

    static void activityChanged(
            Context context,
            CanonicalActivityTransferHandler.Result result) {
        CommitAck acknowledgement = result.getAcknowledgement();
        Intent intent = new Intent(ACTION_CANONICAL_ACTIVITY_CHANGED)
                .setPackage(context.getPackageName())
                .putExtra(EXTRA_TRANSFER_ID, acknowledgement.getTransferId())
                .putExtra(EXTRA_SESSION_ID, acknowledgement.getSessionId())
                .putExtra(
                        EXTRA_ACCEPTED_REVISION,
                        acknowledgement.getAcceptedSessionRevision())
                .putExtra(EXTRA_ACK_RESULT, acknowledgement.getResult().name())
                .putExtra(EXTRA_REPOSITORY_CHANGED, result.isRepositoryChanged())
                .putExtra(EXTRA_ACK_DELIVERED, result.isAcknowledgementDelivered());
        context.sendBroadcast(intent);
    }

    static void transferFailed(
            Context context,
            String transferId,
            String errorCode) {
        Intent intent = new Intent(ACTION_CANONICAL_SYNC_FAILED)
                .setPackage(context.getPackageName())
                .putExtra(EXTRA_TRANSFER_ID, transferId)
                .putExtra(EXTRA_ERROR_CODE, errorCode);
        context.sendBroadcast(intent);
    }
}
