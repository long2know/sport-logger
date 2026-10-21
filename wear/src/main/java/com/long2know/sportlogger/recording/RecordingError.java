package com.long2know.sportlogger.recording;

import java.util.Objects;

public final class RecordingError {
    public enum Code {
        COMMAND_REJECTED,
        DURABILITY,
        STORAGE,
        EXPORT,
        ACQUISITION,
        FOREGROUND,
        RECOVERY,
        BOOT_INTERRUPTION
    }

    private final Code code;
    private final String message;

    public RecordingError(Code code, String message) {
        this.code = Objects.requireNonNull(code);
        this.message = message == null ? "" : message;
    }

    public Code getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RecordingError)) {
            return false;
        }
        RecordingError that = (RecordingError) other;
        return code == that.code && message.equals(that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, message);
    }
}
