package com.long2know.utilities.tcxzpot.builders;

import com.long2know.utilities.tcxzpot.Cadence;
import com.long2know.utilities.tcxzpot.HeartRate;
import com.long2know.utilities.tcxzpot.Position;
import com.long2know.utilities.tcxzpot.SensorState;
import com.long2know.utilities.tcxzpot.TCXDate;
import com.long2know.utilities.tcxzpot.TCXExtension;
import com.long2know.utilities.tcxzpot.Trackpoint;

public class TrackpointBuilder {

    private TCXExtension[] extensions;

    public static TrackpointBuilder aTrackpoint() {
        return new TrackpointBuilder();
    }

    private TrackpointBuilder() {}

    private TCXDate time;
    private Position position;
    private Double altitude;
    private Double distance;
    private HeartRate heartRate;
    private Cadence cadence;
    private SensorState sensorState;

    public TrackpointBuilder onTime(TCXDate time) {
        this.time = time;
        return this;
    }

    public TrackpointBuilder withPosition(Position position) {
        this.position = position;
        return this;
    }

    public TrackpointBuilder withAltitude(double altitude) {
        this.altitude = altitude;
        return this;
    }

    public TrackpointBuilder withDistance(double distance) {
        this.distance = distance;
        return this;
    }

    public TrackpointBuilder withHeartRate(HeartRate heartRate) {
        this.heartRate = heartRate;
        return this;
    }

    public TrackpointBuilder withCadence(Cadence cadence) {
        this.cadence = cadence;
        return this;
    }

    public TrackpointBuilder withSensorState(SensorState sensorState) {
        this.sensorState = sensorState;
        return this;
    }

    public TrackpointBuilder withExtensions(TCXExtension... extensions) {
        this.extensions = extensions;
        return this;
    }

    public Trackpoint build() {
        validateArguments();
        return new Trackpoint(time, position, altitude, distance, heartRate, cadence, sensorState, extensions);
    }

    private void validateArguments() {
        if(time == null) {
            throw new IllegalArgumentException("Trackpoint must have a Time");
        }
    }
}
