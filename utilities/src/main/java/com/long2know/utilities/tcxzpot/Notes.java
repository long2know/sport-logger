package com.long2know.utilities.tcxzpot;

public class Notes implements TCXSerializable {

    public static Notes notes(String notes) {
        return new Notes(notes);
    }

    private final String notes;

    public Notes(String notes) {
        this.notes = notes;
    }

    @Override
    public void serialize(Serializer serializer) {
        serializer.print("<Notes>" + notes + "</Notes>");
    }
}
