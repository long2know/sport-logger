package com.long2know.utilities.models;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;


public class SportActivity implements Serializable {
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

    public static byte[] serialize(SportActivity obj) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(obj);
            byte[] bytes = bos.toByteArray();
            return bytes;
    }

    public static SportActivity deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        ObjectInputStream is = new ObjectInputStream(in);
        return (SportActivity) is.readObject();
    }
}
