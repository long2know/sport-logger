package com.long2know.sportlogger.transfer;

import android.content.Context;
import android.content.Intent;

import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository;
import com.long2know.utilities.contract.proto.CommitAck;

public final class PhoneSyncEvents {
    public static final String ACTION_CANONICAL_ACTIVITY_CHANGED =
            "com.long2know.sportlogger.action.CANONICAL_ACTIVITY_CHANGED";
    public static final String ACTION_CANONICAL_SYNC_FAILED =
            "com.long2know.sportlogger.action.CANONICAL_SYNC_FAILED";
    public static final String ACTION_TOMBSTONE_SYNC_STATE_CHANGED =
            "com.long2know.sportlogger.action.PHONE_TOMBSTONE_SYNC_STATE_CHANGED";
    public static final String EXTRA_TRANSFER_ID = "transfer_id";
    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_ACCEPTED_REVISION = "accepted_revision";
    public static final String EXTRA_DELETE_REVISION = "delete_revision";
    public static final String EXTRA_ACK_RESULT = "ack_result";
    public static final String EXTRA_REPOSITORY_CHANGED = "repository_changed";
    public static final String EXTRA_ACK_DELIVERED = "ack_delivered";
    public static final String EXTRA_ERROR_CODE = "error_code";
    public static final String EXTRA_CANONICAL_STATE = "canonical_state";
    public static final String EXTRA_DISPLAY_STATE = "display_state";
    public static final String EXTRA_ATTEMPT_COUNT = "attempt_count";

    public enum DisplayState {
        QUEUED,
        TRANSFERRING,
        SYNCED,
        FAILED
    }

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

    static void tombstoneChanged(
            Context context,
            CanonicalTombstoneTransferHandler.Result result) {
        CommitAck acknowledgement = result.getAcknowledgement();
        Intent intent = new Intent(ACTION_CANONICAL_ACTIVITY_CHANGED)
                .setPackage(context.getPackageName())
                .putExtra(EXTRA_TRANSFER_ID, acknowledgement.getTransferId())
                .putExtra(EXTRA_SESSION_ID, acknowledgement.getSessionId())
                .putExtra(
                        EXTRA_ACCEPTED_REVISION,
                        acknowledgement.getAcceptedSessionRevision())
                .putExtra(
                        EXTRA_DELETE_REVISION,
                        acknowledgement.getDeleteRevision())
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

    public static DisplayState displayState(String canonicalState) {
        if (PhoneActivityRepository.TOMBSTONE_READY.equals(canonicalState)) {
            return DisplayState.QUEUED;
        }
        if (PhoneActivityRepository.TOMBSTONE_SENDING.equals(canonicalState)
                || PhoneActivityRepository.TOMBSTONE_AWAITING_COMMIT_ACK.equals(
                canonicalState)) {
            return DisplayState.TRANSFERRING;
        }
        if (PhoneActivityRepository.TOMBSTONE_COMMITTED.equals(canonicalState)) {
            return DisplayState.SYNCED;
        }
        if (PhoneActivityRepository.TOMBSTONE_RETRYABLE.equals(canonicalState)) {
            return DisplayState.FAILED;
        }
        throw new IllegalArgumentException("Unknown canonical phone tombstone state");
    }

    static void outboundTombstoneChanged(
            Context context,
            PhoneActivityRepository.TombstoneOutboxItem item,
            String failureCode) {
        Intent intent = new Intent(ACTION_TOMBSTONE_SYNC_STATE_CHANGED)
                .setPackage(context.getPackageName())
                .putExtra(EXTRA_TRANSFER_ID, item.getTransferId())
                .putExtra(EXTRA_SESSION_ID, item.getSessionId())
                .putExtra(EXTRA_DELETE_REVISION, item.getDeleteRevision())
                .putExtra(EXTRA_CANONICAL_STATE, item.getState())
                .putExtra(EXTRA_DISPLAY_STATE, displayState(item.getState()).name())
                .putExtra(EXTRA_ATTEMPT_COUNT, item.getAttemptCount());
        if (failureCode != null) {
            intent.putExtra(EXTRA_ERROR_CODE, failureCode);
        }
        context.sendBroadcast(intent);
    }

}
