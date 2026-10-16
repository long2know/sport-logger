package com.long2know.sportlogger.transfer;

import android.content.Context;

import com.long2know.utilities.transfer.ActivityTransferProtocol;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public final class PhoneActivityStore {
    private static final Object LOCK = new Object();
    private static final String DIRECTORY_NAME = "received-sport-activities";

    private final File directory;

    public static final class PendingAcknowledgement {
        private final File marker;
        private final String acknowledgementPath;
        private final String transferId;
        private final String sourceUri;

        private PendingAcknowledgement(
                File marker,
                String acknowledgementPath,
                String transferId,
                String sourceUri) {
            this.marker = marker;
            this.acknowledgementPath = acknowledgementPath;
            this.transferId = transferId;
            this.sourceUri = sourceUri;
        }

        public String getAcknowledgementPath() {
            return acknowledgementPath;
        }

        public String getTransferId() {
            return transferId;
        }

        public String getSourceUri() {
            return sourceUri;
        }
    }

    public PhoneActivityStore(Context context) {
        this(new File(context.getApplicationContext().getFilesDir(), DIRECTORY_NAME));
    }

    PhoneActivityStore(File directory) {
        this.directory = directory;
    }

    public boolean persist(String sourceNodeId, String transferId, byte[] payload)
            throws IOException {
        String sourceKey = ActivityTransferProtocol.payloadSha256(
                requireValue(sourceNodeId, "Source node").getBytes(StandardCharsets.UTF_8))
                .substring(0, 16);
        String safeTransferId = requireFileSegment(transferId);
        byte[] durablePayload = payload.clone();
        synchronized (LOCK) {
            ensureDirectory();
            File destination = new File(directory, key(sourceKey, safeTransferId) + ".ser");
            if (destination.exists()) {
                verifySame(destination, durablePayload);
                return false;
            }

            writeAtomically(destination, durablePayload);
            return true;
        }
    }

    public PendingAcknowledgement prepareAcknowledgement(
            String sourceNodeId,
            String transferId,
            String acknowledgementPath,
            String sourceUri) throws IOException {
        String sourceKey = ActivityTransferProtocol.payloadSha256(
                requireValue(sourceNodeId, "Source node").getBytes(StandardCharsets.UTF_8))
                .substring(0, 16);
        String safeTransferId = requireFileSegment(transferId);
        PendingAcknowledgement pending = new PendingAcknowledgement(
                new File(directory, key(sourceKey, safeTransferId) + ".ack"),
                requireValue(acknowledgementPath, "Acknowledgement path"),
                safeTransferId,
                requireValue(sourceUri, "Source URI"));
        synchronized (LOCK) {
            ensureDirectory();
            byte[] encoded = encode(pending);
            if (pending.marker.exists()) {
                verifySame(pending.marker, encoded);
            } else {
                writeAtomically(pending.marker, encoded);
            }
            return pending;
        }
    }

    public List<PendingAcknowledgement> pendingAcknowledgements() throws IOException {
        synchronized (LOCK) {
            if (!directory.exists()) {
                return new ArrayList<>();
            }
            File[] files = directory.listFiles(
                    file -> file.isFile() && file.getName().endsWith(".ack"));
            if (files == null) {
                throw new IOException("Could not list pending transfer acknowledgements");
            }
            List<PendingAcknowledgement> pending = new ArrayList<>(files.length);
            for (File file : files) {
                pending.add(decode(file));
            }
            return pending;
        }
    }

    public void acknowledgementQueued(PendingAcknowledgement pending) throws IOException {
        synchronized (LOCK) {
            if (pending.marker.exists() && !pending.marker.delete()) {
                throw new IOException("Could not clear queued transfer acknowledgement");
            }
        }
    }

    int storedActivityCount() throws IOException {
        synchronized (LOCK) {
            if (!directory.exists()) {
                return 0;
            }
            File[] files = directory.listFiles(
                    file -> file.isFile() && file.getName().endsWith(".ser"));
            if (files == null) {
                throw new IOException("Could not list received activity storage");
            }
            return files.length;
        }
    }

    private void ensureDirectory() throws IOException {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create received activity storage");
        }
        if (!directory.isDirectory()) {
            throw new IOException("Received activity storage is not a directory");
        }
    }

    private static void verifySame(File destination, byte[] payload) throws IOException {
        byte[] stored = read(destination);
        if (!MessageDigest.isEqual(stored, payload)) {
            throw new IOException("Transfer identifier already stores different activity data");
        }
    }

    private static void writeAtomically(File destination, byte[] payload) throws IOException {
        File pending = new File(destination.getParentFile(), destination.getName() + ".pending");
        try {
            try (FileOutputStream output = new FileOutputStream(pending, false)) {
                output.write(payload);
                output.flush();
                output.getFD().sync();
            }
            if (!pending.renameTo(destination)) {
                if (destination.exists()) {
                    verifySame(destination, payload);
                    return;
                }
                throw new IOException("Could not atomically store received activity data");
            }
        } finally {
            pending.delete();
        }
    }

    private static byte[] encode(PendingAcknowledgement pending) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF(pending.acknowledgementPath);
            output.writeUTF(pending.transferId);
            output.writeUTF(pending.sourceUri);
            output.flush();
            return bytes.toByteArray();
        }
    }

    private static PendingAcknowledgement decode(File marker) throws IOException {
        try (DataInputStream input = new DataInputStream(new FileInputStream(marker))) {
            return new PendingAcknowledgement(
                    marker,
                    input.readUTF(),
                    input.readUTF(),
                    input.readUTF());
        }
    }

    private static byte[] read(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            for (int read = input.read(buffer); read != -1; read = input.read(buffer)) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String requireValue(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }

    private static String requireFileSegment(String value) {
        String segment = requireValue(value, "Transfer identifier");
        if (!segment.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Transfer identifier is not file-safe");
        }
        return segment;
    }

    private static String key(String sourceKey, String transferId) {
        return sourceKey + "-" + transferId;
    }
}
