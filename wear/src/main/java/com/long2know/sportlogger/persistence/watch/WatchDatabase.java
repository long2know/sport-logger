package com.long2know.sportlogger.persistence.watch;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import androidx.annotation.NonNull;

@Database(
        entities = {
                WatchSessionEntity.class,
                WatchSampleEntity.class,
                WatchLapEntity.class,
                WatchOutboxEntity.class,
                WatchMigrationReceiptEntity.class,
                WatchMigrationQuarantineEntity.class,
                WatchTombstoneEntity.class,
                WatchTombstoneOutboxEntity.class,
                WatchTombstoneReceiptEntity.class
        },
        version = 2,
        exportSchema = true)
public abstract class WatchDatabase extends RoomDatabase {
    public static final String DATABASE_NAME = "sportlogger_watch_v1.db";
    private static final Object PROCESS_OWNER_LOCK = new Object();
    private static WatchDatabase processDatabase;
    private static int processLeaseCount;

    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `watch_laps` ("
                            + "`session_id` TEXT NOT NULL, "
                            + "`lap_index` INTEGER NOT NULL, "
                            + "`lap_id` TEXT NOT NULL, "
                            + "`state` TEXT NOT NULL, "
                            + "`start_time_utc_ms` INTEGER NOT NULL, "
                            + "`end_time_utc_ms` INTEGER, "
                            + "`start_elapsed_duration_ms` INTEGER NOT NULL, "
                            + "`end_elapsed_duration_ms` INTEGER, "
                            + "`start_active_duration_ms` INTEGER NOT NULL, "
                            + "`end_active_duration_ms` INTEGER, "
                            + "`created_at_utc_ms` INTEGER NOT NULL, "
                            + "`updated_at_utc_ms` INTEGER NOT NULL, "
                            + "PRIMARY KEY(`session_id`, `lap_index`), "
                            + "FOREIGN KEY(`session_id`) REFERENCES `watch_sessions`(`session_id`) "
                            + "ON UPDATE NO ACTION ON DELETE CASCADE)");
            database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_watch_laps_lap_id` "
                            + "ON `watch_laps` (`lap_id`)");
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_watch_laps_session_id_state` "
                            + "ON `watch_laps` (`session_id`, `state`)");
        }
    };

    abstract WatchDao watchDao();

    static ProcessLease acquireProcessLease(Context context) {
        synchronized (PROCESS_OWNER_LOCK) {
            if (processDatabase == null) {
                processDatabase = openIndependent(context);
            }
            processLeaseCount++;
            return new ProcessLease(processDatabase);
        }
    }

    static WatchDatabase openIndependent(Context context) {
        return Room.databaseBuilder(
                        context.getApplicationContext(),
                        WatchDatabase.class,
                        DATABASE_NAME)
                        .addMigrations(MIGRATION_1_2)
                        .build();
    }

    private static void release(ProcessLease lease) {
        synchronized (PROCESS_OWNER_LOCK) {
            if (lease.closed) {
                return;
            }
            lease.closed = true;
            if (lease.database != processDatabase || processLeaseCount < 1) {
                throw new IllegalStateException(
                        "Watch database process lease is not owned");
            }
            processLeaseCount--;
            if (processLeaseCount == 0) {
                processDatabase.close();
                processDatabase = null;
            }
        }
    }

    static final class ProcessLease implements AutoCloseable {
        private final WatchDatabase database;
        private boolean closed;

        private ProcessLease(WatchDatabase database) {
            this.database = database;
        }

        WatchDatabase database() {
            synchronized (PROCESS_OWNER_LOCK) {
                if (closed) {
                    throw new IllegalStateException(
                            "Watch database process lease is closed");
                }
                return database;
            }
        }

        @Override
        public void close() {
            release(this);
        }
    }
}
