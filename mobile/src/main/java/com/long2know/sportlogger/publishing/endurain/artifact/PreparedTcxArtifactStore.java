package com.long2know.sportlogger.publishing.endurain.artifact;

import android.content.Context;

import com.long2know.sportlogger.connector.endurain.TcxUploadArtifact;
import com.long2know.sportlogger.export.CanonicalTcxExportSource;
import com.long2know.sportlogger.persistence.phone.PhoneActivityRepository;
import com.long2know.sportlogger.publishing.endurain.EndurainDeliveryKey;
import com.long2know.sportlogger.publishing.endurain.profile.EndurainConnectionProfile;
import com.long2know.utilities.export.tcx.TcxExportException;
import com.long2know.utilities.export.tcx.TcxWriter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class PreparedTcxArtifactStore {
    private static final int MANIFEST_MAGIC = 0x534c4531;
    private static final int MANIFEST_VERSION = 1;
    private static final long MAX_MANIFEST_BYTES = 64L * 1024L;
    private static final long STAGING_STALE_MILLIS =
            24L * 60L * 60L * 1_000L;
    private static final Pattern ARTIFACT_ID =
            Pattern.compile("[0-9a-f]{64}");
    private static final String ROOT_DIRECTORY = "endurain-publishing-v1";
    private static final String MANIFEST_FILE = "manifest.bin";
    private static final String ARTIFACT_FILE = "activity.tcx";

    private final File root;
    private final TcxWriter writer;

    public static PreparedTcxArtifactStore forApplication(Context context) {
        Context applicationContext =
                Objects.requireNonNull(context, "context").getApplicationContext();
        return new PreparedTcxArtifactStore(
                new File(
                        applicationContext.getNoBackupFilesDir(),
                        ROOT_DIRECTORY),
                new TcxWriter());
    }

    PreparedTcxArtifactStore(File root, TcxWriter writer) {
        this.root = Objects.requireNonNull(root, "root");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    public synchronized PreparedArtifact prepare(
            PhoneActivityRepository repository,
            EndurainConnectionProfile profile,
            String sessionId,
            long revision,
            long createdAtUtcMs) throws PreparedArtifactException {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(profile, "profile");
        EndurainDeliveryKey key =
                EndurainDeliveryKey.forProfile(sessionId, revision, profile);
        String artifactId = artifactId(key);
        File finalDirectory = artifactDirectory(artifactId);
        if (finalDirectory.exists()) {
            ArtifactManifest existing = loadManifest(artifactId);
            requireIdentity(
                    existing,
                    key,
                    profile.getCredentialReference());
            Verification verification = verify(existing);
            if (!verification.isReady()) {
                throw new PreparedArtifactException(
                        verification.getState().name(),
                        "Existing prepared TCX artifact requires recovery");
            }
            return new PreparedArtifact(existing, verification.getUploadArtifact());
        }

        CanonicalTcxExportSource.MetadataSnapshot metadata =
                CanonicalTcxExportSource.MetadataSnapshot.from(
                        repository.metadata(sessionId));
        CanonicalTcxExportSource source =
                CanonicalTcxExportSource.fromRevision(
                        repository,
                        sessionId,
                        revision,
                        metadata);
        String uploadFilename = uploadFilename(sessionId, revision);
        ensureRoot();
        File stagingDirectory = new File(
                root,
                ".staging-" + artifactId + "-" + UUID.randomUUID());
        if (!stagingDirectory.mkdir()) {
            throw new PreparedArtifactException(
                    "ARTIFACT_STORAGE_UNAVAILABLE",
                    "Could not create prepared TCX staging directory");
        }

        try {
            WrittenArtifact written =
                    writeTcx(source, new File(stagingDirectory, ARTIFACT_FILE));
            ArtifactManifest manifest = new ArtifactManifest(
                    artifactId,
                    key,
                    profile.getCredentialReference(),
                    uploadFilename,
                    written.length,
                    written.sha256,
                    createdAtUtcMs,
                    metadata);
            writeManifest(
                    new File(stagingDirectory, MANIFEST_FILE),
                    manifest);
            moveDirectoryAtomically(stagingDirectory, finalDirectory);
            return new PreparedArtifact(
                    manifest,
                    new TcxUploadArtifact(
                            artifactId,
                            revision,
                            new File(finalDirectory, ARTIFACT_FILE),
                            uploadFilename,
                            written.length,
                            written.sha256));
        } catch (FileAlreadyExistsException error) {
            deleteStaging(stagingDirectory);
            ArtifactManifest existing = loadManifest(artifactId);
            requireIdentity(
                    existing,
                    key,
                    profile.getCredentialReference());
            Verification verification = verify(existing);
            if (!verification.isReady()) {
                throw new PreparedArtifactException(
                        verification.getState().name(),
                        "Concurrent prepared TCX artifact requires recovery",
                        error);
            }
            return new PreparedArtifact(existing, verification.getUploadArtifact());
        } catch (PreparedArtifactException error) {
            deleteStaging(stagingDirectory);
            throw error;
        } catch (IOException | TcxExportException error) {
            deleteStaging(stagingDirectory);
            throw new PreparedArtifactException(
                    "ARTIFACT_PREPARATION_FAILED",
                    "Could not prepare canonical TCX artifact",
                    error);
        } catch (RuntimeException error) {
            deleteStaging(stagingDirectory);
            throw error;
        }
    }

    public synchronized List<ArtifactManifest> listManifests()
            throws PreparedArtifactException {
        if (!root.exists()) {
            return Collections.emptyList();
        }
        cleanupStagingDirectories();
        File[] directories = root.listFiles(file ->
                file.isDirectory()
                        && ARTIFACT_ID.matcher(file.getName()).matches());
        if (directories == null) {
            throw new PreparedArtifactException(
                    "ARTIFACT_STORAGE_UNAVAILABLE",
                    "Could not list prepared TCX artifacts");
        }
        List<ArtifactManifest> manifests =
                new ArrayList<>(directories.length);
        for (File directory : directories) {
            manifests.add(loadManifest(directory.getName()));
        }
        manifests.sort(Comparator
                .comparingLong(ArtifactManifest::getCreatedAtUtcMs)
                .thenComparing(ArtifactManifest::getArtifactId));
        return Collections.unmodifiableList(manifests);
    }

    public synchronized ArtifactManifest loadManifest(String artifactId)
            throws PreparedArtifactException {
        requireArtifactId(artifactId);
        File manifestFile =
                new File(artifactDirectory(artifactId), MANIFEST_FILE);
        if (!manifestFile.isFile()) {
            throw new PreparedArtifactException(
                    "MANIFEST_MISSING",
                    "Prepared TCX manifest is missing");
        }
        if (manifestFile.length() < 1L
                || manifestFile.length() > MAX_MANIFEST_BYTES) {
            throw new PreparedArtifactException(
                    "MANIFEST_CORRUPT",
                    "Prepared TCX manifest has an invalid size");
        }
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(new FileInputStream(manifestFile)))) {
            if (input.readInt() != MANIFEST_MAGIC
                    || input.readInt() != MANIFEST_VERSION) {
                throw new PreparedArtifactException(
                        "MANIFEST_CORRUPT",
                        "Prepared TCX manifest has an unsupported format");
            }
            String storedArtifactId = input.readUTF();
            EndurainDeliveryKey key = new EndurainDeliveryKey(
                    input.readUTF(),
                    input.readLong(),
                    input.readUTF(),
                    input.readUTF());
            String connectorId = input.readUTF();
            if (!EndurainDeliveryKey.CONNECTOR_ID.equals(connectorId)) {
                throw new PreparedArtifactException(
                        "MANIFEST_CORRUPT",
                        "Prepared TCX manifest has an unknown connector");
            }
            String credentialReference = input.readUTF();
            String uploadFilename = input.readUTF();
            long length = input.readLong();
            String sha256 = input.readUTF();
            long createdAtUtcMs = input.readLong();
            String title = readNullable(input);
            String notes = readNullable(input);
            int tagCount = input.readInt();
            if (tagCount < 0
                    || tagCount > PhoneActivityRepository.MAX_TAG_COUNT) {
                throw new PreparedArtifactException(
                        "MANIFEST_CORRUPT",
                        "Prepared TCX manifest has invalid tags");
            }
            List<String> tags = new ArrayList<>(tagCount);
            for (int index = 0; index < tagCount; index++) {
                tags.add(input.readUTF());
            }
            long metadataUpdatedAtUtcMs = input.readLong();
            if (input.read() != -1) {
                throw new PreparedArtifactException(
                        "MANIFEST_CORRUPT",
                        "Prepared TCX manifest has trailing data");
            }
            if (!artifactId.equals(storedArtifactId)
                    || !artifactId.equals(artifactId(key))) {
                throw new PreparedArtifactException(
                        "MANIFEST_IDENTITY_MISMATCH",
                        "Prepared TCX manifest identity does not match its path");
            }
            return new ArtifactManifest(
                    artifactId,
                    key,
                    credentialReference,
                    uploadFilename,
                    length,
                    sha256,
                    createdAtUtcMs,
                    new CanonicalTcxExportSource.MetadataSnapshot(
                            title,
                            notes,
                            tags,
                            metadataUpdatedAtUtcMs));
        } catch (PreparedArtifactException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw new PreparedArtifactException(
                    "MANIFEST_CORRUPT",
                    "Could not read prepared TCX manifest",
                    error);
        }
    }

    public synchronized Verification verify(ArtifactManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        File artifactFile =
                new File(
                        artifactDirectory(manifest.getArtifactId()),
                        ARTIFACT_FILE);
        if (!artifactFile.isFile()) {
            return Verification.failure(ArtifactState.MISSING);
        }
        if (artifactFile.length() != manifest.getArtifactLength()) {
            return Verification.failure(ArtifactState.LENGTH_MISMATCH);
        }
        String sha256;
        try {
            sha256 = sha256(artifactFile);
        } catch (IOException error) {
            return Verification.failure(ArtifactState.UNREADABLE);
        }
        if (!manifest.getArtifactSha256().equals(sha256)) {
            return Verification.failure(ArtifactState.SHA256_MISMATCH);
        }
        try {
            return Verification.ready(new TcxUploadArtifact(
                    manifest.getArtifactId(),
                    manifest.getKey().getRevision(),
                    artifactFile,
                    manifest.getUploadFilename(),
                    manifest.getArtifactLength(),
                    manifest.getArtifactSha256()));
        } catch (RuntimeException error) {
            return Verification.failure(ArtifactState.MANIFEST_INVALID);
        }
    }

    public synchronized PreparedArtifact repair(
            PhoneActivityRepository repository,
            String artifactId) throws PreparedArtifactException {
        Objects.requireNonNull(repository, "repository");
        ArtifactManifest manifest = loadManifest(artifactId);
        File directory = artifactDirectory(artifactId);
        File repairFile = new File(
                directory,
                ".repair-" + UUID.randomUUID() + ".tcx");
        try {
            CanonicalTcxExportSource source =
                    CanonicalTcxExportSource.fromRevision(
                            repository,
                            manifest.getKey().getSessionId(),
                            manifest.getKey().getRevision(),
                            manifest.getMetadata());
            WrittenArtifact written = writeTcx(source, repairFile);
            if (written.length != manifest.getArtifactLength()
                    || !written.sha256.equals(manifest.getArtifactSha256())) {
                throw new PreparedArtifactException(
                        "FROZEN_REBUILD_MISMATCH",
                        "Frozen canonical metadata no longer reproduces the prepared artifact");
            }
            try {
                Files.move(
                        repairFile.toPath(),
                        new File(directory, ARTIFACT_FILE).toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                throw new PreparedArtifactException(
                        "ATOMIC_MOVE_UNAVAILABLE",
                        "Prepared TCX repair requires an atomic file move",
                        error);
            }
            Verification verification = verify(manifest);
            if (!verification.isReady()) {
                throw new PreparedArtifactException(
                        verification.getState().name(),
                        "Repaired prepared TCX artifact did not verify");
            }
            return new PreparedArtifact(
                    manifest,
                    verification.getUploadArtifact());
        } catch (PreparedArtifactException error) {
            deleteFile(repairFile);
            throw error;
        } catch (IOException | TcxExportException error) {
            deleteFile(repairFile);
            throw new PreparedArtifactException(
                    "ARTIFACT_REPAIR_FAILED",
                    "Could not repair prepared TCX artifact",
                    error);
        }
    }

    public static String artifactId(EndurainDeliveryKey key) {
        Objects.requireNonNull(key, "key");
        MessageDigest digest = sha256Digest();
        update(digest, key.getSessionId());
        digest.update((byte) 0);
        update(digest, Long.toString(key.getRevision()));
        digest.update((byte) 0);
        update(digest, key.getConnectorId());
        digest.update((byte) 0);
        update(digest, key.getNormalizedBaseUrl());
        digest.update((byte) 0);
        update(digest, key.getConnectionIdentity());
        return hex(digest.digest());
    }

    private WrittenArtifact writeTcx(
            CanonicalTcxExportSource source,
            File destination) throws IOException, TcxExportException {
        MessageDigest digest = sha256Digest();
        try (FileOutputStream fileOutput = new FileOutputStream(destination);
             CountingOutputStream counting =
                     new CountingOutputStream(new BufferedOutputStream(fileOutput));
             DigestOutputStream output =
                     new DigestOutputStream(counting, digest)) {
            writer.write(source, output);
            output.flush();
            fileOutput.getFD().sync();
            if (counting.getCount() < 1L) {
                throw new IOException("Prepared TCX artifact is empty");
            }
            return new WrittenArtifact(
                    counting.getCount(),
                    hex(digest.digest()));
        }
    }

    private void writeManifest(
            File destination,
            ArtifactManifest manifest) throws IOException {
        try (FileOutputStream fileOutput = new FileOutputStream(destination);
             DataOutputStream output = new DataOutputStream(
                     new BufferedOutputStream(fileOutput))) {
            output.writeInt(MANIFEST_MAGIC);
            output.writeInt(MANIFEST_VERSION);
            output.writeUTF(manifest.getArtifactId());
            output.writeUTF(manifest.getKey().getSessionId());
            output.writeLong(manifest.getKey().getRevision());
            output.writeUTF(manifest.getKey().getNormalizedBaseUrl());
            output.writeUTF(manifest.getKey().getConnectionIdentity());
            output.writeUTF(manifest.getKey().getConnectorId());
            output.writeUTF(manifest.getCredentialReference());
            output.writeUTF(manifest.getUploadFilename());
            output.writeLong(manifest.getArtifactLength());
            output.writeUTF(manifest.getArtifactSha256());
            output.writeLong(manifest.getCreatedAtUtcMs());
            writeNullable(output, manifest.getMetadata().getTitle());
            writeNullable(output, manifest.getMetadata().getNotes());
            output.writeInt(manifest.getMetadata().getTags().size());
            for (String tag : manifest.getMetadata().getTags()) {
                output.writeUTF(tag);
            }
            output.writeLong(
                    manifest.getMetadata().getUpdatedAtUtcMs());
            output.flush();
            fileOutput.getFD().sync();
        }
    }

    private void moveDirectoryAtomically(
            File source,
            File destination) throws IOException, PreparedArtifactException {
        try {
            Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            throw new PreparedArtifactException(
                    "ATOMIC_MOVE_UNAVAILABLE",
                    "Prepared TCX persistence requires an atomic directory move",
                    error);
        }
    }

    private void ensureRoot() throws PreparedArtifactException {
        if (!root.exists() && !root.mkdirs()) {
            throw new PreparedArtifactException(
                    "ARTIFACT_STORAGE_UNAVAILABLE",
                    "Could not create prepared TCX storage");
        }
        if (!root.isDirectory()) {
            throw new PreparedArtifactException(
                    "ARTIFACT_STORAGE_UNAVAILABLE",
                    "Prepared TCX storage is not a directory");
        }
        cleanupStagingDirectories();
    }

    private void cleanupStagingDirectories()
            throws PreparedArtifactException {
        File[] staging = root.listFiles(file ->
                file.isDirectory()
                        && file.getName().startsWith(".staging-"));
        if (staging == null) {
            throw new PreparedArtifactException(
                    "ARTIFACT_STORAGE_UNAVAILABLE",
                    "Could not inspect prepared TCX staging files");
        }
        long now = System.currentTimeMillis();
        for (File directory : staging) {
            long modified = directory.lastModified();
            if (modified > 0L
                    && now - modified <= STAGING_STALE_MILLIS) {
                continue;
            }
            deleteStaging(directory);
            if (directory.exists()) {
                throw new PreparedArtifactException(
                        "ARTIFACT_STORAGE_UNAVAILABLE",
                        "Could not remove an interrupted TCX staging directory");
            }
        }
    }

    private File artifactDirectory(String artifactId) {
        requireArtifactId(artifactId);
        return new File(root, artifactId);
    }

    private static void requireArtifactId(String artifactId) {
        if (artifactId == null
                || !ARTIFACT_ID.matcher(artifactId).matches()) {
            throw new IllegalArgumentException(
                    "Prepared artifact identifier is invalid");
        }
    }

    private static void requireIdentity(
            ArtifactManifest manifest,
            EndurainDeliveryKey key,
            String credentialReference) throws PreparedArtifactException {
        if (!manifest.getKey().equals(key)
                || !manifest.getCredentialReference().equals(credentialReference)) {
            throw new PreparedArtifactException(
                    "ARTIFACT_IDENTITY_CONFLICT",
                    "Prepared TCX artifact has different immutable inputs");
        }
    }

    private static String uploadFilename(String sessionId, long revision) {
        return String.format(
                Locale.US,
                "sportlogger-%s-r%d.tcx",
                sessionId.substring(0, 8),
                revision);
    }

    private static String sha256(File file) throws IOException {
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[16 * 1024];
        try (FileInputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        return hex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] value) {
        char[] result = new char[value.length * 2];
        for (int index = 0; index < value.length; index++) {
            int current = value[index] & 0xff;
            result[index * 2] = Character.forDigit(current >>> 4, 16);
            result[index * 2 + 1] = Character.forDigit(current & 0x0f, 16);
        }
        return new String(result);
    }

    private static void writeNullable(
            DataOutputStream output,
            String value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) {
            output.writeUTF(value);
        }
    }

    private static String readNullable(
            DataInputStream input) throws IOException {
        return input.readBoolean() ? input.readUTF() : null;
    }

    private static void deleteStaging(File directory) {
        deleteFile(new File(directory, ARTIFACT_FILE));
        deleteFile(new File(directory, MANIFEST_FILE));
        deleteFile(directory);
    }

    private static void deleteFile(File file) {
        if (file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    public enum ArtifactState {
        READY,
        MISSING,
        LENGTH_MISMATCH,
        SHA256_MISMATCH,
        UNREADABLE,
        MANIFEST_INVALID
    }

    public static final class ArtifactManifest {
        private final String artifactId;
        private final EndurainDeliveryKey key;
        private final String credentialReference;
        private final String uploadFilename;
        private final long artifactLength;
        private final String artifactSha256;
        private final long createdAtUtcMs;
        private final CanonicalTcxExportSource.MetadataSnapshot metadata;

        private ArtifactManifest(
                String artifactId,
                EndurainDeliveryKey key,
                String credentialReference,
                String uploadFilename,
                long artifactLength,
                String artifactSha256,
                long createdAtUtcMs,
                CanonicalTcxExportSource.MetadataSnapshot metadata) {
            this.artifactId = artifactId;
            this.key = key;
            this.credentialReference = credentialReference;
            this.uploadFilename = uploadFilename;
            this.artifactLength = artifactLength;
            this.artifactSha256 = artifactSha256;
            this.createdAtUtcMs = createdAtUtcMs;
            this.metadata = metadata;
        }

        public String getArtifactId() {
            return artifactId;
        }

        public EndurainDeliveryKey getKey() {
            return key;
        }

        public String getCredentialReference() {
            return credentialReference;
        }

        public String getUploadFilename() {
            return uploadFilename;
        }

        public long getArtifactLength() {
            return artifactLength;
        }

        public String getArtifactSha256() {
            return artifactSha256;
        }

        public long getCreatedAtUtcMs() {
            return createdAtUtcMs;
        }

        public CanonicalTcxExportSource.MetadataSnapshot getMetadata() {
            return metadata;
        }
    }

    public static final class PreparedArtifact {
        private final ArtifactManifest manifest;
        private final TcxUploadArtifact uploadArtifact;

        private PreparedArtifact(
                ArtifactManifest manifest,
                TcxUploadArtifact uploadArtifact) {
            this.manifest = manifest;
            this.uploadArtifact = uploadArtifact;
        }

        public ArtifactManifest getManifest() {
            return manifest;
        }

        public TcxUploadArtifact getUploadArtifact() {
            return uploadArtifact;
        }
    }

    public static final class Verification {
        private final ArtifactState state;
        private final TcxUploadArtifact uploadArtifact;

        private Verification(
                ArtifactState state,
                TcxUploadArtifact uploadArtifact) {
            this.state = state;
            this.uploadArtifact = uploadArtifact;
        }

        private static Verification ready(TcxUploadArtifact artifact) {
            return new Verification(ArtifactState.READY, artifact);
        }

        private static Verification failure(ArtifactState state) {
            return new Verification(state, null);
        }

        public ArtifactState getState() {
            return state;
        }

        public boolean isReady() {
            return state == ArtifactState.READY;
        }

        public TcxUploadArtifact getUploadArtifact() {
            if (uploadArtifact == null) {
                throw new IllegalStateException(
                        "Prepared TCX artifact is not ready");
            }
            return uploadArtifact;
        }
    }

    public static final class PreparedArtifactException extends Exception {
        private final String code;

        public PreparedArtifactException(String code, String message) {
            super(message);
            this.code = code;
        }

        public PreparedArtifactException(
                String code,
                String message,
                Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    private static final class WrittenArtifact {
        private final long length;
        private final String sha256;

        private WrittenArtifact(long length, String sha256) {
            this.length = length;
            this.sha256 = sha256;
        }
    }

    private static final class CountingOutputStream
            extends FilterOutputStream {
        private long count;

        private CountingOutputStream(OutputStream output) {
            super(output);
        }

        @Override
        public void write(int value) throws IOException {
            out.write(value);
            count++;
        }

        @Override
        public void write(byte[] value, int offset, int length)
                throws IOException {
            out.write(value, offset, length);
            count += length;
        }

        private long getCount() {
            return count;
        }
    }
}
