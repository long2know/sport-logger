package com.long2know.utilities.models;

import android.location.Location;

public class LocationData {
    public double Latitude;
    public double Longitude;
    public double Accuracy;
    public double Altitude;
    public double Speed;
    public double Bearing;

    public double Distance;
    public double TotalDistance;

    public float HeartRate;
    public int Steps;

    public boolean HasAccuracy = false;
    public boolean HasAltitude = false;
    public boolean HasBearing = false;
    public boolean HasSpeed = false;

    public LocationData() {}

    public LocationData(Location location, double distance, double totalDistance) {
        this.Latitude = location.getLatitude();
        this.Longitude = location.getLongitude();
        this.Accuracy = location.hasAccuracy() ? location.getAccuracy() : -1;
        this.Altitude = location.hasAltitude() ? location.getAltitude() : -1;
        this.Speed = location.hasSpeed() ? location.getSpeed() : -1;
        this.Bearing = location.hasBearing() ? location.getBearing() : -1;

        this.HasAccuracy = location.hasAccuracy();
        this.HasAltitude = location.hasAltitude();
        this.HasBearing = location.hasBearing();
        this.HasSpeed = location.hasSpeed();

        this.Distance = distance;
        this.TotalDistance = totalDistance;
    }

    public LocationData(LocationData source) {
        this.Latitude = source.Latitude;
        this.Longitude = source.Longitude;
        this.Accuracy = source.Accuracy;
        this.Altitude = source.Altitude;
        this.Speed = source.Speed;
        this.Bearing = source.Bearing;
        this.Distance = source.Distance;
        this.TotalDistance = source.TotalDistance;
        this.HeartRate = source.HeartRate;
        this.Steps = source.Steps;
        this.HasAccuracy = source.HasAccuracy;
        this.HasAltitude = source.HasAltitude;
        this.HasBearing = source.HasBearing;
        this.HasSpeed = source.HasSpeed;
    }
}
