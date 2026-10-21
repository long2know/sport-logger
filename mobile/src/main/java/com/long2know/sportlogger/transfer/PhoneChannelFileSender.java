package com.long2know.sportlogger.transfer;

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

final class PhoneChannelFileSender {
    interface Gateway {
        void sendFile(String nodeId, String channelPath, Uri fileUri) throws Exception;
    }

    private static final String STAGING_DIRECTORY = "canonical-tombstone-outgoing";

    private final File stagingDirectory;
    private final Gateway gateway;

    PhoneChannelFileSender(Context context) {
        Context applicationContext = context.getApplicationContext();
        stagingDirectory = new File(
                applicationContext.getCacheDir(),
                STAGING_DIRECTORY);
        gateway = new GoogleGateway(applicationContext);
    }

    PhoneChannelFileSender(File stagingDirectory, Gateway gateway) {
        this.stagingDirectory = Objects.requireNonNull(stagingDirectory);
        this.gateway = Objects.requireNonNull(gateway);
    }

    void sendTombstone(
            String nodeId,
            String transferId,
            byte[] exactTransferBytes) throws Exception {
        if (nodeId == null || nodeId.isEmpty()) {
            throw new IllegalArgumentException("Destination node identifier is required");
        }
        byte[] durableBytes = Objects.requireNonNull(exactTransferBytes).clone();
        if (durableBytes.length == 0) {
            throw new IllegalArgumentException("Canonical tombstone bytes are required");
        }

        File staged = stage(transferId, durableBytes);
        try {
            gateway.sendFile(
                    nodeId,
                    CanonicalSyncProtocol.tombstoneChannelPath(transferId),
                    Uri.fromFile(staged));
        } finally {
            if (staged.exists()) {
                staged.delete();
            }
        }
    }

    private File stage(String transferId, byte[] exactTransferBytes) throws IOException {
        CanonicalSyncProtocol.tombstoneChannelPath(transferId);
        if (!stagingDirectory.exists() && !stagingDirectory.mkdirs()) {
            throw new IOException("Could not create phone tombstone staging directory");
        }
        if (!stagingDirectory.isDirectory()) {
            throw new IOException("Phone tombstone staging path is not a directory");
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
                throw new IOException("Could not replace phone tombstone staging file");
            }
            if (!pending.renameTo(destination)) {
                throw new IOException("Could not finalize phone tombstone staging file");
            }
            if (!MessageDigest.isEqual(read(destination), exactTransferBytes)) {
                throw new IOException("Phone tombstone staging file changed");
            }
            return destination;
        } finally {
            if (pending.exists()) {
                pending.delete();
            }
        }
    }

    private static byte[] read(File source) throws IOException {
        try (InputStream input = new FileInputStream(source);
             ByteArrayOutputStream output =
                     new ByteArrayOutputStream((int) Math.min(source.length(), 16 * 1024L))) {
            byte[] buffer = new byte[4 * 1024];
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
            channelClient = Wearable.getChannelClient(context);
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
            Exception sendFailure = null;
            try {
                Tasks.await(
                        channelClient.sendFile(channel, fileUri),
                        CHANNEL_TIMEOUT_MINUTES,
                        TimeUnit.MINUTES);
            } catch (Exception error) {
                sendFailure = error;
                throw error;
            } finally {
                try {
                    Tasks.await(
                            channelClient.close(channel),
                            CLOSE_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS);
                } catch (Exception closeFailure) {
                    if (sendFailure != null) {
                        sendFailure.addSuppressed(closeFailure);
                    } else {
                        throw closeFailure;
                    }
                }
            }
        }
    }
}
