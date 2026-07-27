package com.long2know.utilities.data_access;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import com.long2know.utilities.models.SportActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SqlLoggerStoppedActivityTest {
    private Context _context;
    private Context _previousContext;

    @Before
    public void setUp() {
        _context = RuntimeEnvironment.getApplication();
        _previousContext = com.long2know.utilities.models.Config.context;
        _context.deleteDatabase(SqlLogger.DATABASE_NAME);
        com.long2know.utilities.models.Config.context = _context;
        SqlLogger.initDatabase();
    }

    @After
    public void tearDown() {
        _context.deleteDatabase(SqlLogger.DATABASE_NAME);
        com.long2know.utilities.models.Config.context = _previousContext;
    }

    @Test
    public void productionSchemaAndQueryLoadACompletedStoppedActivity() {
        int activityId = SqlLogger.createActivity();
        assertTrue(activityId > 0);
        assertTrue(SqlLogger.completeActivity(activityId));

        SportActivity activity;
        try (SqlLogger logger = new SqlLogger()) {
            activity = logger.getStoppedActivityForExport(activityId);
        }

        assertEquals(activityId, activity.Id);
        assertNotNull(activity.StartTimeUTC);
        assertNotNull(activity.EndTimeUTC);
        assertNotNull(activity.SportTrackPoints);
        assertTrue(activity.SportTrackPoints.isEmpty());
        assertTrue(_context.deleteDatabase(SqlLogger.DATABASE_NAME));
    }

    @Test
    public void nullEndTimeFailsExplicitlyAndClosesQueryResources() {
        int activityId = SqlLogger.createActivity();

        IllegalStateException failure;
        try (SqlLogger logger = new SqlLogger()) {
            failure = assertThrows(
                    IllegalStateException.class,
                    () -> logger.getStoppedActivityForExport(activityId));
        }

        assertTrue(failure.getMessage().contains(SqlLogger.A_END_TIME_UTC));
        assertTrue(_context.deleteDatabase(SqlLogger.DATABASE_NAME));
    }

    @Test
    public void malformedEndTimeIsNotTreatedAsACompletedStop() {
        int activityId = SqlLogger.createActivity();
        try (SQLiteDatabase database = _context.openOrCreateDatabase(
                SqlLogger.DATABASE_NAME, Context.MODE_PRIVATE, null)) {
            ContentValues values = new ContentValues();
            values.put(SqlLogger.A_END_TIME_UTC, "20260230010101");
            assertEquals(
                    1,
                    database.update(
                            SqlLogger.ACTIVITY_TABLE_NAME,
                            values,
                            SqlLogger.A_ROWID + "=?",
                            new String[]{Integer.toString(activityId)}));
        }

        assertFalse(SqlLogger.completeActivity(activityId));
        try (SqlLogger logger = new SqlLogger()) {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> logger.getStoppedActivityForExport(activityId));
            assertTrue(failure.getMessage().contains(
                    SqlLogger.A_END_TIME_UTC));
        }
        assertTrue(_context.deleteDatabase(SqlLogger.DATABASE_NAME));
    }
}
