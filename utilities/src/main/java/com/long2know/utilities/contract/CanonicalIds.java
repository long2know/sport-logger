package com.long2know.utilities.contract;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

public final class CanonicalIds {
    private static final UUID LEGACY_SESSION_NAMESPACE =
            UUID.fromString("43af8442-24de-5c9a-aef9-57fa9af423bd");
    private static final UUID LEGACY_TRANSFER_NAMESPACE =
            UUID.fromString("72944e5f-d07a-5bc8-a30d-73ed92f62220");

    private CanonicalIds() {
    }

    public static String newSessionId() {
        return UUID.randomUUID().toString();
    }

    public static String newTransferId() {
        return UUID.randomUUID().toString();
    }

    public static String deterministicLegacySessionId(
            String sourceIdentity,
            long legacyActivityId) {
        if (sourceIdentity == null || sourceIdentity.isEmpty()) {
            throw new CanonicalContractException(
                    "SOURCE_IDENTITY_REQUIRED",
                    "Legacy source identity is required");
        }
        if (legacyActivityId <= 0L) {
            throw new CanonicalContractException(
                    "LEGACY_ACTIVITY_ID_INVALID",
                    "Legacy activity identifier must be positive");
        }
        return uuidV5(
                LEGACY_SESSION_NAMESPACE,
                sourceIdentity + "\u0000" + legacyActivityId).toString();
    }

    public static String deterministicLegacyTransferId(
            String sourceIdentity,
            long legacyActivityId,
            long revision) {
        if (sourceIdentity == null || sourceIdentity.isEmpty()) {
            throw new CanonicalContractException(
                    "SOURCE_IDENTITY_REQUIRED",
                    "Legacy source identity is required");
        }
        if (legacyActivityId <= 0L || revision <= 0L) {
            throw new CanonicalContractException(
                    "LEGACY_TRANSFER_ID_INVALID",
                    "Legacy activity identifier and revision must be positive");
        }
        return uuidV5(
                LEGACY_TRANSFER_NAMESPACE,
                sourceIdentity + "\u0000" + legacyActivityId + "\u0000" + revision).toString();
    }

    public static String requireUuid(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new CanonicalContractException(
                    "UUID_REQUIRED",
                    label + " is required");
        }
        try {
            UUID parsed = UUID.fromString(value);
            String canonical = parsed.toString();
            if (!canonical.equals(value.toLowerCase())) {
                throw new IllegalArgumentException("not canonical");
            }
            return canonical;
        } catch (IllegalArgumentException error) {
            throw new CanonicalContractException(
                    "UUID_INVALID",
                    label + " must be a canonical UUID",
                    error);
        }
    }

    private static UUID uuidV5(UUID namespace, String name) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(uuidBytes(namespace));
            byte[] bytes = digest.digest(name.getBytes(StandardCharsets.UTF_8));
            bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x50);
            bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
            ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, 16);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-1 is unavailable", error);
        }
    }

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }
}
