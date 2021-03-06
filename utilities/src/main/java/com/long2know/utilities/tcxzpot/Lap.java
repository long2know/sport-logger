package com.long2know.utilities.tcxzpot;

import java.util.List;

import static com.long2know.utilities.tcxzpot.util.TCXExtensionSerialization.serializeExtensions;

public class Lap implements TCXSerializable {

    private final TCXDate startTime;
    private final double totalTime;
    private final double distance;
    private final Double maximumSpeed;
    private final int calories;
    private final HeartRate averageHeartRate;
    private final HeartRate maximumHeartRate;
    private final Intensity intensity;
    private final Cadence cadence;
    private final TriggerMethod triggerMethod;
    private final List<Track> tracks;
    private final Notes notes;
    private final TCXExtension[] extensions;

    public Lap(TCXDate startTime, double totalTime, double distance, Double maximumSpeed, int calories, HeartRate averageHeartRate,
               HeartRate maximumHeartRate, Intensity intensity, Cadence cadence, TriggerMethod triggerMethod,
               List<Track> tracks, Notes notes, TCXExtension... extensions) {
        this.startTime = startTime;
        this.totalTime = totalTime;
        this.distance = distance;
        this.maximumSpeed = maximumSpeed;
        this.calories = calories;
        this.averageHeartRate = averageHeartRate;
        this.maximumHeartRate = maximumHeartRate;
        this.intensity = intensity;
        this.cadence = cadence;
        this.triggerMethod = triggerMethod;
        this.tracks = tracks;
        this.notes = notes;
        this.extensions = extensions;
    }

    @Override
    public void serialize(Serializer serializer) {
        serializer.print("<Lap StartTime=\"" + startTime.toString() + "\">");
        serializer.print("<TotalTimeSeconds>" + totalTime + "</TotalTimeSeconds>");
        serializer.print("<DistanceMeters>" + distance + "</DistanceMeters>");
        if(maximumSpeed != null) serializer.print("<MaximumSpeed>" + maximumSpeed + "</MaximumSpeed>");
        serializer.print("<Calories>" + calories + "</Calories>");
        if(averageHeartRate != null) {
            serializer.print("<AverageHeartRateBpm>");
            averageHeartRate.serialize(serializer);
            serializer.print("</AverageHeartRateBpm>");
        }
        if(maximumHeartRate != null) {
            serializer.print("<MaximumHeartRateBpm>");
            maximumHeartRate.serialize(serializer);
            serializer.print("</MaximumHeartRateBpm>");
        }
        intensity.serialize(serializer);
        if(cadence!= null) cadence.serialize(serializer);
        triggerMethod.serialize(serializer);
        if(tracks != null) {
            for(Track track : tracks) {
                track.serialize(serializer);
            }
        }
        if(notes != null) notes.serialize(serializer);
        serializeExtensions(extensions, serializer);
        serializer.print("</Lap>");
    }
}
