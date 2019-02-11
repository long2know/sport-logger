package com.long2know.utilities.tcxzpot.serializers;

import com.long2know.utilities.tcxzpot.Serializer;

public class ConsoleSerializer implements Serializer {

    @Override
    public void print(String line) {
        System.out.println(line);
    }
    
}
