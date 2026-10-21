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
                PhoneTombstoneReceiptEntity.class,
                ConnectorLedgerEntity.class,
                ConnectorRemoteIdEntity.class,
                PhoneLegacyBackfillReceiptEntity.class,
                HealthConnectRecordEntity.class,
                HealthConnectOwnedRecordIdEntity.class,
                HealthConnectDeletionReceiptEntity.class,
                HealthConnectClientVersionEntity.class,
                HealthConnectOperationReceiptEntity.class
        },
        version = 1,
        exportSchema = true)
public abstract class PhoneDatabase extends RoomDatabase {
    public static final String DATABASE_NAME = "sportlogger_phone_v1.db";
    private static final Object PROCESS_OWNER_LOCK = new Object();
    private static PhoneDatabase processDatabase;
    private static int processLeaseCount;

    abstract PhoneDao phoneDao();

    static ProcessLease acquireProcessLease(Context context) {
        synchronized (PROCESS_OWNER_LOCK) {
            if (processDatabase == null) {
                processDatabase = openIndependent(context);
            }
            processLeaseCount++;
            return new ProcessLease(processDatabase);
        }
    }

    static PhoneDatabase openIndependent(Context context) {
        return Room.databaseBuilder(
                        context.getApplicationContext(),
                        PhoneDatabase.class,
                        DATABASE_NAME)
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
                        "Phone database process lease is not owned");
            }
            processLeaseCount--;
            if (processLeaseCount == 0) {
                processDatabase.close();
                processDatabase = null;
            }
        }
    }

    static final class ProcessLease implements AutoCloseable {
        private final PhoneDatabase database;
        private boolean closed;

        private ProcessLease(PhoneDatabase database) {
            this.database = database;
        }

        PhoneDatabase database() {
            synchronized (PROCESS_OWNER_LOCK) {
                if (closed) {
                    throw new IllegalStateException(
                            "Phone database process lease is closed");
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
