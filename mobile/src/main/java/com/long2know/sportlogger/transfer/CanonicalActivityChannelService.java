package com.long2know.sportlogger.transfer;

import android.net.Uri;
import android.util.Log;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository;
import com.long2know.utilities.contract.CanonicalContractException;
import com.long2know.utilities.transfer.CanonicalSyncProtocol;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

public final class CanonicalActivityChannelService extends WearableListenerService {
    private static final String TAG = "CanonicalActivityRx";
    private static final Object TRANSFER_LOCK = new Object();
    private static final long CHANNEL_TIMEOUT_MINUTES = 5L;
    private static final long MESSAGE_TIMEOUT_SECONDS = 30L;
    private static final long MAX_TRANSFER_BYTES = 64L * 1024L * 1024L;
    private static final String STAGING_DIRECTORY = "canonical-activity-incoming";

    @Override
    public void onChannelOpened(ChannelClient.Channel channel) {
        String path = channel.getPath();
        if (!CanonicalSyncProtocol.isActivityChannelPath(path)
                && !CanonicalSyncProtocol.isTombstoneChannelPath(path)) {
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
            boolean activity =
                    CanonicalSyncProtocol.isActivityChannelPath(channel.getPath());
            transferId = activity
                    ? CanonicalSyncProtocol.transferIdFromActivityChannelPath(
                    channel.getPath())
                    : CanonicalSyncProtocol.transferIdFromTombstoneChannelPath(
                            channel.getPath());
            stagingFile = stagingFile(transferId);
            if (stagingFile.exists() && !stagingFile.delete()) {
                throw new IOException("Could not replace stale incoming transfer file");
            }
            Tasks.await(
                    Wearable.getChannelClient(this).receiveFile(
                            channel,
                            Uri.fromFile(stagingFile),
                            false),
                    CHANNEL_TIMEOUT_MINUTES,
                    TimeUnit.MINUTES);
            byte[] transferBytes = readBounded(stagingFile);

            try (PhoneActivityRepository repository =
                         PhoneActivityRepository.open(this)) {
                if (activity) {
                    CanonicalActivityTransferHandler.Result result =
                            new CanonicalActivityTransferHandler(
                                    repository,
                                    this::sendAcknowledgement)
                                    .receive(
                                            channel.getNodeId(),
                                            channel.getPath(),
                                            transferBytes,
                                            System.currentTimeMillis());
                    PhoneSyncEvents.activityChanged(this, result);
                    logAcknowledgementFailure(
                            result.isAcknowledgementDelivered(),
                            result.getAcknowledgementDeliveryFailure());
                } else {
                    CanonicalTombstoneTransferHandler.Result result =
                            new CanonicalTombstoneTransferHandler(
                                    repository,
                                    this::sendAcknowledgement)
                                    .receive(
                                            channel.getNodeId(),
                                            channel.getPath(),
                                            transferBytes,
                                            System.currentTimeMillis());
                    PhoneSyncEvents.tombstoneChanged(this, result);
                    logAcknowledgementFailure(
                            result.isAcknowledgementDelivered(),
                            result.getAcknowledgementDeliveryFailure());
                }
            }
        } catch (Exception error) {
            Log.e(TAG, "Could not receive canonical activity transfer", error);
            PhoneSyncEvents.transferFailed(
                    this,
                    transferId,
                    errorCode(error));
        } finally {
            if (stagingFile != null && stagingFile.exists() && !stagingFile.delete()) {
                Log.w(TAG, "Could not remove canonical incoming staging file");
            }
            closeQuietly(channel);
        }
    }

    private static void logAcknowledgementFailure(
            boolean delivered,
            Exception failure) {
        if (!delivered) {
            Log.w(
                    TAG,
                    "Canonical transfer committed; commit ACK will be recovered "
                            + "by duplicate delivery",
                    failure);
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
            throw new IOException("Could not create canonical incoming staging directory");
        }
        if (!directory.isDirectory()) {
            throw new IOException("Canonical incoming staging path is not a directory");
        }
        return new File(directory, transferId + ".pb");
    }

    private static byte[] readBounded(File source) throws IOException {
        long length = source.length();
        if (length <= 0L) {
            throw new IOException("Canonical activity transfer file is empty");
        }
        if (length > MAX_TRANSFER_BYTES || length > Integer.MAX_VALUE) {
            throw new IOException("Canonical activity transfer file exceeds the read limit");
        }
        try (InputStream input = new FileInputStream(source);
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) length)) {
            byte[] buffer = new byte[16 * 1024];
            long total = 0L;
            for (int read = input.read(buffer); read != -1; read = input.read(buffer)) {
                total += read;
                if (total > MAX_TRANSFER_BYTES) {
                    throw new IOException(
                            "Canonical activity transfer file exceeds the read limit");
                }
                output.write(buffer, 0, read);
            }
            if (total != length) {
                throw new IOException("Canonical activity transfer file length changed");
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
            Log.w(TAG, "Could not close canonical activity channel", error);
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
