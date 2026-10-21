package com.long2know.sportlogger.security;

import android.content.Context;

import com.long2know.sportlogger.connector.endurain.EndurainConnectionConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public final class EndurainCredentialStore {
    private static final byte[] AAD_PREFIX =
            "sportlogger:endurain:credential:v1".getBytes(StandardCharsets.UTF_8);
    private static final int MAX_CREDENTIAL_FILE_BYTES = 16 * 1024;
    private static final String CREDENTIAL_SUFFIX = ".credential";
    private static final String TEMPORARY_SUFFIX = ".new";

    private final File directory;
    private final CredentialAead aead;
    private final Object transactionLock;
    private IOException startupCleanupFailure;

    public static EndurainCredentialStore forApplication(Context context) {
        Objects.requireNonNull(context, "context");
        Context applicationContext = context.getApplicationContext();
        String alias = applicationContext.getPackageName()
                + ".endurain.api-key.v1";
        return new EndurainCredentialStore(
                new File(
                        applicationContext.getNoBackupFilesDir(),
                        "connector-credentials-v1"),
                new AndroidKeystoreCredentialAead(alias));
    }

    EndurainCredentialStore(File directory, CredentialAead aead) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.aead = Objects.requireNonNull(aead, "aead");
        transactionLock = Objects.requireNonNull(
                aead.transactionLock(),
                "aead.transactionLock()");
        synchronized (transactionLock) {
            try {
                cleanupAllCredentialTemporaries();
            } catch (IOException error) {
                startupCleanupFailure = error;
            }
        }
    }

    public void store(
            EndurainConnectionConfig config,
            char[] apiKey) throws CredentialStoreException {
        Objects.requireNonNull(config, "config");
        synchronized (transactionLock) {
            EndurainApiKey credential = EndurainApiKey.from(apiKey);
            byte[] plaintext = null;
            byte[] associatedData = associatedData(config);
            byte[] sealed = null;
            try {
                ensureStartupCleanup();
                cleanupScopedTemporaries(credentialFile(config));
                plaintext = credential.encodeUtf8();
                sealed = aead.seal(plaintext, associatedData);
                writeAtomically(credentialFile(config), sealed);
            } catch (CredentialCryptoException error) {
                throw new CredentialStoreException(
                        CredentialStoreException.Reason.KEYSTORE_UNAVAILABLE,
                        error);
            } catch (IOException error) {
                throw new CredentialStoreException(
                        CredentialStoreException.Reason.STORAGE_UNAVAILABLE,
                        error);
            } finally {
                credential.close();
                clear(plaintext);
                clear(associatedData);
                clear(sealed);
            }
        }
    }

    public CredentialReadResult load(EndurainConnectionConfig config) {
        Objects.requireNonNull(config, "config");
        synchronized (transactionLock) {
            File file = credentialFile(config);
            try {
                ensureStartupCleanup();
                cleanupScopedTemporaries(file);
            } catch (IOException error) {
                return CredentialReadResult.of(
                        CredentialReadResult.State.STORAGE_UNAVAILABLE);
            }
            if (!file.isFile()) {
                return CredentialReadResult.of(
                        CredentialReadResult.State.NOT_CONFIGURED);
            }

            CredentialAead.KeyStatus keyStatus = aead.keyStatus();
            if (keyStatus == CredentialAead.KeyStatus.MISSING) {
                return CredentialReadResult.of(
                        CredentialReadResult.State.KEYSTORE_KEY_MISSING);
            }
            if (keyStatus == CredentialAead.KeyStatus.UNAVAILABLE) {
                return CredentialReadResult.of(
                        CredentialReadResult.State.KEYSTORE_UNAVAILABLE);
            }

            byte[] sealed = null;
            byte[] associatedData = associatedData(config);
            byte[] plaintext = null;
            try {
                sealed = readBounded(file);
                plaintext = aead.open(sealed, associatedData);
                return CredentialReadResult.available(
                        EndurainApiKey.decodeUtf8(plaintext));
            } catch (CharacterCodingException | IllegalArgumentException error) {
                return CredentialReadResult.of(
                        CredentialReadResult.State.STORAGE_CORRUPT);
            } catch (CorruptCredentialFileException error) {
                return CredentialReadResult.of(
                        CredentialReadResult.State.STORAGE_CORRUPT);
            } catch (IOException error) {
                return CredentialReadResult.of(
                        CredentialReadResult.State.STORAGE_UNAVAILABLE);
            } catch (CredentialCryptoException error) {
                if (error.reason() == CredentialCryptoException.Reason.KEY_MISSING) {
                    return CredentialReadResult.of(
                            CredentialReadResult.State.KEYSTORE_KEY_MISSING);
                }
                if (error.reason()
                        == CredentialCryptoException.Reason.KEY_UNAVAILABLE) {
                    return CredentialReadResult.of(
                            CredentialReadResult.State.KEYSTORE_UNAVAILABLE);
                }
                return CredentialReadResult.of(
                        CredentialReadResult.State.STORAGE_CORRUPT);
            } finally {
                clear(sealed);
                clear(associatedData);
                clear(plaintext);
            }
        }
    }

    public void reset(EndurainConnectionConfig config)
            throws CredentialStoreException {
        Objects.requireNonNull(config, "config");
        synchronized (transactionLock) {
            File file = credentialFile(config);
            try {
                ensureStartupCleanup();
                deleteFile(file);
                cleanupScopedTemporaries(file);
            } catch (IOException error) {
                throw new CredentialStoreException(
                        CredentialStoreException.Reason.STORAGE_UNAVAILABLE,
                        error);
            }
        }
    }

    public void resetAll() throws CredentialStoreException {
        synchronized (transactionLock) {
            try {
                ensureStartupCleanup();
                deleteAllCredentialFiles();
            } catch (IOException error) {
                throw new CredentialStoreException(
                        CredentialStoreException.Reason.STORAGE_UNAVAILABLE,
                        error);
            }
            try {
                aead.deleteKey();
            } catch (CredentialCryptoException error) {
                throw new CredentialStoreException(
                        CredentialStoreException.Reason.KEYSTORE_UNAVAILABLE,
                        error);
            }
        }
    }

    File credentialFile(EndurainConnectionConfig config) {
        return new File(directory, scopeHash(config) + ".credential");
    }

    private static byte[] associatedData(EndurainConnectionConfig config) {
        byte[] endpoint = config.endpoint()
                .normalizedBaseUrl()
                .getBytes(StandardCharsets.UTF_8);
        byte[] connection = config.connectionIdentity()
                .getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                AAD_PREFIX.length + endpoint.length + connection.length + 2);
        output.write(AAD_PREFIX, 0, AAD_PREFIX.length);
        output.write(0);
        output.write(endpoint, 0, endpoint.length);
        output.write(0);
        output.write(connection, 0, connection.length);
        clear(endpoint);
        clear(connection);
        return output.toByteArray();
    }

    private static String scopeHash(EndurainConnectionConfig config) {
        byte[] scope = (config.endpoint().normalizedBaseUrl()
                + "\0"
                + config.connectionIdentity()).getBytes(StandardCharsets.UTF_8);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(scope);
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte current : digest) {
                value.append(String.format(java.util.Locale.ROOT, "%02x", current));
            }
            clear(digest);
            return value.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        } finally {
            clear(scope);
        }
    }

    private void writeAtomically(File destination, byte[] sealed)
            throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Credential directory could not be created");
        }
        File temporary = new File(
                directory,
                destination.getName() + "." + UUID.randomUUID() + ".new");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(sealed);
                output.getFD().sync();
            }
            temporary.setReadable(false, false);
            temporary.setWritable(false, false);
            temporary.setReadable(true, true);
            temporary.setWritable(true, true);
            try {
                Files.move(
                        temporary.toPath(),
                        destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(
                        temporary.toPath(),
                        destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (temporary.exists()) {
                temporary.delete();
            }
        }
    }

    private void ensureStartupCleanup() throws IOException {
        if (startupCleanupFailure == null) {
            return;
        }
        try {
            cleanupAllCredentialTemporaries();
            startupCleanupFailure = null;
        } catch (IOException error) {
            startupCleanupFailure = error;
            throw error;
        }
    }

    private void cleanupScopedTemporaries(File destination) throws IOException {
        if (!directory.exists()) {
            return;
        }
        File[] files = directory.listFiles(
                (ignored, name) -> name.startsWith(destination.getName() + ".")
                        && isCredentialTemporaryFileName(name));
        deleteListedFiles(files);
    }

    private void cleanupAllCredentialTemporaries() throws IOException {
        if (!directory.exists()) {
            return;
        }
        File[] files = directory.listFiles(
                (ignored, name) -> isCredentialTemporaryFileName(name));
        deleteListedFiles(files);
    }

    private void deleteAllCredentialFiles() throws IOException {
        if (!directory.exists()) {
            return;
        }
        File[] files = directory.listFiles(
                (ignored, name) -> isCredentialFileName(name)
                        || isCredentialTemporaryFileName(name));
        deleteListedFiles(files);
    }

    private void deleteListedFiles(File[] files) throws IOException {
        if (files == null) {
            throw new IOException("Credential directory could not be read");
        }
        for (File file : files) {
            if (file.isFile()) {
                deleteFile(file);
            }
        }
    }

    private static void deleteFile(File file) throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException("Credential file could not be removed");
        }
    }

    private static boolean isCredentialFileName(String name) {
        if (!name.endsWith(CREDENTIAL_SUFFIX)
                || name.length() != 64 + CREDENTIAL_SUFFIX.length()) {
            return false;
        }
        return isLowerHex(name, 0, 64);
    }

    private static boolean isCredentialTemporaryFileName(String name) {
        String marker = CREDENTIAL_SUFFIX + ".";
        if (!name.endsWith(TEMPORARY_SUFFIX)
                || name.length()
                != 64 + marker.length() + 36 + TEMPORARY_SUFFIX.length()
                || !isLowerHex(name, 0, 64)
                || !name.regionMatches(64, marker, 0, marker.length())) {
            return false;
        }
        int uuidStart = 64 + marker.length();
        String uuid = name.substring(
                uuidStart,
                name.length() - TEMPORARY_SUFFIX.length());
        try {
            return UUID.fromString(uuid).toString().equals(uuid);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static boolean isLowerHex(String value, int start, int end) {
        for (int index = start; index < end; index++) {
            char current = value.charAt(index);
            if (!((current >= '0' && current <= '9')
                    || (current >= 'a' && current <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static byte[] readBounded(File file) throws IOException {
        long length = file.length();
        if (length < 1 || length > MAX_CREDENTIAL_FILE_BYTES) {
            throw new CorruptCredentialFileException();
        }
        byte[] bytes = new byte[(int) length];
        int offset = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) {
                    throw new CorruptCredentialFileException();
                }
                offset += read;
            }
            if (input.read() != -1) {
                throw new CorruptCredentialFileException();
            }
        }
        return bytes;
    }

    private static void clear(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static final class CorruptCredentialFileException extends IOException {
    }
}
