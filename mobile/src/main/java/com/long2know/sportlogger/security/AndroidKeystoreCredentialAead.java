package com.long2know.sportlogger.security;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class AndroidKeystoreCredentialAead implements CredentialAead {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int MAGIC = 0x534c4331;
    private static final int VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_SEALED_BYTES = 8192;

    private final String alias;

    AndroidKeystoreCredentialAead(String alias) {
        this.alias = alias;
    }

    @Override
    public KeyStatus keyStatus() {
        try {
            return keyStore().containsAlias(alias)
                    ? KeyStatus.PRESENT
                    : KeyStatus.MISSING;
        } catch (GeneralSecurityException | RuntimeException error) {
            return KeyStatus.UNAVAILABLE;
        }
    }

    @Override
    public byte[] seal(byte[] plaintext, byte[] associatedData)
            throws CredentialCryptoException {
        if (plaintext.length == 0 || plaintext.length > 4096) {
            throw new CredentialCryptoException(
                    CredentialCryptoException.Reason.INVALID_ENVELOPE);
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            cipher.updateAAD(associatedData);
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != IV_BYTES) {
                Arrays.fill(ciphertext, (byte) 0);
                throw new CredentialCryptoException(
                        CredentialCryptoException.Reason.KEY_UNAVAILABLE);
            }
            ByteBuffer envelope = ByteBuffer.allocate(
                    Integer.BYTES + 2 + Integer.BYTES + iv.length + ciphertext.length);
            envelope.putInt(MAGIC);
            envelope.put((byte) VERSION);
            envelope.put((byte) iv.length);
            envelope.putInt(ciphertext.length);
            envelope.put(iv);
            envelope.put(ciphertext);
            Arrays.fill(ciphertext, (byte) 0);
            return envelope.array();
        } catch (CredentialCryptoException error) {
            throw error;
        } catch (GeneralSecurityException | RuntimeException error) {
            throw new CredentialCryptoException(
                    CredentialCryptoException.Reason.KEY_UNAVAILABLE,
                    error);
        }
    }

    @Override
    public byte[] open(byte[] sealed, byte[] associatedData)
            throws CredentialCryptoException {
        Envelope envelope = parseEnvelope(sealed);
        try {
            SecretKey key = existingKey();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(TAG_BITS, envelope.iv));
            cipher.updateAAD(associatedData);
            return cipher.doFinal(envelope.ciphertext);
        } catch (AEADBadTagException error) {
            throw new CredentialCryptoException(
                    CredentialCryptoException.Reason.AUTHENTICATION_FAILED,
                    error);
        } catch (KeyPermanentlyInvalidatedException error) {
            throw new CredentialCryptoException(
                    CredentialCryptoException.Reason.KEY_MISSING,
                    error);
        } catch (CredentialCryptoException error) {
            throw error;
        } catch (GeneralSecurityException | RuntimeException error) {
            throw new CredentialCryptoException(
                    CredentialCryptoException.Reason.KEY_UNAVAILABLE,
                    error);
        } finally {
            envelope.clear();
        }
    }

    @Override
    public void deleteKey() throws CredentialCryptoException {
        try {
            KeyStore store = keyStore();
            if (store.containsAlias(alias)) {
                store.deleteEntry(alias);
            }
        } catch (GeneralSecurityException | RuntimeException error) {
            throw new CredentialCryptoException(
                    CredentialCryptoException.Reason.KEY_UNAVAILABLE,
                    error);
        }
    }

    private SecretKey getOrCreateKey()
            throws GeneralSecurityException, CredentialCryptoException {
        KeyStore store = keyStore();
        if (store.containsAlias(alias)) {
            return existingKey(store);
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private SecretKey existingKey()
            throws GeneralSecurityException, CredentialCryptoException {
        return existingKey(keyStore());
    }

    private SecretKey existingKey(KeyStore store)
            throws GeneralSecurityException, CredentialCryptoException {
        if (!store.containsAlias(alias)) {
            throw new CredentialCryptoException(
                    CredentialCryptoException.Reason.KEY_MISSING);
        }
        java.security.Key key = store.getKey(alias, null);
        if (!(key instanceof SecretKey)) {
            throw new CredentialCryptoException(
                    CredentialCryptoException.Reason.KEY_UNAVAILABLE);
        }
        return (SecretKey) key;
    }

    private static KeyStore keyStore() throws GeneralSecurityException {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        try {
            store.load(null);
        } catch (java.io.IOException error) {
            throw new GeneralSecurityException(error);
        }
        return store;
    }

    private static Envelope parseEnvelope(byte[] sealed)
            throws CredentialCryptoException {
        if (sealed.length < Integer.BYTES + 2 + Integer.BYTES + IV_BYTES + 16
                || sealed.length > MAX_SEALED_BYTES) {
            throw new CredentialCryptoException(
                    CredentialCryptoException.Reason.INVALID_ENVELOPE);
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(sealed);
            int magic = buffer.getInt();
            int version = buffer.get() & 0xff;
            int ivLength = buffer.get() & 0xff;
            int ciphertextLength = buffer.getInt();
            if (magic != MAGIC
                    || version != VERSION
                    || ivLength != IV_BYTES
                    || ciphertextLength < 16
                    || ciphertextLength != buffer.remaining() - ivLength) {
                throw new CredentialCryptoException(
                        CredentialCryptoException.Reason.INVALID_ENVELOPE);
            }
            byte[] iv = new byte[ivLength];
            byte[] ciphertext = new byte[ciphertextLength];
            buffer.get(iv);
            buffer.get(ciphertext);
            return new Envelope(iv, ciphertext);
        } catch (java.nio.BufferUnderflowException error) {
            throw new CredentialCryptoException(
                    CredentialCryptoException.Reason.INVALID_ENVELOPE,
                    error);
        }
    }

    private static final class Envelope {
        private final byte[] iv;
        private final byte[] ciphertext;

        private Envelope(byte[] iv, byte[] ciphertext) {
            this.iv = iv;
            this.ciphertext = ciphertext;
        }

        private void clear() {
            Arrays.fill(iv, (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
        }
    }
}
