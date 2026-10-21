package com.long2know.sportlogger.healthconnect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Build;

import androidx.health.connect.client.HealthConnectClient;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class HealthConnectPlatformInstrumentedTest {
    @Test
    public void reportsTheRealDeviceSdkStatusWithoutAssumingInstallation() {
        Context context = ApplicationProvider.getApplicationContext();
        int status = HealthConnectClient.getSdkStatus(context);

        assertTrue(
                status == HealthConnectClient.SDK_UNAVAILABLE
                        || status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
                        || status == HealthConnectClient.SDK_AVAILABLE);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            assertEquals(HealthConnectClient.SDK_UNAVAILABLE, status);
        }
    }
}
