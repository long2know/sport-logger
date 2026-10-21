package com.long2know.sportlogger.sync;

import android.content.Context;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.long2know.utilities.transfer.CanonicalSyncProtocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class WearPhoneNodeResolver {
    private static final long LOOKUP_TIMEOUT_SECONDS = 30L;

    private final CapabilityClient capabilityClient;

    WearPhoneNodeResolver(Context context) {
        capabilityClient = Wearable.getCapabilityClient(context.getApplicationContext());
    }

    List<String> reachableNodeIds() throws Exception {
        CapabilityInfo capability = Tasks.await(
                capabilityClient.getCapability(
                        CanonicalSyncProtocol.PHONE_RECEIVER_CAPABILITY,
                        CapabilityClient.FILTER_REACHABLE),
                LOOKUP_TIMEOUT_SECONDS,
                TimeUnit.SECONDS);
        List<Node> nodes = new ArrayList<>(capability.getNodes());
        nodes.sort(
                Comparator.comparing(Node::isNearby)
                        .reversed()
                        .thenComparing(Node::getId));
        List<String> nodeIds = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            nodeIds.add(node.getId());
        }
        return Collections.unmodifiableList(nodeIds);
    }
}
