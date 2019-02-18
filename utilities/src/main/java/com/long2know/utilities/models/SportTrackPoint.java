package com.long2know.utilities.models;

import java.io.Serializable;
import java.util.Date;

public class SportTrackPoint implements Serializable {
    public int Id;
    public int SportActivityId;
    public Date TimeStampUTC;
    public double Latitude;
    public double Longitude;
    public double Altitude;
    public double Accuracy;
    public double Speed;
    public double Bearing;
    public double HeartRate;

    public SportTrackPoint() {

    }
}
