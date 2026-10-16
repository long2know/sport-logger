package com.long2know.sportlogger.services;

import android.content.pm.ServiceInfo;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ForegroundServiceControllerTest {
    @Test
    public void disabledLocationUsesHealthOnlyThenRecoversCombinedTypes() {
        List<Integer> promotions = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        ForegroundServiceController controller = new ForegroundServiceController(
                promotions::add,
                (message, error) -> errors.add(message));

        assertTrue(controller.ensure(true, false, true));
        assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH,
                (int) promotions.get(0));

        assertTrue(controller.ensure(true, true, true));
        assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                        | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                (int) promotions.get(1));
        assertTrue(errors.isEmpty());
    }

    @Test
    public void combinedPromotionFailureIsReportedBeforeHealthOnlyFallback() {
        List<Integer> promotions = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        ForegroundServiceController controller = new ForegroundServiceController(
                types -> {
                    promotions.add(types);
                    if (calls.getAndIncrement() == 0) {
                        throw new IllegalStateException("location prerequisite changed");
                    }
                },
                (message, error) -> errors.add(
                        message + ":" + (error == null ? "" : error.getMessage())));

        assertTrue(controller.ensure(true, true, true));

        assertEquals(2, promotions.size());
        assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                        | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                (int) promotions.get(0));
        assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH,
                (int) promotions.get(1));
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("location prerequisite changed"));
    }
}
