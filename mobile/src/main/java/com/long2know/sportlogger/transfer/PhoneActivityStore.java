package com.long2know.sportlogger.transfer;

import android.content.Context;

import com.long2know.utilities.models.SportActivity;
import com.long2know.utilities.models.SportTrackPoint;
import com.long2know.utilities.transfer.ActivityTransferProtocol;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.io.OptionalDataException;
import java.io.StreamCorruptedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PhoneActivityStore {
    private static final Object LOCK = new Object();
    private static final String DIRECTORY_NAME = "received-sport-activities";
    private static final long MAX_ACTIVITY_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_TRACK_POINTS = 250_000;
    private static final int MAX_LIST_LIMIT = 1_000;

    private final File directory;

    public enum ReadFailure {
        CORRUPT,
        TOO_LARGE,
        UNSUPPORTED
    }

    public static final class ActivityReadException extends IOException {
        private final ReadFailure failure;

        private ActivityReadException(
                ReadFailure failure,
                String message,
                Throwable cause) {
            super(message, cause);
            this.failure = failure;
        }

        public ReadFailure getFailure() {
            return failure;
        }
    }

    public static final class StoredActivityFile {
        private final String storageId;
        private final long byteCount;
        private final long lastModifiedMillis;
        private final boolean acknowledgementPending;

        private StoredActivityFile(
                String storageId,
                long byteCount,
                long lastModifiedMillis,
                boolean acknowledgementPending) {
            this.storageId = storageId;
            this.byteCount = byteCount;
            this.lastModifiedMillis = lastModifiedMillis;
            this.acknowledgementPending = acknowledgementPending;
        }

        public String getStorageId() {
            return storageId;
        }

        public long getByteCount() {
            return byteCount;
        }

        public long getLastModifiedMillis() {
            return lastModifiedMillis;
        }

        public boolean isAcknowledgementPending() {
            return acknowledgementPending;
        }
    }

    public static final class StoredActivityListing {
        private final List<StoredActivityFile> activities;
        private final int totalCount;

        private StoredActivityListing(
                List<StoredActivityFile> activities,
                int totalCount) {
            this.activities = Collections.unmodifiableList(activities);
            this.totalCount = totalCount;
        }

        public List<StoredActivityFile> getActivities() {
            return activities;
        }

        public int getTotalCount() {
            return totalCount;
        }

        public boolean isTruncated() {
            return totalCount > activities.size();
        }
    }

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

    public StoredActivityListing listStoredActivities(int limit) throws IOException {
        if (limit <= 0 || limit > MAX_LIST_LIMIT) {
            throw new IllegalArgumentException(
                    "Activity list limit must be in range 1.." + MAX_LIST_LIMIT);
        }
        synchronized (LOCK) {
            if (!directory.exists()) {
                return new StoredActivityListing(new ArrayList<>(), 0);
            }
            File[] files = directory.listFiles(
                    file -> file.isFile() && file.getName().endsWith(".ser"));
            if (files == null) {
                throw new IOException("Could not list received activity storage");
            }
            Arrays.sort(
                    files,
                    Comparator.comparingLong(File::lastModified)
                            .reversed()
                            .thenComparing(File::getName));
            int count = Math.min(limit, files.length);
            List<StoredActivityFile> stored = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                File file = files[index];
                String storageId = storageId(file);
                stored.add(new StoredActivityFile(
                        storageId,
                        file.length(),
                        file.lastModified(),
                        new File(directory, storageId + ".ack").isFile()));
            }
            return new StoredActivityListing(stored, files.length);
        }
    }

    public SportActivity loadStoredActivity(String storageId) throws IOException {
        String safeStorageId = requireFileSegment(storageId);
        File source;
        long byteCount;
        synchronized (LOCK) {
            source = new File(directory, safeStorageId + ".ser");
            if (!source.isFile()) {
                throw new IOException("Received activity no longer exists");
            }
            byteCount = source.length();
        }
        if (byteCount <= 0L) {
            throw readFailure(
                    ReadFailure.CORRUPT,
                    "Received activity payload is empty",
                    null);
        }
        if (byteCount > MAX_ACTIVITY_BYTES) {
            throw readFailure(
                    ReadFailure.TOO_LARGE,
                    "Received activity payload exceeds the read limit",
                    null);
        }

        try (WhitelistedObjectInputStream input = new WhitelistedObjectInputStream(
                new BufferedInputStream(new FileInputStream(source), 16 * 1024))) {
            Object decoded = input.readObject();
            if (!(decoded instanceof SportActivity)) {
                throw readFailure(
                        ReadFailure.UNSUPPORTED,
                        "Received activity payload has an unsupported type",
                        null);
            }
            if (input.read() != -1) {
                throw readFailure(
                        ReadFailure.CORRUPT,
                        "Received activity payload has trailing data",
                        null);
            }
            SportActivity activity = (SportActivity) decoded;
            if (activity.SportTrackPoints == null) {
                throw readFailure(
                        ReadFailure.CORRUPT,
                        "Received activity has no track point collection",
                        null);
            }
            if (activity.SportTrackPoints.size() > MAX_TRACK_POINTS) {
                throw readFailure(
                        ReadFailure.TOO_LARGE,
                        "Received activity exceeds the track point limit",
                        null);
            }
            for (SportTrackPoint point : activity.SportTrackPoints) {
                if (point == null) {
                    throw readFailure(
                            ReadFailure.CORRUPT,
                            "Received activity contains an invalid track point",
                            null);
                }
            }
            return activity;
        } catch (UnsupportedSerializedClassException error) {
            throw readFailure(
                    ReadFailure.UNSUPPORTED,
                    "Received activity uses an unsupported serialized class",
                    error);
        } catch (StreamCorruptedException
                 | InvalidClassException
                 | OptionalDataException
                 | EOFException error) {
            throw readFailure(
                    ReadFailure.CORRUPT,
                    "Received activity payload is corrupt",
                    error);
        } catch (ClassNotFoundException error) {
            throw readFailure(
                    ReadFailure.UNSUPPORTED,
                    "Received activity payload cannot be decoded by this app version",
                    error);
        }
    }

    public boolean isAcknowledgementPending(String storageId) {
        String safeStorageId = requireFileSegment(storageId);
        synchronized (LOCK) {
            return new File(directory, safeStorageId + ".ack").isFile();
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

    private static String storageId(File file) {
        String name = file.getName();
        return name.substring(0, name.length() - ".ser".length());
    }

    private static ActivityReadException readFailure(
            ReadFailure failure,
            String message,
            Throwable cause) {
        return new ActivityReadException(failure, message, cause);
    }

    private static final class WhitelistedObjectInputStream extends ObjectInputStream {
        private static final Set<String> ALLOWED_CLASSES = new HashSet<>(Arrays.asList(
                SportActivity.class.getName(),
                SportTrackPoint.class.getName(),
                ArrayList.class.getName(),
                java.util.Date.class.getName()));

        private WhitelistedObjectInputStream(BufferedInputStream input) throws IOException {
            super(input);
        }

        @Override
        protected Class<?> resolveClass(ObjectStreamClass descriptor)
                throws IOException, ClassNotFoundException {
            if (!ALLOWED_CLASSES.contains(descriptor.getName())) {
                throw new UnsupportedSerializedClassException(descriptor.getName());
            }
            return super.resolveClass(descriptor);
        }

        @Override
        protected Class<?> resolveProxyClass(String[] interfaces)
                throws IOException, ClassNotFoundException {
            throw new UnsupportedSerializedClassException("proxy");
        }
    }

    private static final class UnsupportedSerializedClassException
            extends InvalidClassException {
        private UnsupportedSerializedClassException(String className) {
            super(className, "Serialized class is not allowed");
        }
    }
}
