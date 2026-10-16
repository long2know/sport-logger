package com.long2know.utilities.data_access;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.long2know.utilities.models.Config;
import com.long2know.utilities.models.LocationData;
import com.long2know.utilities.models.SharedData;
import com.long2know.utilities.models.SportActivity;
import com.long2know.utilities.models.SportTrackPoint;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.TimeZone;

public final class SqlLogger implements Runnable, AutoCloseable {
    public static final String DATABASE_NAME = "GPSLOGGERDB_LONG2KNOW";
    public static final String ACTIVITY_TABLE_NAME = "ACTIVITY";

    public static final String A_ROWID = "ID";
    public static final String A_NAME = "NAME";
    public static final String A_DESCRIPTION = "DESCRIPTION";
    public static final String A_START_TIME_UTC = "GMTSTART";
    public static final String A_END_TIME_UTC = "GMTEND";
    public static final String A_DISTANCE = "DISTANCE";
    public static final String A_TIME = "TIME";
    public static final String A_PACE = "PACE";

    public static final String GPS_TABLE_NAME = "GPS_POINTS";
    public static final String T_ROWID = "ID";
    public static final String T_ACTIVITY_ID = "ACTIVITYID";
    public static final String T_TIMESTAMP_UTC = "GMTTIMESTAMP";
    public static final String T_LATITUDE = "LATITUDE";
    public static final String T_LONGITUDE = "LONGITUDE";
    public static final String T_ALTITUDE = "ALTITUDE";
    public static final String T_ACCURACY = "ACCURACY";
    public static final String T_SPEED = "SPEED";
    public static final String T_BEARING = "BEARING";
    public static final String T_HEARTRATE = "HEARTRATE";

    private final SQLiteDatabase database;

    public SqlLogger(Context context) {
        Context applicationContext = context.getApplicationContext();
        database = applicationContext.openOrCreateDatabase(
                DATABASE_NAME,
                Context.MODE_PRIVATE,
                null);
    }

    @Override
    public void run() {
        writeData(SharedData.getInstance().getActivityId());
    }

    public void writeData(int activityId) {
        if (activityId <= 0) {
            throw new IllegalArgumentException("activityId must be positive");
        }

        LocationData location = SharedData.getInstance().getData();
        ContentValues values = new ContentValues();
        values.put(T_TIMESTAMP_UTC, Config.formatTimestamp(legacyUtcNow()));
        values.put(T_ACTIVITY_ID, activityId);
        values.put(T_LATITUDE, location.Latitude);
        values.put(T_LONGITUDE, location.Longitude);
        putNullable(values, T_ALTITUDE, location.HasAltitude, location.Altitude);
        putNullable(values, T_ACCURACY, location.HasAccuracy, location.Accuracy);
        putNullable(values, T_SPEED, location.HasSpeed, location.Speed);
        putNullable(values, T_BEARING, location.HasBearing, location.Bearing);
        values.put(T_HEARTRATE, location.HeartRate);
        database.insertOrThrow(GPS_TABLE_NAME, null, values);
    }

    public static void initDatabase(Context context) {
        Context applicationContext = context.getApplicationContext();
        try (SQLiteDatabase database = applicationContext.openOrCreateDatabase(
                DATABASE_NAME,
                Context.MODE_PRIVATE,
                null)) {
            database.execSQL("CREATE TABLE IF NOT EXISTS " + ACTIVITY_TABLE_NAME
                    + " (ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, GMTSTART VARCHAR, "
                    + "GMTEND VARCHAR, NAME VARCHAR, DESCRIPTION VARCHAR, DISTANCE REAL, "
                    + "TIME REAL, PACE REAL);");
            database.execSQL("CREATE TABLE IF NOT EXISTS " + GPS_TABLE_NAME
                    + " (ID INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, ACTIVITYID INTEGER, "
                    + "GMTTIMESTAMP VARCHAR, LATITUDE REAL, LONGITUDE REAL, ALTITUDE REAL, "
                    + "ACCURACY REAL, SPEED REAL, BEARING REAL, HEARTRATE REAL);");
        }
    }

    public static int createActivity(Context context, Date startedAt) {
        Context applicationContext = context.getApplicationContext();
        try (SQLiteDatabase database = applicationContext.openOrCreateDatabase(
                DATABASE_NAME,
                Context.MODE_PRIVATE,
                null)) {
            ContentValues values = new ContentValues();
            values.put(A_START_TIME_UTC, Config.formatTimestamp(toLegacyUtc(startedAt)));
            long rowId = database.insertOrThrow(ACTIVITY_TABLE_NAME, null, values);
            if (rowId > Integer.MAX_VALUE) {
                throw new IllegalStateException("Activity identifier exceeds legacy integer range");
            }
            return (int) rowId;
        }
    }

    public int createActivity(SportActivity activity) {
        ContentValues values = activityValues(activity);
        long rowId = database.insertOrThrow(ACTIVITY_TABLE_NAME, null, values);
        if (rowId > Integer.MAX_VALUE) {
            throw new IllegalStateException("Activity identifier exceeds legacy integer range");
        }
        activity.Id = (int) rowId;
        createTrackPoints(activity.SportTrackPoints, activity.Id);
        return activity.Id;
    }

    public SportActivity getSportActivity(int id) {
        String[] fields = {
                A_ROWID,
                A_NAME,
                A_DESCRIPTION,
                A_START_TIME_UTC,
                A_END_TIME_UTC,
                A_DISTANCE,
                A_TIME,
                A_PACE
        };
        try (Cursor cursor = database.query(
                ACTIVITY_TABLE_NAME,
                fields,
                A_ROWID + "=?",
                new String[]{Integer.toString(id)},
                null,
                null,
                null,
                "1")) {
            if (!cursor.moveToFirst()) {
                throw new IllegalStateException("Activity " + id + " does not exist");
            }
            return readActivity(cursor);
        }
    }

    public List<SportActivity> getSportActivities() {
        String[] fields = {
                A_ROWID,
                A_NAME,
                A_DESCRIPTION,
                A_START_TIME_UTC,
                A_END_TIME_UTC,
                A_DISTANCE,
                A_TIME,
                A_PACE
        };
        List<SportActivity> activities = new ArrayList<>();
        try (Cursor cursor = database.query(
                ACTIVITY_TABLE_NAME,
                fields,
                null,
                null,
                null,
                null,
                A_START_TIME_UTC + " DESC")) {
            while (cursor.moveToNext()) {
                activities.add(readActivity(cursor));
            }
        }
        return activities;
    }

    public void updateSportActivity(SportActivity activity) {
        int count = database.update(
                ACTIVITY_TABLE_NAME,
                activityValues(activity),
                A_ROWID + "=?",
                new String[]{Integer.toString(activity.Id)});
        if (count != 1) {
            throw new IllegalStateException("Activity " + activity.Id + " was not updated");
        }
    }

    public void completeActivity(int activityId, Date endedAt) {
        ContentValues values = new ContentValues();
        values.put(A_END_TIME_UTC, Config.formatTimestamp(toLegacyUtc(endedAt)));
        int count = database.update(
                ACTIVITY_TABLE_NAME,
                values,
                A_ROWID + "=?",
                new String[]{Integer.toString(activityId)});
        if (count != 1) {
            throw new IllegalStateException("Activity " + activityId + " was not finalized");
        }
    }

    public void deleteActivity(int id) {
        database.beginTransaction();
        try {
            database.delete(
                    GPS_TABLE_NAME,
                    T_ACTIVITY_ID + "=?",
                    new String[]{Integer.toString(id)});
            database.delete(
                    ACTIVITY_TABLE_NAME,
                    A_ROWID + "=?",
                    new String[]{Integer.toString(id)});
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    public List<SportTrackPoint> getTrackPointsByActivity(int activityId) {
        String[] fields = {
                T_ROWID,
                T_ACTIVITY_ID,
                T_TIMESTAMP_UTC,
                T_LATITUDE,
                T_LONGITUDE,
                T_ALTITUDE,
                T_ACCURACY,
                T_SPEED,
                T_BEARING,
                T_HEARTRATE
        };
        List<SportTrackPoint> trackPoints = new ArrayList<>();
        try (Cursor cursor = database.query(
                GPS_TABLE_NAME,
                fields,
                T_ACTIVITY_ID + "=?",
                new String[]{Integer.toString(activityId)},
                null,
                null,
                T_ROWID + " ASC")) {
            while (cursor.moveToNext()) {
                SportTrackPoint trackPoint = new SportTrackPoint();
                trackPoint.Id = cursor.getInt(cursor.getColumnIndexOrThrow(T_ROWID));
                trackPoint.SportActivityId =
                        cursor.getInt(cursor.getColumnIndexOrThrow(T_ACTIVITY_ID));
                trackPoint.TimeStampUTC = parseTimestamp(
                        cursor.getString(cursor.getColumnIndexOrThrow(T_TIMESTAMP_UTC)));
                trackPoint.Latitude = cursor.getDouble(cursor.getColumnIndexOrThrow(T_LATITUDE));
                trackPoint.Longitude = cursor.getDouble(cursor.getColumnIndexOrThrow(T_LONGITUDE));
                trackPoint.Altitude = cursor.getDouble(cursor.getColumnIndexOrThrow(T_ALTITUDE));
                trackPoint.Accuracy = cursor.getDouble(cursor.getColumnIndexOrThrow(T_ACCURACY));
                trackPoint.Speed = cursor.getDouble(cursor.getColumnIndexOrThrow(T_SPEED));
                trackPoint.Bearing = cursor.getDouble(cursor.getColumnIndexOrThrow(T_BEARING));
                trackPoint.HeartRate = cursor.getDouble(cursor.getColumnIndexOrThrow(T_HEARTRATE));
                trackPoints.add(trackPoint);
            }
        }
        return trackPoints;
    }

    public void createTrackPoints(List<SportTrackPoint> trackPoints, int activityId) {
        database.beginTransaction();
        try {
            for (SportTrackPoint trackPoint : trackPoints) {
                createTrackPoint(trackPoint, activityId);
            }
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    public void createTrackPoint(SportTrackPoint trackPoint, int activityId) {
        long rowId = database.insertOrThrow(
                GPS_TABLE_NAME,
                null,
                trackPointValues(trackPoint, activityId));
        if (rowId > Integer.MAX_VALUE) {
            throw new IllegalStateException("Track point identifier exceeds legacy integer range");
        }
        trackPoint.Id = (int) rowId;
    }

    public void updateTrackPoint(SportTrackPoint trackPoint, int activityId) {
        int count = database.update(
                GPS_TABLE_NAME,
                trackPointValues(trackPoint, activityId),
                T_ROWID + "=?",
                new String[]{Integer.toString(trackPoint.Id)});
        if (count != 1) {
            throw new IllegalStateException("Track point " + trackPoint.Id + " was not updated");
        }
    }

    @Override
    public void close() {
        database.close();
    }

    private static SportActivity readActivity(Cursor cursor) {
        SportActivity activity = new SportActivity();
        activity.Id = cursor.getInt(cursor.getColumnIndexOrThrow(A_ROWID));
        activity.Name = cursor.getString(cursor.getColumnIndexOrThrow(A_NAME));
        activity.Description = cursor.getString(cursor.getColumnIndexOrThrow(A_DESCRIPTION));
        activity.StartTimeUTC = parseNullableTimestamp(
                cursor.getString(cursor.getColumnIndexOrThrow(A_START_TIME_UTC)));
        activity.EndTimeUTC = parseNullableTimestamp(
                cursor.getString(cursor.getColumnIndexOrThrow(A_END_TIME_UTC)));
        activity.Distance = cursor.getDouble(cursor.getColumnIndexOrThrow(A_DISTANCE));
        activity.Time = cursor.getDouble(cursor.getColumnIndexOrThrow(A_TIME));
        activity.Pace = cursor.getDouble(cursor.getColumnIndexOrThrow(A_PACE));
        return activity;
    }

    private static ContentValues activityValues(SportActivity activity) {
        ContentValues values = new ContentValues();
        values.put(A_NAME, activity.Name);
        values.put(A_DESCRIPTION, activity.Description);
        putDate(values, A_START_TIME_UTC, activity.StartTimeUTC);
        putDate(values, A_END_TIME_UTC, activity.EndTimeUTC);
        values.put(A_DISTANCE, activity.Distance);
        values.put(A_TIME, activity.Time);
        values.put(A_PACE, activity.Pace);
        return values;
    }

    private static ContentValues trackPointValues(
            SportTrackPoint trackPoint,
            int activityId) {
        ContentValues values = new ContentValues();
        values.put(
                T_ACTIVITY_ID,
                activityId == 0 ? trackPoint.SportActivityId : activityId);
        putDate(values, T_TIMESTAMP_UTC, trackPoint.TimeStampUTC);
        values.put(T_LATITUDE, trackPoint.Latitude);
        values.put(T_LONGITUDE, trackPoint.Longitude);
        values.put(T_ALTITUDE, trackPoint.Altitude);
        values.put(T_ACCURACY, trackPoint.Accuracy);
        values.put(T_SPEED, trackPoint.Speed);
        values.put(T_BEARING, trackPoint.Bearing);
        values.put(T_HEARTRATE, trackPoint.HeartRate);
        return values;
    }

    private static void putDate(ContentValues values, String key, Date date) {
        if (date == null) {
            values.putNull(key);
        } else {
            values.put(key, Config.formatTimestamp(date));
        }
    }

    private static void putNullable(
            ContentValues values,
            String key,
            boolean present,
            double value) {
        if (present) {
            values.put(key, value);
        } else {
            values.putNull(key);
        }
    }

    private static Date parseNullableTimestamp(String timestamp) {
        return timestamp == null ? null : parseTimestamp(timestamp);
    }

    private static Date parseTimestamp(String timestamp) {
        try {
            return Config.parseTimestamp(timestamp);
        } catch (ParseException error) {
            throw new IllegalStateException("Invalid legacy timestamp: " + timestamp, error);
        }
    }

    private static Date legacyUtcNow() {
        return toLegacyUtc(new Date());
    }

    private static Date toLegacyUtc(Date date) {
        GregorianCalendar calendar = new GregorianCalendar();
        calendar.setTime(date);
        TimeZone timeZone = calendar.getTimeZone();
        int offset = timeZone.getOffset(date.getTime());
        calendar.add(Calendar.SECOND, (offset / 1000) * -1);
        return calendar.getTime();
    }
}
