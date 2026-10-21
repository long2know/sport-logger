package com.long2know.sportlogger.publishing.endurain.factory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class EndurainPublishingSnapshot {
    private final List<EndurainConnectionStatus> connections;
    private final List<EndurainDeliveryStatus> deliveries;

    public EndurainPublishingSnapshot(
            List<EndurainConnectionStatus> connections,
            List<EndurainDeliveryStatus> deliveries) {
        this.connections = Collections.unmodifiableList(
                new ArrayList<>(connections));
        this.deliveries = Collections.unmodifiableList(
                new ArrayList<>(deliveries));
    }

    public List<EndurainConnectionStatus> getConnections() {
        return connections;
    }

    public List<EndurainDeliveryStatus> getDeliveries() {
        return deliveries;
    }
}
