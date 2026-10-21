package com.long2know.sportlogger.security;

interface CredentialAead {
    enum KeyStatus {
        PRESENT,
        MISSING,
        UNAVAILABLE
    }

    KeyStatus keyStatus();

    Object transactionLock();

    byte[] seal(byte[] plaintext, byte[] associatedData)
            throws CredentialCryptoException;

    byte[] open(byte[] sealed, byte[] associatedData)
            throws CredentialCryptoException;

    void deleteKey() throws CredentialCryptoException;
}
