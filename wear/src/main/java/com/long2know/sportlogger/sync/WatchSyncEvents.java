package com.long2know.sportlogger.sync;

import android.content.Context;
import android.content.Intent;

import com.long2know.sportlogger.persistence.watch.WatchActivityRepository;

public final class WatchSyncEvents {
    public static final String ACTION_SYNC_STATE_CHANGED =
            "com.long2know.sportlogger.action.WATCH_SYNC_STATE_CHANGED";
    public static final String ACTION_INCOMING_TOMBSTONE_CHANGED =
            "com.long2know.sportlogger.action.WATCH_INCOMING_TOMBSTONE_CHANGED";
    public static final String ACTION_INCOMING_SYNC_FAILED =
            "com.long2know.sportlogger.action.WATCH_INCOMING_SYNC_FAILED";
    public static final String EXTRA_TRANSFER_ID = "transfer_id";
    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_SESSION_REVISION = "session_revision";
    public static final String EXTRA_DELETE_REVISION = "delete_revision";
    public static final String EXTRA_CANONICAL_STATE = "canonical_state";
    public static final String EXTRA_DISPLAY_STATE = "display_state";
    public static final String EXTRA_ATTEMPT_COUNT = "attempt_count";
    public static final String EXTRA_FAILURE_CODE = "failure_code";
    public static final String EXTRA_ACK_RESULT = "ack_result";
    public static final String EXTRA_REPOSITORY_CHANGED = "repository_changed";
    public static final String EXTRA_ACK_DELIVERED = "ack_delivered";

    public enum DisplayState {
        QUEUED,
        TRANSFERRING,
        SYNCED,
        FAILED
    }

    private WatchSyncEvents() {
    }

    public static DisplayState displayState(String canonicalState) {
        if (WatchActivityRepository.OUTBOX_READY.equals(canonicalState)) {
            return DisplayState.QUEUED;
        }
        if (WatchActivityRepository.OUTBOX_SENDING.equals(canonicalState)
                || WatchActivityRepository.OUTBOX_AWAITING_COMMIT_ACK.equals(
                canonicalState)) {
            return DisplayState.TRANSFERRING;
        }
        if (WatchActivityRepository.OUTBOX_COMMITTED.equals(canonicalState)
                || "TOMBSTONED".equals(canonicalState)) {
            return DisplayState.SYNCED;
        }
        if (WatchActivityRepository.OUTBOX_RETRYABLE.equals(canonicalState)) {
            return DisplayState.FAILED;
        }
        throw new IllegalArgumentException("Unknown canonical outbox state");
    }

    static void stateChanged(
            Context context,
            WatchActivityRepository.OutboxItem item,
            String failureCode) {
        Intent intent = new Intent(ACTION_SYNC_STATE_CHANGED)
                .setPackage(context.getPackageName())
                .putExtra(EXTRA_TRANSFER_ID, item.getTransferId())
                .putExtra(EXTRA_SESSION_ID, item.getSessionId())
                .putExtra(EXTRA_SESSION_REVISION, item.getSessionRevision())
                .putExtra(EXTRA_CANONICAL_STATE, item.getState())
                .putExtra(EXTRA_DISPLAY_STATE, displayState(item.getState()).name())
                .putExtra(EXTRA_ATTEMPT_COUNT, item.getAttemptCount());
        if (failureCode != null) {
            intent.putExtra(EXTRA_FAILURE_CODE, failureCode);
        }
        context.sendBroadcast(intent);
    }

    static void tombstoneStateChanged(
            Context context,
            WatchActivityRepository.TombstoneOutboxItem item,
            String failureCode) {
        Intent intent = new Intent(ACTION_SYNC_STATE_CHANGED)
                .setPackage(context.getPackageName())
                .putExtra(EXTRA_TRANSFER_ID, item.getTransferId())
                .putExtra(EXTRA_SESSION_ID, item.getSessionId())
                .putExtra(EXTRA_DELETE_REVISION, item.getDeleteRevision())
                .putExtra(EXTRA_CANONICAL_STATE, item.getState())
                .putExtra(EXTRA_DISPLAY_STATE, displayState(item.getState()).name())
                .putExtra(EXTRA_ATTEMPT_COUNT, item.getAttemptCount());
        if (failureCode != null) {
            intent.putExtra(EXTRA_FAILURE_CODE, failureCode);
        }
        context.sendBroadcast(intent);
    }

    static void incomingTombstoneChanged(
            Context context,
            CanonicalWatchTombstoneTransferHandler.Result result) {
        Intent intent = new Intent(ACTION_INCOMING_TOMBSTONE_CHANGED)
                .setPackage(context.getPackageName())
                .putExtra(
                        EXTRA_TRANSFER_ID,
                        result.getAcknowledgement().getTransferId())
                .putExtra(
                        EXTRA_SESSION_ID,
                        result.getAcknowledgement().getSessionId())
                .putExtra(
                        EXTRA_DELETE_REVISION,
                        result.getAcknowledgement().getDeleteRevision())
                .putExtra(
                        EXTRA_ACK_RESULT,
                        result.getAcknowledgement().getResult().name())
                .putExtra(EXTRA_REPOSITORY_CHANGED, result.isRepositoryChanged())
                .putExtra(EXTRA_ACK_DELIVERED, result.isAcknowledgementDelivered());
        context.sendBroadcast(intent);
    }

    static void incomingTransferFailed(
            Context context,
            String transferId,
            String failureCode) {
        Intent intent = new Intent(ACTION_INCOMING_SYNC_FAILED)
                .setPackage(context.getPackageName())
                .putExtra(EXTRA_TRANSFER_ID, transferId)
                .putExtra(EXTRA_FAILURE_CODE, failureCode);
        context.sendBroadcast(intent);
    }
}
