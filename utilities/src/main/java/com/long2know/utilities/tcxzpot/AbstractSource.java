package com.long2know.utilities.tcxzpot;

public abstract class AbstractSource implements TCXSerializable {

    protected final String name;

    public AbstractSource(String name) {
        this.name = name;
    }

    public abstract String tcxType();
}
