package com.long2know.sportlogger.recording;

public interface TransferReceiptStore {
    void acknowledge(String transferId) throws Exception;

    boolean consumeAcknowledgement(String transferId) throws Exception;
}
