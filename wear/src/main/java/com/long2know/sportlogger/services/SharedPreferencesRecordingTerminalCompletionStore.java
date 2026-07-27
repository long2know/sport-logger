package com.long2know.sportlogger.services;

import android.content.Context;
import android.content.SharedPreferences;

final class SharedPreferencesRecordingTerminalCompletionStore
        implements RecordingTerminalCompletionState.Store {
    private static final String PREFERENCES_NAME =
            "sport_logger_terminal_export_handoff";
    private static final String KEY_LAST_OPERATION_ID = "last_operation_id";
    private static final String KEY_OPERATION_ID = "operation_id";
    private static final String KEY_ACTIVITY_ID = "activity_id";
    private static final String KEY_GENERATION = "generation";
    private static final String KEY_TYPE = "type";
    private static final String KEY_RESULT = "result";

    private final SharedPreferences _preferences;
    private final RecordingPersistenceBarrier _persistenceBarrier;
    private final RecordingPersistenceBarrier.Epoch _epoch;

    SharedPreferencesRecordingTerminalCompletionStore(
            Context context,
            RecordingPersistenceBarrier persistenceBarrier,
            RecordingPersistenceBarrier.Epoch epoch) {
        _preferences = context.getApplicationContext().getSharedPreferences(
                PREFERENCES_NAME, Context.MODE_PRIVATE);
        _persistenceBarrier = persistenceBarrier;
        _epoch = epoch;
    }

    @Override
    public RecordingTerminalCompletionState.Snapshot load() {
        return _persistenceBarrier.read(
                _epoch,
                RecordingTerminalCompletionState.Snapshot.empty(0L),
                new RecordingPersistenceBarrier.Operation<
                        RecordingTerminalCompletionState.Snapshot>() {
                    @Override
                    public RecordingTerminalCompletionState.Snapshot run() {
                        long lastOperationId =
                                _preferences.getLong(
                                        KEY_LAST_OPERATION_ID, 0L);
                        long operationId =
                                _preferences.getLong(KEY_OPERATION_ID, 0L);
                        if (operationId <= 0L) {
                            return RecordingTerminalCompletionState.Snapshot
                                    .empty(lastOperationId);
                        }

                        try {
                            RecordingTerminalCompletion completion =
                                    new RecordingTerminalCompletion(
                                            operationId,
                                            _preferences.getInt(
                                                    KEY_ACTIVITY_ID, 0),
                                            _preferences.getLong(
                                                    KEY_GENERATION, 0L),
                                            RecordingTerminalCompletion.Type
                                                    .valueOf(
                                                            _preferences
                                                                    .getString(
                                                                            KEY_TYPE,
                                                                            "")),
                                            RecordingTerminalCompletion.Result
                                                    .valueOf(
                                                            _preferences
                                                                    .getString(
                                                                            KEY_RESULT,
                                                                            "")));
                            return RecordingTerminalCompletionState.Snapshot
                                    .pending(lastOperationId, completion);
                        } catch (IllegalArgumentException exception) {
                            return RecordingTerminalCompletionState.Snapshot
                                    .empty(Math.max(
                                            lastOperationId, operationId));
                        }
                    }
                });
    }

    @Override
    public boolean save(
            RecordingTerminalCompletionState.Snapshot snapshot) {
        RecordingTerminalCompletion pending = snapshot.getPending();
        if (pending == null) {
            return false;
        }
        return _persistenceBarrier.write(
                _epoch,
                new RecordingPersistenceBarrier.Operation<Boolean>() {
                    @Override
                    public Boolean run() {
                        return _preferences.edit()
                                .putLong(
                                        KEY_LAST_OPERATION_ID,
                                        snapshot.getLastOperationId())
                                .putLong(
                                        KEY_OPERATION_ID,
                                        pending.getOperationId())
                                .putInt(
                                        KEY_ACTIVITY_ID,
                                        pending.getActivityId())
                                .putLong(
                                        KEY_GENERATION,
                                        pending.getGeneration())
                                .putString(
                                        KEY_TYPE,
                                        pending.getType().name())
                                .putString(
                                        KEY_RESULT,
                                        pending.getResult().name())
                                .commit();
                    }
                });
    }

    @Override
    public boolean clearPending(long expectedOperationId) {
        return _persistenceBarrier.write(
                _epoch,
                new RecordingPersistenceBarrier.Operation<Boolean>() {
                    @Override
                    public Boolean run() {
                        long storedOperationId =
                                _preferences.getLong(KEY_OPERATION_ID, 0L);
                        if (storedOperationId == 0L) {
                            return true;
                        }
                        if (storedOperationId != expectedOperationId) {
                            return false;
                        }
                        return _preferences.edit()
                                .remove(KEY_OPERATION_ID)
                                .remove(KEY_ACTIVITY_ID)
                                .remove(KEY_GENERATION)
                                .remove(KEY_TYPE)
                                .remove(KEY_RESULT)
                                .commit();
                    }
                });
    }
}
