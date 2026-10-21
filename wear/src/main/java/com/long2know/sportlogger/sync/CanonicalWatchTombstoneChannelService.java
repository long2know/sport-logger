package com.long2know.sportlogger.sync;

import android.net.Uri;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import com.long2know.utilities.contract.CanonicalContractException;
import com.long2know.utilities.transfer.CanonicalSyncProtocol;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

public final class CanonicalWatchTombstoneChannelService
        extends WearableListenerService {
    private static final String TAG = "WatchTombstoneRx";
    private static final Object TRANSFER_LOCK = new Object();
    private static final long CHANNEL_TIMEOUT_MINUTES = 5L;
    private static final long MESSAGE_TIMEOUT_SECONDS = 30L;
    private static final long MAX_TRANSFER_BYTES = 1024L * 1024L;
    private static final String STAGING_DIRECTORY = "canonical-tombstone-incoming";

    @Override
    public void onChannelOpened(ChannelClient.Channel channel) {
        if (!CanonicalSyncProtocol.isTombstoneChannelPath(channel.getPath())) {
            closeQuietly(channel);
            return;
        }
        synchronized (TRANSFER_LOCK) {
            receive(channel);
        }
    }

    private void receive(ChannelClient.Channel channel) {
        String transferId = null;
        File stagingFile = null;
        try {
            transferId =
                    CanonicalSyncProtocol.transferIdFromTombstoneChannelPath(
                            channel.getPath());
            stagingFile = stagingFile(transferId);
            if (stagingFile.exists() && !stagingFile.delete()) {
                throw new IOException("Could not replace stale incoming tombstone file");
            }
            Tasks.await(
                    Wearable.getChannelClient(this).receiveFile(
                            channel,
                            Uri.fromFile(stagingFile),
                            false),
                    CHANNEL_TIMEOUT_MINUTES,
                    TimeUnit.MINUTES);
            byte[] tombstoneBytes = readBounded(stagingFile);

            CanonicalWatchTombstoneTransferHandler.Result result =
                    WatchSyncDispatcher.acceptIncomingTombstone(
                            this,
                            channel.getNodeId(),
                            channel.getPath(),
                            tombstoneBytes,
                            System.currentTimeMillis(),
                            this::sendAcknowledgement);
            WatchSyncEvents.incomingTombstoneChanged(this, result);
            if (!result.isAcknowledgementDelivered()) {
                Log.w(
                        TAG,
                        "Canonical tombstone committed; commit ACK will be recovered "
                                + "by duplicate delivery",
                        result.getAcknowledgementDeliveryFailure());
            }
        } catch (Exception error) {
            Log.e(TAG, "Could not receive canonical phone tombstone", error);
            WatchSyncEvents.incomingTransferFailed(
                    this,
                    transferId,
                    errorCode(error));
        } finally {
            if (stagingFile != null && stagingFile.exists() && !stagingFile.delete()) {
                Log.w(TAG, "Could not remove incoming tombstone staging file");
            }
            closeQuietly(channel);
        }
    }

    private void sendAcknowledgement(
            String destinationNodeId,
            byte[] exactAcknowledgementBytes) throws Exception {
        Tasks.await(
                Wearable.getMessageClient(this).sendMessage(
                        destinationNodeId,
                        CanonicalSyncProtocol.COMMIT_ACK_PATH,
                        exactAcknowledgementBytes),
                MESSAGE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS);
    }

    private File stagingFile(String transferId) throws IOException {
        File directory = new File(getCacheDir(), STAGING_DIRECTORY);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create incoming tombstone staging directory");
        }
        if (!directory.isDirectory()) {
            throw new IOException("Incoming tombstone staging path is not a directory");
        }
        return new File(directory, transferId + ".pb");
    }

    private static byte[] readBounded(File source) throws IOException {
        long length = source.length();
        if (length <= 0L) {
            throw new IOException("Canonical tombstone transfer file is empty");
        }
        if (length > MAX_TRANSFER_BYTES || length > Integer.MAX_VALUE) {
            throw new IOException("Canonical tombstone transfer exceeds the read limit");
        }
        try (InputStream input = new FileInputStream(source);
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) length)) {
            byte[] buffer = new byte[4 * 1024];
            long total = 0L;
            for (int read = input.read(buffer); read != -1; read = input.read(buffer)) {
                total += read;
                if (total > MAX_TRANSFER_BYTES) {
                    throw new IOException(
                            "Canonical tombstone transfer exceeds the read limit");
                }
                output.write(buffer, 0, read);
            }
            if (total != length) {
                throw new IOException("Canonical tombstone transfer length changed");
            }
            return output.toByteArray();
        }
    }

    private void closeQuietly(ChannelClient.Channel channel) {
        try {
            Tasks.await(
                    Wearable.getChannelClient(this).close(channel),
                    MESSAGE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
        } catch (Exception error) {
            Log.w(TAG, "Could not close canonical tombstone channel", error);
        }
    }

    private static String errorCode(Exception error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof CanonicalContractException) {
                return ((CanonicalContractException) current).getCode();
            }
            current = current.getCause();
        }
        return "TRANSFER_IO_FAILURE";
    }
}
