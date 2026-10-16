package com.long2know.sportlogger.measurements;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class MeasurementRuntime {
    private final List<ResourceOwner> owners;

    public MeasurementRuntime(ResourceOwner... owners) {
        this.owners = Collections.unmodifiableList(Arrays.asList(owners));
    }

    public void startAsync() {
        for (ResourceOwner owner : owners) {
            owner.startAsync();
        }
    }

    public void stopAsync() {
        for (ResourceOwner owner : owners) {
            owner.stopAsync();
        }
    }
}
