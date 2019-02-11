package com.long2know.utilities;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class SportActivity {
    public int Id;
    public Date StartTimeUTC;
    public Date EndTimeUTC;
    public String Name;
    public String Description;
    public double Distance;
    public double Time;
    public double Pace;

    public List<SportTrackPoint> SportTrackPoints;

    public SportActivity() {
        SportTrackPoints =  new ArrayList<>();
    }
}
