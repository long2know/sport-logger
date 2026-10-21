package com.long2know.sportlogger.security;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.long2know.sportlogger.connector.endurain.EndurainConnectionConfig;
import com.long2know.sportlogger.connector.endurain.EndurainEndpoint;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class EndurainCredentialStoreInstrumentedTest {
    private File directory;
    private AndroidKeystoreCredentialAead aead;
    private EndurainCredentialStore store;
    private EndurainConnectionConfig config;

    @Before
    public void setUp() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String suffix = Long.toHexString(System.nanoTime());
        directory = new File(
                context.getNoBackupFilesDir(),
                "endurain-credential-test-" + suffix);
        aead = new AndroidKeystoreCredentialAead(
                context.getPackageName() + ".endurain.test." + suffix);
        store = new EndurainCredentialStore(directory, aead);
        config = new EndurainConnectionConfig(
                EndurainEndpoint.parse("https://localhost"),
                "instrumented-connection");
    }

    @After
    public void tearDown() throws Exception {
        if (store != null) {
            store.resetAll();
        }
        deleteRecursively(directory);
    }

    @Test
    public void androidKeystoreRoundTripTamperLossAndResetAreExplicit()
            throws Exception {
        store.store(config, "instrumented-fixture-key".toCharArray());
        try (CredentialReadResult loaded = store.load(config)) {
            assertEquals(CredentialReadResult.State.AVAILABLE, loaded.state());
        }

        File file = store.credentialFile(config);
        byte[] sealed = read(file);
        sealed[sealed.length - 1] ^= 0x01;
        write(file, sealed);
        assertEquals(
                CredentialReadResult.State.STORAGE_CORRUPT,
                store.load(config).state());

        store.store(config, "replacement-fixture-key".toCharArray());
        aead.deleteKey();
        assertEquals(
                CredentialReadResult.State.KEYSTORE_KEY_MISSING,
                store.load(config).state());

        store.reset(config);
        assertEquals(
                CredentialReadResult.State.NOT_CONFIGURED,
                store.load(config).state());
    }

    private static byte[] read(File file) throws Exception {
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) {
                    throw new AssertionError("Credential fixture ended early");
                }
                offset += read;
            }
        }
        return bytes;
    }

    private static void write(File file, byte[] bytes) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
    }

    private static void deleteRecursively(File file) throws Exception {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!file.delete()) {
            throw new AssertionError("Could not delete credential fixture");
        }
    }
}
