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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;

import okhttp3.Request;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
    public void androidKeystoreInstancesShareAliasTransactionBoundary() {
        String alias = "com.long2know.sportlogger.test."
                + Long.toHexString(System.nanoTime());

        AndroidKeystoreCredentialAead first =
                new AndroidKeystoreCredentialAead(alias);
        AndroidKeystoreCredentialAead second =
                new AndroidKeystoreCredentialAead(alias);

        assertSame(first.transactionLock(), second.transactionLock());
    }

    @Test
    public void apiKeyOnlyAppliesToHttpsOkHttpRequests() {
        EndurainApiKey key = EndurainApiKey.from(
                "okhttp-test-key".toCharArray());
        try {
            Request.Builder https = new Request.Builder()
                    .url("https://example.com")
                    .get();
            key.applyTo(https);
            assertEquals(
                    "okhttp-test-key",
                    https.build().header("X-API-Key"));

            try {
                key.applyTo(new Request.Builder()
                        .url("http://example.com")
                        .get());
                fail("Expected cleartext request to reject the API key");
            } catch (IllegalArgumentException expected) {
                assertFalse(expected.getMessage().isEmpty());
            }
        } finally {
            key.close();
        }
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

    @Test
    public void twoStoreInstancesSerializeStoreAgainstResetAll() throws Exception {
        BlockingCredentialBoundary boundary = new BlockingCredentialBoundary();
        EndurainCredentialStore writingStore =
                new EndurainCredentialStore(directory, boundary.newAeadInstance());
        EndurainCredentialStore resettingStore =
                new EndurainCredentialStore(directory, boundary.newAeadInstance());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch resetAttempted = new CountDownLatch(1);
        try {
            Future<Void> write = executor.submit(() -> {
                writingStore.store(config, "race-test-key".toCharArray());
                return null;
            });
            assertTrue(boundary.sealEntered.await(5, TimeUnit.SECONDS));

            Future<Void> reset = executor.submit(() -> {
                resetAttempted.countDown();
                resettingStore.resetAll();
                return null;
            });
            assertTrue(resetAttempted.await(5, TimeUnit.SECONDS));
            assertFalse(boundary.deleteKeyEntered.await(
                    250,
                    TimeUnit.MILLISECONDS));

            boundary.allowSeal.countDown();
            write.get(5, TimeUnit.SECONDS);
            reset.get(5, TimeUnit.SECONDS);

            assertEquals(
                    CredentialAead.KeyStatus.MISSING,
                    boundary.keyStatus());
            assertFalse(writingStore.credentialFile(config).exists());
            assertEquals(
                    CredentialReadResult.State.NOT_CONFIGURED,
                    writingStore.load(config).state());
        } finally {
            boundary.allowSeal.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void resetDeletesScopedCrashTemporaryAndRetainsUnrelatedFiles()
            throws Exception {
        store.store(config, "crash-state-key".toCharArray());
        File scopedTemporary = crashTemporary(store.credentialFile(config));
        write(scopedTemporary, read(store.credentialFile(config)));

        EndurainConnectionConfig other = new EndurainConnectionConfig(
                EndurainEndpoint.parse("https://example.com"),
                "connection-other");
        store.store(other, "other-key".toCharArray());
        File otherTemporary = crashTemporary(store.credentialFile(other));
        write(otherTemporary, read(store.credentialFile(other)));
        File unrelated = new File(directory, "unrelated.new");
        write(unrelated, new byte[]{1, 2, 3});

        store.reset(config);

        assertFalse(store.credentialFile(config).exists());
        assertFalse(scopedTemporary.exists());
        assertTrue(store.credentialFile(other).exists());
        assertTrue(otherTemporary.exists());
        assertTrue(unrelated.exists());
        assertEquals(CredentialAead.KeyStatus.PRESENT, aead.keyStatus());
    }

    @Test
    public void constructionCleansOnlyRecognizedCrashTemporaries()
            throws Exception {
        store.store(config, "startup-cleanup-key".toCharArray());
        File recognized = crashTemporary(store.credentialFile(config));
        write(recognized, read(store.credentialFile(config)));
        File invalidUuid = new File(
                directory,
                store.credentialFile(config).getName() + ".not-a-uuid.new");
        write(invalidUuid, new byte[]{4, 5, 6});
        File unrelated = new File(directory, "unrelated.new");
        write(unrelated, new byte[]{7, 8, 9});

        EndurainCredentialStore restarted =
                new EndurainCredentialStore(directory, aead);

        assertFalse(recognized.exists());
        assertTrue(invalidUuid.exists());
        assertTrue(unrelated.exists());
        try (CredentialReadResult loaded = restarted.load(config)) {
            assertEquals(CredentialReadResult.State.AVAILABLE, loaded.state());
        }
    }

    private static File crashTemporary(File credentialFile) {
        return new File(
                credentialFile.getParentFile(),
                credentialFile.getName() + "." + UUID.randomUUID() + ".new");
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
        private final Object transactionLock = new Object();
        private SecretKey key;

        private TestCredentialAead() throws Exception {
            createKey();
        }

        @Override
        public KeyStatus keyStatus() {
            return key == null ? KeyStatus.MISSING : KeyStatus.PRESENT;
        }

        @Override
        public Object transactionLock() {
            return transactionLock;
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

    private static final class BlockingCredentialBoundary {
        private final TestCredentialAead delegate;
        private final CountDownLatch sealEntered = new CountDownLatch(1);
        private final CountDownLatch allowSeal = new CountDownLatch(1);
        private final CountDownLatch deleteKeyEntered = new CountDownLatch(1);

        private BlockingCredentialBoundary() throws Exception {
            delegate = new TestCredentialAead();
        }

        private CredentialAead newAeadInstance() {
            return new BlockingCredentialAead(this);
        }

        private CredentialAead.KeyStatus keyStatus() {
            return delegate.keyStatus();
        }
    }

    private static final class BlockingCredentialAead implements CredentialAead {
        private final BlockingCredentialBoundary boundary;

        private BlockingCredentialAead(BlockingCredentialBoundary boundary) {
            this.boundary = boundary;
        }

        @Override
        public KeyStatus keyStatus() {
            return boundary.delegate.keyStatus();
        }

        @Override
        public Object transactionLock() {
            return boundary.delegate.transactionLock();
        }

        @Override
        public byte[] seal(byte[] plaintext, byte[] associatedData)
                throws CredentialCryptoException {
            boundary.sealEntered.countDown();
            try {
                if (!boundary.allowSeal.await(5, TimeUnit.SECONDS)) {
                    throw new CredentialCryptoException(
                            CredentialCryptoException.Reason.KEY_UNAVAILABLE);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new CredentialCryptoException(
                        CredentialCryptoException.Reason.KEY_UNAVAILABLE,
                        error);
            }
            return boundary.delegate.seal(plaintext, associatedData);
        }

        @Override
        public byte[] open(byte[] sealed, byte[] associatedData)
                throws CredentialCryptoException {
            return boundary.delegate.open(sealed, associatedData);
        }

        @Override
        public void deleteKey() {
            boundary.deleteKeyEntered.countDown();
            boundary.delegate.deleteKey();
        }
    }
}
