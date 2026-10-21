package com.long2know.sportlogger.connector.endurain;

import java.io.File;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public final class TcxUploadArtifact {
    private static final Pattern ARTIFACT_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}");
    private static final Pattern SAFE_FILENAME =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,119}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final String artifactId;
    private final long revision;
    private final File file;
    private final String uploadFilename;
    private final long byteLength;
    private final String sha256;

    public TcxUploadArtifact(
            String artifactId,
            long revision,
            File file,
            String uploadFilename,
            long byteLength,
            String sha256) {
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(uploadFilename, "uploadFilename");
        Objects.requireNonNull(sha256, "sha256");
        if (!ARTIFACT_ID.matcher(artifactId).matches()) {
            throw new IllegalArgumentException(
                    "Artifact ID must be a stable opaque identifier");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("Artifact revision must not be negative");
        }
        if (byteLength < 1) {
            throw new IllegalArgumentException("TCX artifact must not be empty");
        }
        if (!SAFE_FILENAME.matcher(uploadFilename).matches()
                || !uploadFilename.toLowerCase(Locale.ROOT).endsWith(".tcx")) {
            throw new IllegalArgumentException(
                    "Upload filename must be a safe ASCII .tcx filename");
        }
        String normalizedHash = sha256.toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(normalizedHash).matches()) {
            throw new IllegalArgumentException(
                    "Artifact SHA-256 must contain 64 hexadecimal characters");
        }
        this.artifactId = artifactId;
        this.revision = revision;
        this.file = file;
        this.uploadFilename = uploadFilename;
        this.byteLength = byteLength;
        this.sha256 = normalizedHash;
    }

    public String artifactId() {
        return artifactId;
    }

    public long revision() {
        return revision;
    }

    public File file() {
        return file;
    }

    public String uploadFilename() {
        return uploadFilename;
    }

    public long byteLength() {
        return byteLength;
    }

    public String sha256() {
        return sha256;
    }
}
