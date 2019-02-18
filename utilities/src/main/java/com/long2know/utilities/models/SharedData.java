package com.long2know.utilities.models;

public class SharedData {
    private LocationData data;
    public String Duration;
    public boolean IsRecording;
    public boolean IsPaused;

    public int ActivityId;

    private static class Loader {
        static volatile SharedData INSTANCE = new SharedData();
    }

    SharedData() {
        this.data = new LocationData();
    }

    public static SharedData getInstance() {
        return Loader.INSTANCE;
    }

    public LocationData getData() {
        return data;
    }

    public void setLocation(LocationData data) {
        this.data.Latitude = data.Latitude;
        this.data.Longitude = data.Longitude;
        this.data.Accuracy = data.Accuracy;
        this.data.Altitude = data.Altitude;
        this.data.Speed = data.Speed;
        this.data.Bearing = data.Bearing;

        this.data.HasAccuracy = data.HasAccuracy;
        this.data.HasAltitude = data.HasAltitude;
        this.data.HasBearing = data.HasBearing;
        this.data.HasSpeed = data.HasSpeed;

        this.data.Distance = data.Distance;
        this.data.TotalDistance = data.TotalDistance;
    }

    public void setHeartRate(float heartRate) {
        this.data.HeartRate = heartRate;
    }
    public void setSteps(int steps) {
        this.data.Steps = steps;
    }
}
