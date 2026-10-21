package com.long2know.sportlogger.services;

import androidx.annotation.Nullable;

import com.long2know.sportlogger.acquisition.LegacyMeasurementBridge;
import com.long2know.sportlogger.acquisition.LiveMeasurementSnapshot;
import com.long2know.sportlogger.acquisition.RouteStatus;
import com.long2know.sportlogger.acquisition.healthservices.HealthServicesExerciseSnapshot;
import com.long2know.sportlogger.measurements.MeasurementEvent;
import com.long2know.sportlogger.recording.CompletedRecordingSnapshot;
import com.long2know.sportlogger.recording.RecordingSnapshot;
import com.long2know.utilities.models.SharedData;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class LiveMeasurementCallbackContractTest {
    @After
    public void tearDown() {
        SharedData.getInstance().resetMeasurements();
    }

    @Test
    public void publicCallbackReplaysCurrentSessionAndPublishesEmptyNewSession() {
        LegacyMeasurementBridge bridge = new LegacyMeasurementBridge();
        bridge.beginSession(17);
        bridge.applyRaw(MeasurementEvent.heartRate(1L, 0F));

        ServiceClientRegistry<ISportLoggerServiceClient> clients =
                new ServiceClientRegistry<>();
        AtomicReference<LiveMeasurementSnapshot> first = new AtomicReference<>();
        ISportLoggerServiceClient firstClient = client(first);
        clients.register(firstClient);
        clients.dispatch(client ->
                client.onLoggerUpdate(bridge.getLiveSnapshot()));

        assertEquals(17, first.get().getActivityId());
        assertEquals(0D, first.get().getHeartRateBpm().getValue(), 0D);

        clients.unregister(firstClient);
        AtomicReference<LiveMeasurementSnapshot> recreated = new AtomicReference<>();
        clients.register(client(recreated));
        clients.dispatch(client ->
                client.onLoggerUpdate(bridge.getLiveSnapshot()));

        assertEquals(17, recreated.get().getActivityId());
        assertEquals(0D, recreated.get().getHeartRateBpm().getValue(), 0D);

        bridge.beginSession(18);
        clients.dispatch(client ->
                client.onLoggerUpdate(bridge.getLiveSnapshot()));

        assertEquals(18, recreated.get().getActivityId());
        assertNull(recreated.get().getHeartRateBpm());
    }

    private static ISportLoggerServiceClient client(
            AtomicReference<LiveMeasurementSnapshot> latest) {
        return new ISportLoggerServiceClient() {
            @Override
            public void onLoggerUpdate(LiveMeasurementSnapshot snapshot) {
                latest.set(snapshot);
            }

            @Override
            public void onRecordingStateChanged(RecordingSnapshot snapshot) {
            }

            @Override
            public void onRouteStatusChanged(RouteStatus status) {
            }

            @Override
            public void onAcquisitionStateChanged(
                    HealthServicesExerciseSnapshot snapshot) {
            }

            @Override
            public void onLatestCompletedChanged(
                    @Nullable CompletedRecordingSnapshot snapshot) {
            }

            @Override
            public void onLoggerError(String code, String message) {
            }
        };
    }
}
