package com.long2know.utilities.data_access;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.text.ParseException;
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


public class SqlLogger implements Runnable {

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

    public SqlLogger() {
        _db = Config.context.openOrCreateDatabase(DATABASE_NAME,
                SQLiteDatabase.OPEN_READWRITE, null);
    }

    @Override
    public void run() {
        // Moves the current Thread into the background
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        writeData();
    }

    private void writeData() {
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

        queryBuf.append("INSERT INTO "
                + GPS_TABLE_NAME
                + " (GMTTIMESTAMP, ACTIVITYID, LATITUDE,LONGITUDE,ALTITUDE,ACCURACY,SPEED,BEARING,HEARTRATE) VALUES ("
                + "'"
                + gmtTime
                + "',"
                + singleton.ActivityId
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
        _db.execSQL(queryBuf.toString());
    }


    public static void initDatabase() {
        SQLiteDatabase db = Config.context.openOrCreateDatabase(DATABASE_NAME,
                SQLiteDatabase.OPEN_READWRITE, null);

        db.execSQL("CREATE TABLE IF NOT EXISTS " + ACTIVITY_TABLE_NAME
                + " (ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, GMTSTART VARCHAR, GMTEND VARCHAR, NAME VARCHAR, DESCRIPTION VARCHAR,"
                + "DISTANCE REAL, TIME REAL, PACE REAL);");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + GPS_TABLE_NAME
                + " (ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, ACTIVITYID INTEGER, GMTTIMESTAMP VARCHAR, LATITUDE REAL, LONGITUDE REAL,"
                + "ALTITUDE REAL, ACCURACY REAL, SPEED REAL, BEARING REAL, HEARTRATE REAL);");
        db.close();
        Log.i(TAG, "Database opened ok");
    }

    public static int createActivity() {
        // Get a timestamp
        GregorianCalendar greg = new GregorianCalendar();
        TimeZone tz = greg.getTimeZone();
        int offset = tz.getOffset(System.currentTimeMillis());
        greg.add(Calendar.SECOND, (offset / 1000) * -1);
        Date current = greg.getTime();
        String ts = Config.DotnetTimestampFormat.format(current);
        String gmtTime = Config.TimestampFormat.format(current);
        StringBuffer queryBuf = new StringBuffer();

        queryBuf.append("INSERT INTO "
                + ACTIVITY_TABLE_NAME
                + " (GMTSTART) VALUES ("
                + "'"
                + gmtTime
                + "');");
        Log.i(TAG, queryBuf.toString());

        SQLiteDatabase db = Config.context.openOrCreateDatabase(DATABASE_NAME,
                SQLiteDatabase.OPEN_READWRITE, null);
        db.execSQL(queryBuf.toString());

        String queryLastRowInserted = "select last_insert_rowid()";

        final Cursor cursor = db.rawQuery(queryLastRowInserted, null);
        int idLastInsertedRow = 0;
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    idLastInsertedRow = cursor.getInt(0);
                }
            } finally {
                cursor.close();
            }
        }

        return idLastInsertedRow;
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
        SQLiteDatabase db = Config.context.openOrCreateDatabase(DATABASE_NAME,
                SQLiteDatabase.OPEN_READWRITE, null);

        String[] field = {A_ROWID, A_NAME, A_DESCRIPTION, A_START_TIME_UTC, A_DISTANCE, A_TIME, A_PACE};
        String whereClause = A_ROWID + "=?";
        String[] whereArgs = { Integer.toString(id) };
        Cursor cursor = db.query(ACTIVITY_TABLE_NAME, field, whereClause,whereArgs, null, null, null, null);

        int iname = cursor.getColumnIndex(A_NAME);
        int idescription = cursor.getColumnIndex(A_DESCRIPTION);
        int istarttime = cursor.getColumnIndex(A_START_TIME_UTC);
        int iendtime = cursor.getColumnIndex(A_END_TIME_UTC);
        int idistance = cursor.getColumnIndex(A_DISTANCE);
        int itime = cursor.getColumnIndex(A_TIME);
        int ipace = cursor.getColumnIndex(A_PACE);

        SportActivity retVal = new SportActivity();

        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    retVal.Name = cursor.getString(iname);
                    retVal.Description = cursor.getString(idescription);

                    try {
                        retVal.StartTimeUTC = Config.TimestampFormat.parse(cursor.getString(istarttime));
                    } catch (ParseException e) {}

                    try {
                        retVal.EndTimeUTC = Config.TimestampFormat.parse(cursor.getString(iendtime));
                    } catch (ParseException e) {}

                    retVal.Distance = cursor.getDouble(idistance);
                    retVal.Time = cursor.getDouble(itime);
                    retVal.Pace = cursor.getDouble(ipace);
                }
            } finally {
                cursor.close();
            }
        }

        return retVal;
    }

    public List<SportActivity> getSportActivities() {
        SQLiteDatabase db = Config.context.openOrCreateDatabase(DATABASE_NAME,
                SQLiteDatabase.OPEN_READWRITE, null);

        String[] field = {A_ROWID, A_NAME, A_DESCRIPTION, A_START_TIME_UTC, A_DISTANCE, A_TIME, A_PACE};
//        String whereClause = A_ROWID + "=?";
//        String[] whereArgs = { Integer.toString(id) };

        Cursor cursor = db.query(ACTIVITY_TABLE_NAME, field, null, null, null, null, null, null);

        int iname = cursor.getColumnIndex(A_NAME);
        int idescription = cursor.getColumnIndex(A_DESCRIPTION);
        int istarttime = cursor.getColumnIndex(A_START_TIME_UTC);
        int iendtime = cursor.getColumnIndex(A_END_TIME_UTC);
        int idistance = cursor.getColumnIndex(A_DISTANCE);
        int itime = cursor.getColumnIndex(A_TIME);
        int ipace = cursor.getColumnIndex(A_PACE);

        List<SportActivity> retVal =  new ArrayList<>();

        if (cursor != null) {
            try {

                for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()){
                    SportActivity sportActivity = new SportActivity();
                    sportActivity.Name = cursor.getString(iname);
                    sportActivity.Description = cursor.getString(idescription);

                    try {
                        sportActivity.StartTimeUTC = Config.TimestampFormat.parse(cursor.getString(istarttime));
                    } catch (ParseException e) {}

                    try {
                        sportActivity.EndTimeUTC = Config.TimestampFormat.parse(cursor.getString(iendtime));
                    } catch (ParseException e) {}

                    sportActivity.Distance = cursor.getDouble(idistance);
                    sportActivity.Time = cursor.getDouble(itime);
                    sportActivity.Pace = cursor.getDouble(ipace);

                    retVal.add(sportActivity);
                }

            } finally {
                cursor.close();
            }
        }

        return retVal;
    }

    public void updateSportActivity(SportActivity sportActivity) {
        SQLiteDatabase db = Config.context.openOrCreateDatabase(DATABASE_NAME,
                SQLiteDatabase.OPEN_READWRITE, null);

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

        int count = db.update(ACTIVITY_TABLE_NAME, values, whereClause, whereArgs);
    }

    public void deleteActivity(int id) {
        SQLiteDatabase db = Config.context.openOrCreateDatabase(DATABASE_NAME,
                SQLiteDatabase.OPEN_READWRITE, null);

        // Delete the track points
        String whereClause = T_ACTIVITY_ID + "=?";
        String[] whereArgs = { Integer.toString(id) };
        db.delete(GPS_TABLE_NAME, whereClause, whereArgs);

        // Delete the activity
        whereClause = A_ROWID + "=?";
        whereArgs = new String[] { Integer.toString(id) };
        db.delete(ACTIVITY_TABLE_NAME, whereClause, whereArgs);
    }

    public List<SportTrackPoint> getTrackPointsByActivity(int activityId) {
        SQLiteDatabase db = Config.context.openOrCreateDatabase(DATABASE_NAME,
                SQLiteDatabase.OPEN_READWRITE, null);

        String[] field = {T_ROWID, T_ACTIVITY_ID, T_TIMESTAMP_UTC, T_LATITUDE, T_LONGITUDE, T_ALTITUDE,
                T_ACCURACY, T_SPEED, T_BEARING, T_HEARTRATE};
        String whereClause = T_ACTIVITY_ID + "=?";
        String[] whereArgs = { Integer.toString(activityId) };
        Cursor cursor = db.query(GPS_TABLE_NAME, field, whereClause,whereArgs, null, null, null, null);

        int irowid = cursor.getColumnIndex(T_ROWID);
        int iactivityid = cursor.getColumnIndex(T_ACTIVITY_ID);
        int itimestamputc = cursor.getColumnIndex(T_TIMESTAMP_UTC);
        int ilatitude = cursor.getColumnIndex(T_LATITUDE);
        int ilongitude = cursor.getColumnIndex(T_LONGITUDE);
        int ialtitude = cursor.getColumnIndex(T_ALTITUDE);
        int iaccuracy = cursor.getColumnIndex(T_ACCURACY);
        int ispeed = cursor.getColumnIndex(T_SPEED);
        int ibearing = cursor.getColumnIndex(T_BEARING);
        int iheartrate = cursor.getColumnIndex(T_HEARTRATE);

        List<SportTrackPoint> retVal =  new ArrayList<>();

        if (cursor != null) {
            try {

                for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()){
                    SportTrackPoint trackPoint = new SportTrackPoint();

                    trackPoint.Id = cursor.getInt(irowid);
                    trackPoint.SportActivityId = cursor.getInt(iactivityid);

                    try {
                        trackPoint.TimeStampUTC = Config.TimestampFormat.parse(cursor.getString(itimestamputc));
                    } catch (ParseException e) {}

                    trackPoint.Latitude = cursor.getDouble(ilatitude);
                    trackPoint.Longitude = cursor.getInt(ilongitude);
                    trackPoint.Altitude = cursor.getInt(ialtitude);
                    trackPoint.Accuracy = cursor.getInt(iaccuracy);
                    trackPoint.Speed = cursor.getInt(ispeed);
                    trackPoint.Bearing = cursor.getInt(ibearing);
                    trackPoint.HeartRate = cursor.getInt(iheartrate);

                    retVal.add(trackPoint);
                }

            } finally {
                cursor.close();
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
}
