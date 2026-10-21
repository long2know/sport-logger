package com.long2know.sportlogger.transfer;

import android.net.Uri;

import com.long2know.utilities.transfer.CanonicalSyncProtocol;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 26)
public class PhoneChannelFileSenderTest {
    private static final String NODE_ID = "watch-node";
    private static final String TRANSFER_ID = "3e6a8ed7-db1f-423c-baf4-f9fe10dbafcb";

    private File stagingDirectory;

    @Before
    public void setUp() throws Exception {
        stagingDirectory = new File(
                "build/test-data/phone-tombstone-channel-" + System.nanoTime());
        deleteRecursively(stagingDirectory);
    }

    @After
    public void tearDown() throws Exception {
        deleteRecursively(stagingDirectory);
    }

    @Test
    public void sendsExactWholeFileAndRemovesTransientStaging() throws Exception {
        byte[] exactBytes = new byte[]{2, 7, 1, 8, 2, 8};
        RecordingGateway gateway = new RecordingGateway(0);

        new PhoneChannelFileSender(stagingDirectory, gateway)
                .sendTombstone(NODE_ID, TRANSFER_ID, exactBytes);

        assertEquals(1, gateway.attempts.size());
        Attempt attempt = gateway.attempts.get(0);
        assertEquals(NODE_ID, attempt.nodeId);
        assertEquals(
                CanonicalSyncProtocol.tombstoneChannelPath(TRANSFER_ID),
                attempt.channelPath);
        assertArrayEquals(exactBytes, attempt.fileBytes);
        assertFalse(new File(stagingDirectory, TRANSFER_ID + ".pb").exists());
        assertFalse(new File(stagingDirectory, TRANSFER_ID + ".pb.pending").exists());
    }

    @Test
    public void failedSendRemovesTransientStaging() throws Exception {
        RecordingGateway gateway = new RecordingGateway(1);
        try {
            new PhoneChannelFileSender(stagingDirectory, gateway)
                    .sendTombstone(NODE_ID, TRANSFER_ID, new byte[]{1, 2, 3});
            fail("Expected channel failure");
        } catch (IOException expected) {
            assertEquals("simulated channel failure", expected.getMessage());
        }

        assertEquals(1, gateway.attempts.size());
        assertTrue(stagingDirectory.isDirectory());
        File[] remaining = stagingDirectory.listFiles();
        assertTrue(remaining == null || remaining.length == 0);
    }

    private static final class RecordingGateway
            implements PhoneChannelFileSender.Gateway {
        private final List<Attempt> attempts = new ArrayList<>();
        private int failuresRemaining;

        private RecordingGateway(int failuresRemaining) {
            this.failuresRemaining = failuresRemaining;
        }

        @Override
        public void sendFile(String nodeId, String channelPath, Uri fileUri)
                throws Exception {
            attempts.add(new Attempt(
                    nodeId,
                    channelPath,
                    read(new File(fileUri.getPath()))));
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw new IOException("simulated channel failure");
            }
        }
    }

    private static final class Attempt {
        private final String nodeId;
        private final String channelPath;
        private final byte[] fileBytes;

        private Attempt(String nodeId, String channelPath, byte[] fileBytes) {
            this.nodeId = nodeId;
            this.channelPath = channelPath;
            this.fileBytes = fileBytes;
        }
    }

    private static byte[] read(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            for (int read = input.read(buffer); read != -1; read = input.read(buffer)) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        if (!file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!file.delete()) {
            throw new IOException("Could not delete test file " + file);
        }
    }
}
