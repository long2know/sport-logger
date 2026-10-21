package com.long2know.utilities.transfer;

import com.long2know.utilities.contract.CanonicalContractException;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CanonicalSyncProtocolTest {
    private static final String TRANSFER_ID = "2433cf55-34ff-47f0-a9ea-ed9331d9196c";

    @Test
    public void activityChannelPathRoundTripsCanonicalTransferIdentity() {
        assertEquals(
                "sportlogger_phone_activity_sync_v1",
                CanonicalSyncProtocol.PHONE_RECEIVER_CAPABILITY);
        assertEquals(
                "sportlogger_watch_tombstone_sync_v1",
                CanonicalSyncProtocol.WATCH_RECEIVER_CAPABILITY);
        String path = CanonicalSyncProtocol.activityChannelPath(TRANSFER_ID);

        assertTrue(CanonicalSyncProtocol.isActivityChannelPath(path));
        assertEquals(
                TRANSFER_ID,
                CanonicalSyncProtocol.transferIdFromActivityChannelPath(path));
        assertFalse(CanonicalSyncProtocol.isActivityChannelPath(
                CanonicalSyncProtocol.COMMIT_ACK_PATH));

        String tombstonePath =
                CanonicalSyncProtocol.tombstoneChannelPath(TRANSFER_ID);
        assertTrue(CanonicalSyncProtocol.isTombstoneChannelPath(tombstonePath));
        assertEquals(
                TRANSFER_ID,
                CanonicalSyncProtocol.transferIdFromTombstoneChannelPath(
                        tombstonePath));
        assertFalse(CanonicalSyncProtocol.isTombstoneChannelPath(path));
    }

    @Test
    public void malformedOrNonCanonicalPathsAreRejected() {
        try {
            CanonicalSyncProtocol.transferIdFromActivityChannelPath("/legacy/activity");
            fail("Expected path rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Unexpected"));
        }

        try {
            CanonicalSyncProtocol.transferIdFromActivityChannelPath(
                    CanonicalSyncProtocol.ACTIVITY_CHANNEL_ROOT + "/not-a-uuid");
            fail("Expected identifier rejection");
        } catch (CanonicalContractException expected) {
            assertEquals("UUID_INVALID", expected.getCode());
        }
    }
}
