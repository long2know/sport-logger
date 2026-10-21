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

    private final File directory;
    private final CredentialAead aead;

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
    }

    public synchronized void store(
            EndurainConnectionConfig config,
            char[] apiKey) throws CredentialStoreException {
        Objects.requireNonNull(config, "config");
        EndurainApiKey credential = EndurainApiKey.from(apiKey);
        byte[] plaintext = null;
        byte[] associatedData = associatedData(config);
        byte[] sealed = null;
        try {
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

    public synchronized CredentialReadResult load(EndurainConnectionConfig config) {
        Objects.requireNonNull(config, "config");
        File file = credentialFile(config);
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

    public synchronized void reset(EndurainConnectionConfig config)
            throws CredentialStoreException {
        Objects.requireNonNull(config, "config");
        File file = credentialFile(config);
        if (file.exists() && !file.delete()) {
            throw new CredentialStoreException(
                    CredentialStoreException.Reason.STORAGE_UNAVAILABLE,
                    new IOException("Credential file could not be removed"));
        }
    }

    public synchronized void resetAll() throws CredentialStoreException {
        File[] files = directory.listFiles(
                (ignored, name) -> name.endsWith(".credential")
                        || name.endsWith(".new"));
        if (files != null) {
            for (File file : files) {
                if (file.exists() && !file.delete()) {
                    throw new CredentialStoreException(
                            CredentialStoreException.Reason.STORAGE_UNAVAILABLE,
                            new IOException("Credential file could not be removed"));
                }
            }
        } else if (directory.exists()) {
            throw new CredentialStoreException(
                    CredentialStoreException.Reason.STORAGE_UNAVAILABLE,
                    new IOException("Credential directory could not be read"));
        }
        try {
            aead.deleteKey();
        } catch (CredentialCryptoException error) {
            throw new CredentialStoreException(
                    CredentialStoreException.Reason.KEYSTORE_UNAVAILABLE,
                    error);
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
