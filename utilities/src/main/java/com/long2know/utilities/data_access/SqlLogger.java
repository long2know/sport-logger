package com.long2know.utilities.data_access;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.text.DateFormat;
import java.text.ParsePosition;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.TimeZone;
import java.util.Date;

import android.util.Log;
import com.long2know.utilities.models.Config;
import com.long2know.utilities.models.LocationData;
import com.long2know.utilities.models.SharedData;
import com.long2know.utilities.models.SportActivity;
import com.long2know.utilities.models.SportTrackPoint;
import com.long2know.utilities.tcxzpot.Trackpoint;


public class SqlLogger implements Runnable, AutoCloseable {

    public static final String TAG = "SqlLogger";
    public static final String DATABASE_NAME = "GPSLOGGERDB_LONG2KNOW";
    public static final String ACTIVITY_TABLE_NAME = "ACTIVITY";

    public static final String A_ROWID="ID";
    public static final String A_NAME="NAME";
    public static final String A_DESCRIPTION="DESCRIPTION";
    public static final String A_START_TIME_UTC="GMTSTART";
    public static final String A_END_TIME_UTC="GMTEND";
    public static final String A_DISTANCE="DISTANCE";
    public static final String A_TIME="TIME";
    public static final String A_PACE="PACE";

    public static final String GPS_TABLE_NAME = "GPS_POINTS";
    public static final String T_ROWID="ID";
    public static final String T_ACTIVITY_ID="ACTIVITYID";
    public static final String T_TIMESTAMP_UTC="GMTTIMESTAMP";
    public static final String T_LATITUDE="LATITUDE";
    public static final String T_LONGITUDE="LONGITUDE";
    public static final String T_ALTITUDE="ALTITUDE";
    public static final String T_ACCURACY="ACCURACY";
    public static final String T_SPEED="SPEED";
    public static final String T_BEARING="BEARING";
    public static final String T_HEARTRATE="HEARTRATE";

    private SQLiteDatabase _db;
    private final Integer _activityId;

    public SqlLogger() {
        this(null);
    }

    public SqlLogger(int activityId) {
        this(Integer.valueOf(activityId));
    }

    private SqlLogger(Integer activityId) {
        _activityId = activityId;
        _db = Config.context.openOrCreateDatabase(DATABASE_NAME,
                Context.MODE_PRIVATE, null);
    }

    @Override
    public void run() {
        writeData();
    }

    @Override
    public synchronized void close() {
        if (_db != null) {
            _db.close();
            _db = null;
        }
    }

    private void writeData() {
        if (Thread.currentThread().isInterrupted()) {
            return;
        }
        if (_activityId == null) {
            throw new IllegalStateException(
                    "Scheduled SQL writes require an immutable activity ID.");
        }

        // Get a timestamp
        GregorianCalendar greg = new GregorianCalendar();
        TimeZone tz = greg.getTimeZone();
        int offset = tz.getOffset(System.currentTimeMillis());
        greg.add(Calendar.SECOND, (offset / 1000) * -1);
        Date current = greg.getTime();
        String ts = Config.DotnetTimestampFormat.format(current);
        String gmtTime = Config.TimestampFormat.format(current);

        StringBuffer queryBuf = new StringBuffer();
        SharedData singleton = SharedData.getInstance();
        LocationData locationData = singleton.getData();

        int activityId = _activityId;
        if (Thread.currentThread().isInterrupted()) {
            return;
        }

        queryBuf.append("INSERT INTO "
                + GPS_TABLE_NAME
                + " (GMTTIMESTAMP, ACTIVITYID, LATITUDE,LONGITUDE,ALTITUDE,ACCURACY,SPEED,BEARING,HEARTRATE) VALUES ("
                + "'"
                + gmtTime
                + "',"
                + activityId
                + ","
                + locationData.Latitude
                + ","
                + locationData.Longitude
                + ","
                + (locationData.HasAltitude ? locationData.Altitude : "NULL")
                + ","
                + (locationData.HasAccuracy ? locationData.Accuracy : "NULL")
                + ","
                + (locationData.HasSpeed ? locationData.Speed : "NULL")
                + ","
                + (locationData.HasBearing ? locationData.Bearing : "NULL")
                + ","
                + locationData.HeartRate
                + ");");
        Log.i(TAG, queryBuf.toString());
        if (Thread.currentThread().isInterrupted()) {
            return;
        }
        _db.execSQL(queryBuf.toString());
    }


    public static void initDatabase() {
        try (SQLiteDatabase db = Config.context.openOrCreateDatabase(
                DATABASE_NAME, Context.MODE_PRIVATE, null)) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + ACTIVITY_TABLE_NAME
                    + " (ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, GMTSTART VARCHAR, GMTEND VARCHAR, NAME VARCHAR, DESCRIPTION VARCHAR,"
                    + "DISTANCE REAL, TIME REAL, PACE REAL);");

            db.execSQL("CREATE TABLE IF NOT EXISTS " + GPS_TABLE_NAME
                    + " (ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, ACTIVITYID INTEGER, GMTTIMESTAMP VARCHAR, LATITUDE REAL, LONGITUDE REAL,"
                    + "ALTITUDE REAL, ACCURACY REAL, SPEED REAL, BEARING REAL, HEARTRATE REAL);");
        }
        Log.i(TAG, "Database opened ok");
    }

    public static int createActivity() {
        String gmtTime = currentUtcTimestamp();
        StringBuffer queryBuf = new StringBuffer();

        queryBuf.append("INSERT INTO "
                + ACTIVITY_TABLE_NAME
                + " (GMTSTART) VALUES ("
                + "'"
                + gmtTime
                + "');");
        Log.i(TAG, queryBuf.toString());

        try (SQLiteDatabase db = Config.context.openOrCreateDatabase(
                DATABASE_NAME, Context.MODE_PRIVATE, null)) {
            db.execSQL(queryBuf.toString());
            try (Cursor cursor = db.rawQuery(
                    "select last_insert_rowid()", null)) {
                if (cursor.moveToFirst()) {
                    return cursor.getInt(0);
                }
            }
        }
        return 0;
    }

    public static boolean completeActivity(int activityId) {
        if (activityId <= 0) {
            return false;
        }
        try (SQLiteDatabase db = Config.context.openOrCreateDatabase(
                DATABASE_NAME, Context.MODE_PRIVATE, null)) {
            ContentValues values = new ContentValues();
            values.put(A_END_TIME_UTC, currentUtcTimestamp());
            int updated = db.update(
                    ACTIVITY_TABLE_NAME,
                    values,
                    A_ROWID + "=? AND (" + A_END_TIME_UTC
                            + " IS NULL OR " + A_END_TIME_UTC + "='')",
                    new String[]{Integer.toString(activityId)});
            if (updated == 1) {
                return true;
            }
            try (Cursor cursor = db.query(
                    ACTIVITY_TABLE_NAME,
                    new String[]{A_END_TIME_UTC},
                    A_ROWID + "=?",
                    new String[]{Integer.toString(activityId)},
                    null,
                    null,
                    null,
                    "1")) {
                return cursor.moveToFirst()
                        && parseOptionalTimestamp(cursor, 0) != null;
            }
        }
    }

    public static boolean activityExists(int activityId) {
        if (activityId <= 0) {
            return false;
        }
        try (SQLiteDatabase db = Config.context.openOrCreateDatabase(
                DATABASE_NAME, Context.MODE_PRIVATE, null);
             Cursor cursor = db.query(
                     ACTIVITY_TABLE_NAME,
                     new String[]{A_ROWID},
                     A_ROWID + "=?",
                     new String[]{Integer.toString(activityId)},
                     null,
                     null,
                     null,
                     "1")) {
            return cursor.moveToFirst();
        }
    }

    public int createActivity(SportActivity sportAcitvity) {
        // Create a new map of values, where column names are the keys
        ContentValues values = new ContentValues();
        values.put(A_NAME, sportAcitvity.Name);
        values.put(A_DESCRIPTION, sportAcitvity.Description);
        values.put(A_START_TIME_UTC, Config.TimestampFormat.format(sportAcitvity.StartTimeUTC));
        values.put(A_END_TIME_UTC, Config.TimestampFormat.format(sportAcitvity.EndTimeUTC));
        values.put(A_DISTANCE, sportAcitvity.Distance);
        values.put(A_TIME, sportAcitvity.Time);
        values.put(A_PACE, sportAcitvity.Pace);

        // Insert the new row, returning the primary key value of the new row
        int newRowId = (int) _db.insert(ACTIVITY_TABLE_NAME, null, values);
        sportAcitvity.Id = (int) newRowId;

        createTrackPoints(sportAcitvity.SportTrackPoints, sportAcitvity.Id);
        return newRowId;
    }

    public SportActivity getSportActivity(int id) {
        if (id <= 0) {
            throw new IllegalArgumentException(
                    "A positive activity ID is required for export.");
        }
        String[] field = {
                A_ROWID,
                A_NAME,
                A_DESCRIPTION,
                A_START_TIME_UTC,
                A_END_TIME_UTC,
                A_DISTANCE,
                A_TIME,
                A_PACE
        };
        String whereClause = A_ROWID + "=?";
        String[] whereArgs = { Integer.toString(id) };
        try (Cursor cursor = database().query(
                ACTIVITY_TABLE_NAME,
                field,
                whereClause,
                whereArgs,
                null,
                null,
                null,
                "1")) {
            if (!cursor.moveToFirst()) {
                throw new IllegalStateException(
                        "Activity " + id + " does not exist.");
            }
            SportActivity activity = new SportActivity();
            activity.Id = cursor.getInt(
                    cursor.getColumnIndexOrThrow(A_ROWID));
            activity.Name = cursor.getString(
                    cursor.getColumnIndexOrThrow(A_NAME));
            activity.Description = cursor.getString(
                    cursor.getColumnIndexOrThrow(A_DESCRIPTION));
            activity.StartTimeUTC = parseRequiredTimestamp(
                    cursor,
                    cursor.getColumnIndexOrThrow(A_START_TIME_UTC),
                    A_START_TIME_UTC,
                    id);
            activity.EndTimeUTC = parseRequiredTimestamp(
                    cursor,
                    cursor.getColumnIndexOrThrow(A_END_TIME_UTC),
                    A_END_TIME_UTC,
                    id);
            activity.Distance = cursor.getDouble(
                    cursor.getColumnIndexOrThrow(A_DISTANCE));
            activity.Time = cursor.getDouble(
                    cursor.getColumnIndexOrThrow(A_TIME));
            activity.Pace = cursor.getDouble(
                    cursor.getColumnIndexOrThrow(A_PACE));
            return activity;
        }
    }

    public SportActivity getStoppedActivityForExport(int id) {
        SportActivity activity = getSportActivity(id);
        activity.SportTrackPoints = getTrackPointsByActivity(id);
        return activity;
    }

    public List<SportActivity> getSportActivities() {
        String[] field = {
                A_ROWID,
                A_NAME,
                A_DESCRIPTION,
                A_START_TIME_UTC,
                A_END_TIME_UTC,
                A_DISTANCE,
                A_TIME,
                A_PACE
        };
//        String whereClause = A_ROWID + "=?";
//        String[] whereArgs = { Integer.toString(id) };

        List<SportActivity> retVal =  new ArrayList<>();
        try (Cursor cursor = database().query(
                ACTIVITY_TABLE_NAME,
                field,
                null,
                null,
                null,
                null,
                null,
                null)) {
            int irowid = cursor.getColumnIndexOrThrow(A_ROWID);
            int iname = cursor.getColumnIndexOrThrow(A_NAME);
            int idescription = cursor.getColumnIndexOrThrow(A_DESCRIPTION);
            int istarttime = cursor.getColumnIndexOrThrow(A_START_TIME_UTC);
            int iendtime = cursor.getColumnIndexOrThrow(A_END_TIME_UTC);
            int idistance = cursor.getColumnIndexOrThrow(A_DISTANCE);
            int itime = cursor.getColumnIndexOrThrow(A_TIME);
            int ipace = cursor.getColumnIndexOrThrow(A_PACE);

            for (cursor.moveToFirst();
                 !cursor.isAfterLast();
                 cursor.moveToNext()) {
                SportActivity sportActivity = new SportActivity();
                sportActivity.Id = cursor.getInt(irowid);
                sportActivity.Name = cursor.getString(iname);
                sportActivity.Description = cursor.getString(idescription);
                sportActivity.StartTimeUTC =
                        parseOptionalTimestamp(cursor, istarttime);
                sportActivity.EndTimeUTC =
                        parseOptionalTimestamp(cursor, iendtime);
                sportActivity.Distance = cursor.getDouble(idistance);
                sportActivity.Time = cursor.getDouble(itime);
                sportActivity.Pace = cursor.getDouble(ipace);
                retVal.add(sportActivity);
            }
        }

        return retVal;
    }

    public void updateSportActivity(SportActivity sportActivity) {
        // Create a new map of values, where column names are the keys
        ContentValues values = new ContentValues();
        values.put(A_NAME, sportActivity.Name);
        values.put(A_DESCRIPTION, sportActivity.Description);
        values.put(A_START_TIME_UTC, Config.TimestampFormat.format(sportActivity.StartTimeUTC));
        values.put(A_END_TIME_UTC, Config.TimestampFormat.format(sportActivity.EndTimeUTC));
        values.put(A_DISTANCE, sportActivity.Distance);
        values.put(A_TIME, sportActivity.Time);
        values.put(A_PACE, sportActivity.Pace);

        // Which row to update, based on the title
        String whereClause = A_ROWID + "=?";
        String[] whereArgs = { Integer.toString(sportActivity.Id) };

        database().update(ACTIVITY_TABLE_NAME, values, whereClause, whereArgs);
    }

    public void deleteActivity(int id) {
        // Delete the track points
        String whereClause = T_ACTIVITY_ID + "=?";
        String[] whereArgs = { Integer.toString(id) };
        database().delete(GPS_TABLE_NAME, whereClause, whereArgs);

        // Delete the activity
        whereClause = A_ROWID + "=?";
        whereArgs = new String[] { Integer.toString(id) };
        database().delete(ACTIVITY_TABLE_NAME, whereClause, whereArgs);
    }

    public List<SportTrackPoint> getTrackPointsByActivity(int activityId) {
        String[] field = {T_ROWID, T_ACTIVITY_ID, T_TIMESTAMP_UTC, T_LATITUDE, T_LONGITUDE, T_ALTITUDE,
                T_ACCURACY, T_SPEED, T_BEARING, T_HEARTRATE};
        String whereClause = T_ACTIVITY_ID + "=?";
        String[] whereArgs = { Integer.toString(activityId) };
        List<SportTrackPoint> retVal =  new ArrayList<>();
        try (Cursor cursor = database().query(
                GPS_TABLE_NAME,
                field,
                whereClause,
                whereArgs,
                null,
                null,
                null,
                null)) {
            int irowid = cursor.getColumnIndexOrThrow(T_ROWID);
            int iactivityid = cursor.getColumnIndexOrThrow(T_ACTIVITY_ID);
            int itimestamputc =
                    cursor.getColumnIndexOrThrow(T_TIMESTAMP_UTC);
            int ilatitude = cursor.getColumnIndexOrThrow(T_LATITUDE);
            int ilongitude = cursor.getColumnIndexOrThrow(T_LONGITUDE);
            int ialtitude = cursor.getColumnIndexOrThrow(T_ALTITUDE);
            int iaccuracy = cursor.getColumnIndexOrThrow(T_ACCURACY);
            int ispeed = cursor.getColumnIndexOrThrow(T_SPEED);
            int ibearing = cursor.getColumnIndexOrThrow(T_BEARING);
            int iheartrate = cursor.getColumnIndexOrThrow(T_HEARTRATE);

            for (cursor.moveToFirst();
                 !cursor.isAfterLast();
                 cursor.moveToNext()) {
                SportTrackPoint trackPoint = new SportTrackPoint();
                trackPoint.Id = cursor.getInt(irowid);
                trackPoint.SportActivityId = cursor.getInt(iactivityid);
                trackPoint.TimeStampUTC =
                        parseOptionalTimestamp(cursor, itimestamputc);
                trackPoint.Latitude = cursor.getDouble(ilatitude);
                trackPoint.Longitude = cursor.getDouble(ilongitude);
                trackPoint.Altitude = cursor.getDouble(ialtitude);
                trackPoint.Accuracy = cursor.getDouble(iaccuracy);
                trackPoint.Speed = cursor.getDouble(ispeed);
                trackPoint.Bearing = cursor.getDouble(ibearing);
                trackPoint.HeartRate = cursor.getDouble(iheartrate);
                retVal.add(trackPoint);
            }
        }

        return retVal;
    }

    public void createTrackPoints(List<SportTrackPoint> trackPoints, int activityId) {
        for (SportTrackPoint trackPoint : trackPoints) {
            createTrackPoint(trackPoint, activityId);
        }
    }

    public void createTrackPoint(SportTrackPoint trackPoint, int activityId) {
        ContentValues values = getrackPointContentValues(trackPoint, activityId);
        // Insert the new row, returning the primary key value of the new row
        int newRowId = (int) _db.insert(GPS_TABLE_NAME, null, values);
        trackPoint.Id = newRowId;
    }

    public void updateTrackPoint(SportTrackPoint trackPoint, int activityId) {
        // Create a new map of values, where column names are the keys
        ContentValues values = getrackPointContentValues(trackPoint, activityId);
        // Which row to update, based on the title
        String whereClause = T_ROWID + "=?";
        String[] whereArgs = {Integer.toString(trackPoint.Id)};

        int count = _db.update(GPS_TABLE_NAME, values, whereClause, whereArgs);
    }

    private ContentValues getrackPointContentValues(SportTrackPoint trackPoint, int activityId) {
        // Create a new map of values, where column names are the keys
        ContentValues values = new ContentValues();
        values.put(T_ACTIVITY_ID, activityId == 0 ? trackPoint.SportActivityId : activityId);
        values.put(T_TIMESTAMP_UTC, Config.TimestampFormat.format(trackPoint.TimeStampUTC));
        values.put(T_LATITUDE, trackPoint.Latitude);
        values.put(T_LONGITUDE, trackPoint.Longitude);
        values.put(T_ALTITUDE, trackPoint.Altitude);
        values.put(T_ACCURACY, trackPoint.Accuracy);
        values.put(T_SPEED, trackPoint.Speed);
        values.put(T_BEARING, trackPoint.Bearing);
        values.put(T_HEARTRATE, trackPoint.HeartRate);

        return values;
    }

    private synchronized SQLiteDatabase database() {
        if (_db == null || !_db.isOpen()) {
            throw new IllegalStateException("SqlLogger is closed.");
        }
        return _db;
    }

    private static String currentUtcTimestamp() {
        GregorianCalendar greg = new GregorianCalendar();
        TimeZone tz = greg.getTimeZone();
        int offset = tz.getOffset(System.currentTimeMillis());
        greg.add(Calendar.SECOND, (offset / 1000) * -1);
        synchronized (Config.TimestampFormat) {
            return Config.TimestampFormat.format(greg.getTime());
        }
    }

    private static Date parseRequiredTimestamp(
            Cursor cursor, int columnIndex, String columnName, int activityId) {
        Date timestamp = parseOptionalTimestamp(cursor, columnIndex);
        if (timestamp == null) {
            throw new IllegalStateException(
                    "Activity " + activityId + " has no valid "
                            + columnName + " value.");
        }
        return timestamp;
    }

    private static Date parseOptionalTimestamp(
            Cursor cursor, int columnIndex) {
        if (cursor.isNull(columnIndex)) {
            return null;
        }
        String value = cursor.getString(columnIndex);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        DateFormat timestampFormat;
        synchronized (Config.TimestampFormat) {
            timestampFormat = (DateFormat) Config.TimestampFormat.clone();
        }
        timestampFormat.setLenient(false);
        ParsePosition position = new ParsePosition(0);
        Date timestamp = timestampFormat.parse(value, position);
        return timestamp != null && position.getIndex() == value.length()
                ? timestamp
                : null;
    }
}
