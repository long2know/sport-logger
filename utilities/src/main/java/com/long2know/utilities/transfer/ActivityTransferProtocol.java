package com.long2know.utilities.transfer;

import com.long2know.utilities.models.SportActivity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

public final class ActivityTransferProtocol {
    public static final String ASSET_KEY = "sportActivity";
    public static final String ACTIVITY_ID_KEY = "activityId";
    public static final String TRANSFER_ID_KEY = "transferId";
    public static final String PAYLOAD_SHA256_KEY = "payloadSha256";
    public static final String SOURCE_URI_KEY = "sourceUri";
    public static final String ATTEMPT_KEY = "attempt";
    public static final String LEGACY_ALIAS_KEY = "legacyAlias";

    private ActivityTransferProtocol() {
    }

    public static String transferId(SportActivity activity, byte[] payload) {
        Objects.requireNonNull(activity);
        Objects.requireNonNull(payload);
        long startedAt = activity.StartTimeUTC == null
                ? 0L
                : activity.StartTimeUTC.getTime();
        return "v1-" + activity.Id + "-" + startedAt + "-" + sha256Hex(payload);
    }

    public static String payloadSha256(byte[] payload) {
        return sha256Hex(Objects.requireNonNull(payload));
    }

    public static String activityPath(String legacyPath, String transferId) {
        return normalizePath(legacyPath) + "/" + requireSegment(transferId);
    }

    public static String acknowledgementRoot(String legacyPath) {
        return normalizePath(legacyPath) + "-ack";
    }

    public static String acknowledgementPath(
            String legacyPath,
            String sourceNodeId,
            String transferId) {
        String sourceKey = sha256Hex(
                Objects.requireNonNull(sourceNodeId).getBytes(StandardCharsets.UTF_8))
                .substring(0, 16);
        return acknowledgementRoot(legacyPath)
                + "/"
                + sourceKey
                + "/"
                + requireSegment(transferId);
    }

    public static boolean isActivityPath(String legacyPath, String path) {
        if (path == null) {
            return false;
        }
        String normalized = normalizePath(legacyPath);
        return path.equals(normalized) || path.startsWith(normalized + "/");
    }

    public static boolean isAcknowledgementPath(String legacyPath, String path) {
        if (path == null) {
            return false;
        }
        String root = acknowledgementRoot(legacyPath);
        return path.equals(root) || path.startsWith(root + "/");
    }

    public static String transferIdFromActivityPath(String legacyPath, String path) {
        if (!isActivityPath(legacyPath, path)) {
            return null;
        }
        String normalized = normalizePath(legacyPath);
        if (path.equals(normalized)) {
            return null;
        }
        return requireSegment(path.substring(normalized.length() + 1));
    }

    public static String transferIdFromAcknowledgementPath(
            String legacyPath,
            String path) {
        if (!isAcknowledgementPath(legacyPath, path)) {
            return null;
        }
        if (path.equals(acknowledgementRoot(legacyPath))) {
            return null;
        }
        int separator = path.lastIndexOf('/');
        if (separator < 0 || separator == path.length() - 1) {
            return null;
        }
        return requireSegment(path.substring(separator + 1));
    }

    private static String normalizePath(String path) {
        String normalized = Objects.requireNonNull(path).trim();
        if (normalized.isEmpty() || normalized.charAt(0) != '/') {
            throw new IllegalArgumentException("Wear path must start with '/'");
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String requireSegment(String value) {
        String segment = Objects.requireNonNull(value);
        if (segment.isEmpty()
                || segment.indexOf('/') >= 0
                || segment.indexOf('\\') >= 0
                || segment.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid transfer path segment");
        }
        return segment;
    }

    private static String sha256Hex(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(Character.forDigit((item >>> 4) & 0x0f, 16));
                result.append(Character.forDigit(item & 0x0f, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
