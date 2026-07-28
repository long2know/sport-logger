package com.long2know.utilities.data_access;

import android.database.sqlite.SQLiteDatabase;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SqlLoggerOpenModeTest {
    @Test
    public void contextModePreservesLegacyReadWriteValue() {
        assertEquals(SQLiteDatabase.OPEN_READWRITE, SqlLogger.DATABASE_OPEN_MODE);
    }
}
