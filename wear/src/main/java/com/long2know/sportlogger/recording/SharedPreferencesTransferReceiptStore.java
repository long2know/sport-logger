package com.long2know.sportlogger.recording;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class SharedPreferencesTransferReceiptStore implements TransferReceiptStore {
    private static final Object LOCK = new Object();
    private static final String PREFERENCES_NAME = "sport_logger_transfer_receipts";
    private static final String KEY_PREFIX = "ack_";
    private static final int MAX_RECEIPTS = 128;

    private final SharedPreferences preferences;

    public SharedPreferencesTransferReceiptStore(Context context) {
        this(context.getApplicationContext().getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE));
    }

    SharedPreferencesTransferReceiptStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    @Override
    public void acknowledge(String transferId) {
        synchronized (LOCK) {
            String key = key(transferId);
            SharedPreferences.Editor editor = preferences.edit()
                    .putLong(key, System.currentTimeMillis());
            List<Map.Entry<String, ?>> receipts = new ArrayList<>();
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                if (entry.getKey().startsWith(KEY_PREFIX) && !entry.getKey().equals(key)) {
                    receipts.add(entry);
                }
            }
            receipts.sort(Comparator.comparingLong(SharedPreferencesTransferReceiptStore::time));
            int removeCount = Math.max(0, receipts.size() - MAX_RECEIPTS + 1);
            for (int index = 0; index < removeCount; index++) {
                editor.remove(receipts.get(index).getKey());
            }
            if (!editor.commit()) {
                throw new IllegalStateException("Transfer acknowledgement was not persisted");
            }
        }
    }

    @Override
    public boolean consumeAcknowledgement(String transferId) {
        synchronized (LOCK) {
            String key = key(transferId);
            if (!preferences.contains(key)) {
                return false;
            }
            if (!preferences.edit().remove(key).commit()) {
                throw new IllegalStateException("Transfer acknowledgement was not consumed");
            }
            return true;
        }
    }

    private static String key(String transferId) {
        if (transferId == null || transferId.isEmpty()) {
            throw new IllegalArgumentException("Transfer identifier is required");
        }
        return KEY_PREFIX + transferId;
    }

    private static long time(Map.Entry<String, ?> entry) {
        Object value = entry.getValue();
        return value instanceof Long ? (Long) value : Long.MIN_VALUE;
    }
}
