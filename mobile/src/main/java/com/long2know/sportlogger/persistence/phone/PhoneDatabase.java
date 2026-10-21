package com.long2know.sportlogger.persistence.phone;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(
        entities = {
                PhoneSessionHeadEntity.class,
                PhoneActivityRevisionEntity.class,
                PhonePointEntity.class,
                PhoneMetadataEntity.class,
                PhoneInboxReceiptEntity.class,
                PhoneTombstoneEntity.class,
                PhoneTombstoneOutboxEntity.class,
                ConnectorLedgerEntity.class,
                ConnectorRemoteIdEntity.class,
                PhoneLegacyBackfillReceiptEntity.class
        },
        version = 1,
        exportSchema = true)
public abstract class PhoneDatabase extends RoomDatabase {
    public static final String DATABASE_NAME = "sportlogger_phone_v1.db";

    abstract PhoneDao phoneDao();

    public static PhoneDatabase open(Context context) {
        return Room.databaseBuilder(
                        context.getApplicationContext(),
                        PhoneDatabase.class,
                        DATABASE_NAME)
                .build();
    }
}
