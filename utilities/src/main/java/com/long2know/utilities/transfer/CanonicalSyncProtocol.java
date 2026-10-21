package com.long2know.utilities.transfer;

import com.long2know.utilities.contract.CanonicalIds;

public final class CanonicalSyncProtocol {
    public static final String PHONE_RECEIVER_CAPABILITY =
            "sportlogger_phone_activity_sync_v1";
    public static final String WATCH_RECEIVER_CAPABILITY =
            "sportlogger_watch_tombstone_sync_v1";
    public static final String ACTIVITY_CHANNEL_ROOT =
            "/sportlogger/sync/v1/activity";
    public static final String TOMBSTONE_CHANNEL_ROOT =
            "/sportlogger/sync/v1/tombstone";
    public static final String COMMIT_ACK_PATH =
            "/sportlogger/sync/v1/activity-commit-ack";

    private CanonicalSyncProtocol() {
    }

    public static String activityChannelPath(String transferId) {
        return ACTIVITY_CHANNEL_ROOT
                + "/"
                + CanonicalIds.requireUuid(transferId, "Transfer identifier");
    }

    public static boolean isActivityChannelPath(String path) {
        return path != null && path.startsWith(ACTIVITY_CHANNEL_ROOT + "/");
    }

    public static String transferIdFromActivityChannelPath(String path) {
        return transferIdFromPath(
                path,
                ACTIVITY_CHANNEL_ROOT,
                "Unexpected canonical activity channel path");
    }

    public static String tombstoneChannelPath(String transferId) {
        return TOMBSTONE_CHANNEL_ROOT
                + "/"
                + CanonicalIds.requireUuid(transferId, "Transfer identifier");
    }

    public static boolean isTombstoneChannelPath(String path) {
        return path != null && path.startsWith(TOMBSTONE_CHANNEL_ROOT + "/");
    }

    public static String transferIdFromTombstoneChannelPath(String path) {
        return transferIdFromPath(
                path,
                TOMBSTONE_CHANNEL_ROOT,
                "Unexpected canonical tombstone channel path");
    }

    private static String transferIdFromPath(
            String path,
            String root,
            String errorMessage) {
        if (path == null || !path.startsWith(root + "/")) {
            throw new IllegalArgumentException(errorMessage);
        }
        String transferId = path.substring(root.length() + 1);
        return CanonicalIds.requireUuid(transferId, "Transfer identifier");
    }
}
