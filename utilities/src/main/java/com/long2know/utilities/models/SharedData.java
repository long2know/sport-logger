package com.long2know.utilities.models;

public class SharedData {
    private final LocationData data;
    private String duration = "00:00:00";
    private boolean recording;
    private boolean paused;
    private int activityId;

    private static class Loader {
        static volatile SharedData INSTANCE = new SharedData();
    }

    SharedData() {
        this.data = new LocationData();
    }

    public static SharedData getInstance() {
        return Loader.INSTANCE;
    }

    public synchronized LocationData getData() {
        return new LocationData(data);
    }

    public synchronized void setLocation(LocationData location) {
        this.data.Latitude = location.Latitude;
        this.data.Longitude = location.Longitude;
        this.data.Accuracy = location.Accuracy;
        this.data.Altitude = location.Altitude;
        this.data.Speed = location.Speed;
        this.data.Bearing = location.Bearing;
        this.data.HasAccuracy = location.HasAccuracy;
        this.data.HasAltitude = location.HasAltitude;
        this.data.HasBearing = location.HasBearing;
        this.data.HasSpeed = location.HasSpeed;
        this.data.Distance = location.Distance;
        this.data.TotalDistance = location.TotalDistance;
    }

    public synchronized void setHeartRate(float heartRate) {
        this.data.HeartRate = heartRate;
    }

    public synchronized void setSteps(int steps) {
        this.data.Steps = steps;
    }

    public synchronized void resetMeasurements() {
        this.data.Latitude = 0D;
        this.data.Longitude = 0D;
        this.data.Accuracy = 0D;
        this.data.Altitude = 0D;
        this.data.Speed = 0D;
        this.data.Bearing = 0D;
        this.data.Distance = 0D;
        this.data.TotalDistance = 0D;
        this.data.HeartRate = 0F;
        this.data.Steps = 0;
        this.data.HasAccuracy = false;
        this.data.HasAltitude = false;
        this.data.HasBearing = false;
        this.data.HasSpeed = false;
    }

    public synchronized String getDuration() {
        return duration;
    }

    public synchronized void setDuration(String duration) {
        this.duration = duration;
    }

    public synchronized boolean isRecording() {
        return recording;
    }

    public synchronized boolean isPaused() {
        return paused;
    }

    public synchronized int getActivityId() {
        return activityId;
    }

    public synchronized void setRecordingState(
            boolean recording,
            boolean paused,
            int activityId,
            String duration) {
        this.recording = recording;
        this.paused = paused;
        this.activityId = activityId;
        this.duration = duration;
    }
}
