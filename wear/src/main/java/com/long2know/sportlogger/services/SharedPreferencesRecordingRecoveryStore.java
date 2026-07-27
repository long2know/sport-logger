package com.long2know.sportlogger.services;

import android.content.Context;
import android.content.SharedPreferences;

final class SharedPreferencesRecordingRecoveryStore
        implements RecordingRecoveryState.Store {
    private static final String PREFERENCES_NAME =
            "sport_logger_recording_recovery";
    private static final String KEY_PHASE = "phase";
    private static final String KEY_ACTIVITY_ID = "activity_id";
    private static final String KEY_GENERATION = "generation";

    private final SharedPreferences _preferences;
    private final RecordingPersistenceBarrier _persistenceBarrier;
    private final RecordingPersistenceBarrier.Epoch _epoch;

    SharedPreferencesRecordingRecoveryStore(
            Context context,
            RecordingPersistenceBarrier persistenceBarrier,
            RecordingPersistenceBarrier.Epoch epoch) {
        _preferences = context.getApplicationContext().getSharedPreferences(
                PREFERENCES_NAME, Context.MODE_PRIVATE);
        _persistenceBarrier = persistenceBarrier;
        _epoch = epoch;
    }

    @Override
    public RecordingRecoveryState.Snapshot load() {
        return _persistenceBarrier.read(
                _epoch,
                RecordingRecoveryState.Snapshot.idle(),
                new RecordingPersistenceBarrier.Operation<
                        RecordingRecoveryState.Snapshot>() {
                    @Override
                    public RecordingRecoveryState.Snapshot run() {
                        String phaseName =
                                _preferences.getString(KEY_PHASE, null);
                        if (phaseName == null) {
                            return RecordingRecoveryState.Snapshot.idle();
                        }

                        RecordingRecoveryState.Phase phase;
                        try {
                            phase = RecordingRecoveryState.Phase.valueOf(
                                    phaseName);
                        } catch (IllegalArgumentException exception) {
                            return RecordingRecoveryState.Snapshot.idle();
                        }
                        return RecordingRecoveryState.Snapshot.owned(
                                phase,
                                _preferences.getInt(KEY_ACTIVITY_ID, 0),
                                _preferences.getLong(KEY_GENERATION, 0L));
                    }
                });
    }

    @Override
    public boolean save(RecordingRecoveryState.Snapshot snapshot) {
        return _persistenceBarrier.write(
                _epoch,
                new RecordingPersistenceBarrier.Operation<Boolean>() {
                    @Override
                    public Boolean run() {
                        return _preferences.edit()
                                .putString(
                                        KEY_PHASE,
                                        snapshot.getPhase().name())
                                .putInt(
                                        KEY_ACTIVITY_ID,
                                        snapshot.getActivityId())
                                .putLong(
                                        KEY_GENERATION,
                                        snapshot.getGeneration())
                                .commit();
                    }
                });
    }

    @Override
    public boolean clear() {
        return _persistenceBarrier.write(
                _epoch,
                new RecordingPersistenceBarrier.Operation<Boolean>() {
                    @Override
                    public Boolean run() {
                        return _preferences.edit()
                                .remove(KEY_PHASE)
                                .remove(KEY_ACTIVITY_ID)
                                .remove(KEY_GENERATION)
                                .commit();
                    }
                });
    }
}
