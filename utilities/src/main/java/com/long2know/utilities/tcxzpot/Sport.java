package com.long2know.utilities.tcxzpot;

public enum Sport implements TCXSerializable {
    RUNNING("Running"),
    BIKING("Biking"),
    OTHER("Other");

    private final String rawValue;

    Sport(String rawValue) {
        this.rawValue = rawValue;
    }

    @Override
    public void serialize(Serializer serializer) {
        serializer.print(rawValue);
    }

    @Override
    public String toString() {
        return rawValue;
    }
}
