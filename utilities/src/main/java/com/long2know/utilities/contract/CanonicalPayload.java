package com.long2know.utilities.contract;

import com.google.protobuf.InvalidProtocolBufferException;
import com.long2know.utilities.contract.proto.ActivityFile;
import com.long2know.utilities.contract.proto.ActivityRecord;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class CanonicalPayload {
    public static final int SCHEMA_VERSION = 1;

    private final ActivityFile file;
    private final byte[] exactBytes;

    private CanonicalPayload(ActivityFile file, byte[] exactBytes) {
        this.file = file;
        this.exactBytes = exactBytes;
    }

    public static CanonicalPayload create(ActivityRecord activity) {
        CanonicalActivityValidator.validateCompleted(activity);
        ActivityFile file = ActivityFile.newBuilder()
                .setSchemaVersion(SCHEMA_VERSION)
                .setActivity(activity)
                .build();
        return new CanonicalPayload(file, file.toByteArray());
    }

    public static CanonicalPayload parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new CanonicalContractException(
                    "PAYLOAD_REQUIRED",
                    "Canonical payload bytes are required");
        }
        try {
            byte[] exact = bytes.clone();
            ActivityFile file = ActivityFile.parseFrom(exact);
            if (file.getSchemaVersion() != SCHEMA_VERSION) {
                throw new CanonicalContractException(
                        "SCHEMA_UNSUPPORTED",
                        "Unsupported canonical schema version " + file.getSchemaVersion());
            }
            if (!file.hasActivity()) {
                throw new CanonicalContractException(
                        "ACTIVITY_REQUIRED",
                        "Canonical payload has no activity");
            }
            CanonicalActivityValidator.validateCompleted(file.getActivity());
            return new CanonicalPayload(file, exact);
        } catch (InvalidProtocolBufferException error) {
            throw new CanonicalContractException(
                    "PAYLOAD_CORRUPT",
                    "Canonical payload is not valid protobuf",
                    error);
        }
    }

    public ActivityFile getFile() {
        return file;
    }

    public ActivityRecord getActivity() {
        return file.getActivity();
    }

    public byte[] getExactBytes() {
        return exactBytes.clone();
    }

    public byte[] reserializePreservingUnknownFields() {
        return file.toByteArray();
    }

    public byte[] sha256() {
        try {
            return MessageDigest.getInstance("SHA-256").digest(exactBytes);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    public String sha256Hex() {
        return hex(sha256());
    }

    public static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            result.append(Character.forDigit(value & 0x0f, 16));
        }
        return result.toString();
    }
}
