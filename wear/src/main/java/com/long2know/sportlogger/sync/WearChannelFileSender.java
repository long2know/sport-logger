package com.long2know.sportlogger.sync;

import android.content.Context;
import android.net.Uri;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Wearable;
import com.long2know.utilities.transfer.CanonicalSyncProtocol;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class WearChannelFileSender {
    interface Gateway {
        void sendFile(String nodeId, String channelPath, Uri fileUri) throws Exception;
    }

    private static final String STAGING_DIRECTORY = "canonical-activity-outgoing";

    private final File stagingDirectory;
    private final Gateway gateway;

    public WearChannelFileSender(Context context) {
        Context applicationContext = context.getApplicationContext();
        this.stagingDirectory =
                new File(applicationContext.getCacheDir(), STAGING_DIRECTORY);
        this.gateway = new GoogleGateway(applicationContext);
    }

    WearChannelFileSender(File stagingDirectory, Gateway gateway) {
        this.stagingDirectory = Objects.requireNonNull(stagingDirectory);
        this.gateway = Objects.requireNonNull(gateway);
    }

    public void sendActivity(
            String nodeId,
            String transferId,
            byte[] exactTransferBytes) throws Exception {
        send(
                nodeId,
                CanonicalSyncProtocol.activityChannelPath(transferId),
                transferId,
                exactTransferBytes);
    }

    public void sendTombstone(
            String nodeId,
            String transferId,
            byte[] exactTransferBytes) throws Exception {
        send(
                nodeId,
                CanonicalSyncProtocol.tombstoneChannelPath(transferId),
                transferId,
                exactTransferBytes);
    }

    private void send(
            String nodeId,
            String channelPath,
            String transferId,
            byte[] exactTransferBytes) throws Exception {
        if (nodeId == null || nodeId.isEmpty()) {
            throw new IllegalArgumentException("Destination node identifier is required");
        }
        byte[] durableBytes = Objects.requireNonNull(exactTransferBytes).clone();
        if (durableBytes.length == 0) {
            throw new IllegalArgumentException("Canonical transfer bytes are required");
        }

        File staged = stage(transferId, durableBytes);
        try {
            gateway.sendFile(nodeId, channelPath, Uri.fromFile(staged));
        } finally {
            if (staged.exists() && !staged.delete()) {
                staged.deleteOnExit();
            }
        }
    }

    private File stage(String transferId, byte[] exactTransferBytes) throws IOException {
        if (!stagingDirectory.exists() && !stagingDirectory.mkdirs()) {
            throw new IOException("Could not create canonical outgoing staging directory");
        }
        if (!stagingDirectory.isDirectory()) {
            throw new IOException("Canonical outgoing staging path is not a directory");
        }
        File destination = new File(stagingDirectory, transferId + ".pb");
        File pending = new File(stagingDirectory, transferId + ".pb.pending");
        try {
            try (FileOutputStream output = new FileOutputStream(pending, false)) {
                output.write(exactTransferBytes);
                output.flush();
                output.getFD().sync();
            }
            if (destination.exists() && !destination.delete()) {
                throw new IOException("Could not replace canonical outgoing staging file");
            }
            if (!pending.renameTo(destination)) {
                throw new IOException("Could not finalize canonical outgoing staging file");
            }
            if (!MessageDigest.isEqual(read(destination), exactTransferBytes)) {
                throw new IOException("Canonical outgoing staging file changed");
            }
            return destination;
        } finally {
            if (pending.exists() && !pending.delete()) {
                pending.deleteOnExit();
            }
        }
    }

    private static byte[] read(File source) throws IOException {
        try (InputStream input = new FileInputStream(source);
             ByteArrayOutputStream output =
                     new ByteArrayOutputStream((int) Math.min(source.length(), 64 * 1024L))) {
            byte[] buffer = new byte[16 * 1024];
            for (int read = input.read(buffer); read != -1; read = input.read(buffer)) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static final class GoogleGateway implements Gateway {
        private static final long CHANNEL_TIMEOUT_MINUTES = 5L;
        private static final long CLOSE_TIMEOUT_SECONDS = 30L;

        private final ChannelClient channelClient;

        private GoogleGateway(Context context) {
            this.channelClient = Wearable.getChannelClient(context);
        }

        @Override
        public void sendFile(
                String nodeId,
                String channelPath,
                Uri fileUri) throws Exception {
            ChannelClient.Channel channel = Tasks.await(
                    channelClient.openChannel(nodeId, channelPath),
                    CLOSE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
            try {
                Tasks.await(
                        channelClient.sendFile(channel, fileUri),
                        CHANNEL_TIMEOUT_MINUTES,
                        TimeUnit.MINUTES);
            } finally {
                Tasks.await(
                        channelClient.close(channel),
                        CLOSE_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS);
            }
        }
    }
}
