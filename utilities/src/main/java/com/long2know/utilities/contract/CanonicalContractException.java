package com.long2know.utilities.contract;

public final class CanonicalContractException extends IllegalArgumentException {
    private final String code;

    public CanonicalContractException(String code, String message) {
        super(message);
        this.code = code;
    }

    public CanonicalContractException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
