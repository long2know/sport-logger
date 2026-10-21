package com.long2know.sportlogger.publishing.endurain.factory;

import com.long2know.sportlogger.publishing.endurain.EndurainDeliveryKey;

public final class EndurainActionResult {
    public enum Code {
        COMPLETED,
        QUEUED,
        CREDENTIAL_REQUIRED,
        ARTIFACT_REPAIR_REQUIRED,
        RETRY_NOT_DUE,
        RECONCILIATION_REQUIRED,
        UNSUPPORTED_REMOTE_EDIT,
        UNSUPPORTED_REMOTE_DELETE,
        INVALID_STATE
    }

    private final Code code;
    private final EndurainDeliveryKey deliveryKey;
    private final String message;

    EndurainActionResult(
            Code code,
            EndurainDeliveryKey deliveryKey,
            String message) {
        this.code = code;
        this.deliveryKey = deliveryKey;
        this.message = message;
    }

    public static EndurainActionResult of(
            Code code,
            EndurainDeliveryKey deliveryKey,
            String message) {
        return new EndurainActionResult(code, deliveryKey, message);
    }

    public Code getCode() {
        return code;
    }

    public EndurainDeliveryKey getDeliveryKey() {
        return deliveryKey;
    }

    public String getMessage() {
        return message;
    }
}
