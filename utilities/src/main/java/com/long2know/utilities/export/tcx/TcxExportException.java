package com.long2know.utilities.export.tcx;

public final class TcxExportException extends Exception {
    public enum Code {
        MISSING_START_TIME,
        MISSING_DURATION,
        MISSING_DISTANCE,
        MISSING_CALORIES,
        MISSING_INTENSITY,
        MISSING_TRIGGER_METHOD,
        MISSING_SPORT,
        INVALID_VALUE,
        INVALID_TRACK_POINT,
        OUT_OF_ORDER_TRACK_POINT,
        TOO_MANY_TRACK_POINTS,
        CANCELLED,
        SOURCE_READ_FAILED,
        OUTPUT_WRITE_FAILED
    }

    private final Code code;
    private final String field;

    TcxExportException(Code code, String field, String message) {
        super(message);
        this.code = code;
        this.field = field;
    }

    TcxExportException(Code code, String field, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.field = field;
    }

    public Code getCode() {
        return code;
    }

    public String getField() {
        return field;
    }
}
