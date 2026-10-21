package com.long2know.sportlogger.security;

import com.long2know.sportlogger.connector.endurain.EndurainConnectionConfig;
import com.long2know.sportlogger.connector.endurain.EndurainEndpoint;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.security.Key;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class EndurainCredentialStoreTest {
    private File directory;
    private TestCredentialAead aead;
    private EndurainCredentialStore store;
    private EndurainConnectionConfig config;

    @Before
    public void setUp() throws Exception {
        directory = new File(
                "build/test-data/endurain-credentials-" + System.nanoTime());
        deleteRecursively(directory);
        aead = new TestCredentialAead();
        store = new EndurainCredentialStore(directory, aead);
        config = new EndurainConnectionConfig(
                EndurainEndpoint.parse("https://example.com"),
                "connection-a");
    }

    @After
    public void tearDown() throws Exception {
        deleteRecursively(directory);
    }

    @Test
    public void credentialIsEncryptedScopedAndLoadableWithoutPlaintextPrefs()
            throws Exception {
        char[] value = "ephemeral-test-key".toCharArray();

        store.store(config, value);
        Arrays.fill(value, '\0');

        byte[] stored = read(store.credentialFile(config));
        assertFalse(new String(stored, java.nio.charset.StandardCharsets.UTF_8)
                .contains("ephemeral-test-key"));
        try (CredentialReadResult result = store.load(config)) {
            assertEquals(CredentialReadResult.State.AVAILABLE, result.state());
            RecordingHttpsConnection connection = new RecordingHttpsConnection();
            result.credential().applyTo(connection);
            assertEquals("ephemeral-test-key", connection.getRequestProperty("X-API-Key"));
            assertEquals("EndurainApiKey[redacted]", result.credential().toString());
        }
    }

    @Test
    public void everyWriteUsesFreshAesGcmIv() throws Exception {
        store.store(config, "same-test-key".toCharArray());
        byte[] first = read(store.credentialFile(config));

        store.store(config, "same-test-key".toCharArray());
        byte[] second = read(store.credentialFile(config));

        assertFalse(Arrays.equals(first, second));
    }

    @Test
    public void tamperWrongScopeAndKeystoreLossReturnExplicitStates()
            throws Exception {
        store.store(config, "tamper-test-key".toCharArray());
        File file = store.credentialFile(config);
        byte[] sealed = read(file);
        sealed[sealed.length - 1] ^= 0x01;
        write(file, sealed);

        assertEquals(
                CredentialReadResult.State.STORAGE_CORRUPT,
                store.load(config).state());

        store.store(config, "scope-test-key".toCharArray());
        EndurainConnectionConfig other = new EndurainConnectionConfig(
                EndurainEndpoint.parse("https://example.com"),
                "connection-b");
        write(store.credentialFile(other), read(store.credentialFile(config)));
        assertEquals(
                CredentialReadResult.State.STORAGE_CORRUPT,
                store.load(other).state());

        aead.deleteKey();
        assertEquals(
                CredentialReadResult.State.KEYSTORE_KEY_MISSING,
                store.load(config).state());
    }

    @Test
    public void resetRequiresReentryAndResetAllRemovesKey() throws Exception {
        store.store(config, "reset-test-key".toCharArray());
        assertTrue(store.credentialFile(config).isFile());

        store.reset(config);

        assertFalse(store.credentialFile(config).exists());
        assertEquals(
                CredentialReadResult.State.NOT_CONFIGURED,
                store.load(config).state());

        store.store(config, "reset-all-test-key".toCharArray());
        store.resetAll();
        assertEquals(CredentialAead.KeyStatus.MISSING, aead.keyStatus());
        assertFalse(store.credentialFile(config).exists());
    }

    @Test
    public void connectionScopeProducesStableNonRevealingFilename() throws Exception {
        store.store(config, "filename-test-key".toCharArray());

        String name = store.credentialFile(config).getName();

        assertTrue(name.matches("[0-9a-f]{64}\\.credential"));
        assertFalse(name.contains("example"));
        assertFalse(name.contains("connection"));
        assertNotEquals("filename-test-key", name);
    }

    private static byte[] read(File file) throws Exception {
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int count = input.read(data, offset, data.length - offset);
                if (count < 0) {
                    throw new AssertionError("Unexpected end of fixture file");
                }
                offset += count;
            }
        }
        return data;
    }

    private static void write(File file, byte[] data) throws Exception {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new AssertionError("Could not create fixture directory");
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(data);
        }
    }

    private static void deleteRecursively(File file) throws Exception {
        if (!file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!file.delete()) {
            throw new AssertionError("Could not delete fixture file");
        }
    }

    private static final class RecordingHttpsConnection extends HttpsURLConnection {
        private RecordingHttpsConnection() throws Exception {
            super(new URL("https://localhost"));
        }

        @Override
        public void disconnect() {
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
        }

        @Override
        public String getCipherSuite() {
            return null;
        }

        @Override
        public java.security.cert.Certificate[] getLocalCertificates() {
            return null;
        }

        @Override
        public java.security.cert.Certificate[] getServerCertificates() {
            return null;
        }
    }

    private static final class TestCredentialAead implements CredentialAead {
        private static final int IV_BYTES = 12;
        private static final int TAG_BITS = 128;

        private final SecureRandom random = new SecureRandom();
        private SecretKey key;

        private TestCredentialAead() throws Exception {
            createKey();
        }

        @Override
        public KeyStatus keyStatus() {
            return key == null ? KeyStatus.MISSING : KeyStatus.PRESENT;
        }

        @Override
        public byte[] seal(byte[] plaintext, byte[] associatedData)
                throws CredentialCryptoException {
            if (key == null) {
                try {
                    createKey();
                } catch (Exception error) {
                    throw new CredentialCryptoException(
                            CredentialCryptoException.Reason.KEY_UNAVAILABLE,
                            error);
                }
            }
            try {
                byte[] iv = new byte[IV_BYTES];
                random.nextBytes(iv);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(
                        Cipher.ENCRYPT_MODE,
                        key,
                        new GCMParameterSpec(TAG_BITS, iv));
                cipher.updateAAD(associatedData);
                byte[] encrypted = cipher.doFinal(plaintext);
                return ByteBuffer.allocate(IV_BYTES + encrypted.length)
                        .put(iv)
                        .put(encrypted)
                        .array();
            } catch (Exception error) {
                throw new CredentialCryptoException(
                        CredentialCryptoException.Reason.KEY_UNAVAILABLE,
                        error);
            }
        }

        @Override
        public byte[] open(byte[] sealed, byte[] associatedData)
                throws CredentialCryptoException {
            if (key == null) {
                throw new CredentialCryptoException(
                        CredentialCryptoException.Reason.KEY_MISSING);
            }
            if (sealed.length <= IV_BYTES) {
                throw new CredentialCryptoException(
                        CredentialCryptoException.Reason.INVALID_ENVELOPE);
            }
            try {
                byte[] iv = Arrays.copyOfRange(sealed, 0, IV_BYTES);
                byte[] encrypted = Arrays.copyOfRange(
                        sealed,
                        IV_BYTES,
                        sealed.length);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(
                        Cipher.DECRYPT_MODE,
                        key,
                        new GCMParameterSpec(TAG_BITS, iv));
                cipher.updateAAD(associatedData);
                return cipher.doFinal(encrypted);
            } catch (AEADBadTagException error) {
                throw new CredentialCryptoException(
                        CredentialCryptoException.Reason.AUTHENTICATION_FAILED,
                        error);
            } catch (Exception error) {
                throw new CredentialCryptoException(
                        CredentialCryptoException.Reason.KEY_UNAVAILABLE,
                        error);
            }
        }

        @Override
        public void deleteKey() {
            key = null;
        }

        private void createKey() throws Exception {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            Key generated = generator.generateKey();
            key = (SecretKey) generated;
        }
    }
}
