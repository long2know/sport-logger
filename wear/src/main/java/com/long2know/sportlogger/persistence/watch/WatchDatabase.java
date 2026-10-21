package com.long2know.sportlogger.persistence.watch;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(
        entities = {
                WatchSessionEntity.class,
                WatchSampleEntity.class,
                WatchOutboxEntity.class,
                WatchMigrationReceiptEntity.class,
                WatchMigrationQuarantineEntity.class,
                WatchTombstoneEntity.class,
                WatchTombstoneOutboxEntity.class,
                WatchTombstoneReceiptEntity.class
        },
        version = 1,
        exportSchema = true)
public abstract class WatchDatabase extends RoomDatabase {
    public static final String DATABASE_NAME = "sportlogger_watch_v1.db";

    abstract WatchDao watchDao();

    public static WatchDatabase open(Context context) {
        return Room.databaseBuilder(
                        context.getApplicationContext(),
                        WatchDatabase.class,
                        DATABASE_NAME)
                .build();
    }
}
