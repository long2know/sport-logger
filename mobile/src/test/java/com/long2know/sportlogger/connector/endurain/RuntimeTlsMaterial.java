package com.long2know.sportlogger.connector.endurain;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

final class RuntimeTlsMaterial implements AutoCloseable {
    private static final char[] STORE_PASSPHRASE = "fixture-pass".toCharArray();

    private final File directory;
    private final SSLContext serverContext;
    private final SSLSocketFactory pinnedCaSocketFactory;
    private final X509TrustManager pinnedCaTrustManager;

    private RuntimeTlsMaterial(
            File directory,
            SSLContext serverContext,
            SSLSocketFactory pinnedCaSocketFactory,
            X509TrustManager pinnedCaTrustManager) {
        this.directory = directory;
        this.serverContext = serverContext;
        this.pinnedCaSocketFactory = pinnedCaSocketFactory;
        this.pinnedCaTrustManager = pinnedCaTrustManager;
    }

    static RuntimeTlsMaterial create(File directory) throws Exception {
        deleteRecursively(directory);
        if (!directory.mkdirs()) {
            throw new IllegalStateException("Could not create TLS fixture directory");
        }

        try {
            File caStore = new File(directory, "ca.p12");
            File serverStore = new File(directory, "server.p12");
            File request = new File(directory, "server.csr");
            File serverCertificate = new File(directory, "server.crt");
            File caCertificate = new File(directory, "ca.crt");

            runKeytool(directory,
                "-genkeypair",
                "-alias", "test-ca",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "2",
                "-dname", "CN=SportLogger Test CA",
                "-ext", "bc=ca:true",
                "-ext", "KU=keyCertSign,cRLSign",
                "-storetype", "PKCS12",
                "-keystore", caStore.getAbsolutePath(),
                "-storepass", storePassphrase(),
                "-keypass", storePassphrase(),
                "-noprompt");
            runKeytool(directory,
                "-genkeypair",
                "-alias", "localhost",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "2",
                "-dname", "CN=localhost",
                "-ext", "bc=ca:false",
                "-ext", "SAN=dns:localhost",
                "-ext", "EKU=serverAuth",
                "-storetype", "PKCS12",
                "-keystore", serverStore.getAbsolutePath(),
                "-storepass", storePassphrase(),
                "-keypass", storePassphrase(),
                "-noprompt");
            runKeytool(directory,
                "-certreq",
                "-alias", "localhost",
                "-keystore", serverStore.getAbsolutePath(),
                "-storepass", storePassphrase(),
                "-file", request.getAbsolutePath());
            runKeytool(directory,
                "-gencert",
                "-alias", "test-ca",
                "-keystore", caStore.getAbsolutePath(),
                "-storepass", storePassphrase(),
                "-infile", request.getAbsolutePath(),
                "-outfile", serverCertificate.getAbsolutePath(),
                "-rfc",
                "-validity", "2",
                "-ext", "bc=ca:false",
                "-ext", "SAN=dns:localhost",
                "-ext", "KU=digitalSignature,keyEncipherment",
                "-ext", "EKU=serverAuth");
            runKeytool(directory,
                "-exportcert",
                "-alias", "test-ca",
                "-keystore", caStore.getAbsolutePath(),
                "-storepass", storePassphrase(),
                "-file", caCertificate.getAbsolutePath(),
                "-rfc");
            runKeytool(directory,
                "-importcert",
                "-noprompt",
                "-alias", "test-ca",
                "-file", caCertificate.getAbsolutePath(),
                "-keystore", serverStore.getAbsolutePath(),
                "-storepass", storePassphrase());
            runKeytool(directory,
                "-importcert",
                "-noprompt",
                "-alias", "localhost",
                "-file", serverCertificate.getAbsolutePath(),
                "-keystore", serverStore.getAbsolutePath(),
                "-storepass", storePassphrase());

            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (FileInputStream input = new FileInputStream(serverStore)) {
                keys.load(input, STORE_PASSPHRASE);
            }
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keys, STORE_PASSPHRASE);
            SSLContext server = SSLContext.getInstance("TLS");
            server.init(keyManagers.getKeyManagers(), null, null);

            byte[] caBytes = java.nio.file.Files.readAllBytes(caCertificate.toPath());
            Certificate ca = CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(caBytes));
            Arrays.fill(caBytes, (byte) 0);
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null);
            trust.setCertificateEntry("test-ca", ca);
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trust);
            X509TrustManager clientTrustManager = null;
            for (TrustManager trustManager : trustManagers.getTrustManagers()) {
                if (trustManager instanceof X509TrustManager) {
                    clientTrustManager = (X509TrustManager) trustManager;
                    break;
                }
            }
            if (clientTrustManager == null) {
                throw new IllegalStateException("Fixture X.509 trust manager is unavailable");
            }
            SSLContext client = SSLContext.getInstance("TLS");
            client.init(null, new TrustManager[]{clientTrustManager}, null);

            return new RuntimeTlsMaterial(
                directory,
                server,
                client.getSocketFactory(),
                clientTrustManager);
        } catch (Exception error) {
            try {
                deleteRecursively(directory);
            } catch (Exception cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
    }

    SSLContext serverContext() {
        return serverContext;
    }

    SSLSocketFactory pinnedCaSocketFactory() {
        return pinnedCaSocketFactory;
    }

    X509TrustManager pinnedCaTrustManager() {
        return pinnedCaTrustManager;
    }

    @Override
    public void close() throws Exception {
        deleteRecursively(directory);
    }

    private static void runKeytool(File directory, String... arguments)
            throws Exception {
        String executable = new File(
                System.getProperty("java.home"),
                "bin/keytool").getAbsolutePath();
        List<String> command = new ArrayList<>(arguments.length + 1);
        command.add(executable);
        command.addAll(Arrays.asList(arguments));
        Process process = new ProcessBuilder(command)
                .directory(directory)
                .redirectErrorStream(true)
                .start();
        byte[] output;
        try (java.io.InputStream input = process.getInputStream()) {
            output = readAll(input);
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException(
                    "keytool failed: "
                            + new String(output, StandardCharsets.UTF_8));
        }
        Arrays.fill(output, (byte) 0);
    }

    private static byte[] readAll(java.io.InputStream input) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String storePassphrase() {
        return new String(STORE_PASSPHRASE);
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
            throw new IllegalStateException("Could not delete TLS fixture file");
        }
    }
}
