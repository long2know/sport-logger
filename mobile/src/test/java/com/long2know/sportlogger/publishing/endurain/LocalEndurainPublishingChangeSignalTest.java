package com.long2know.sportlogger.publishing.endurain;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 26)
public final class LocalEndurainPublishingChangeSignalTest {
    @Test
    public void observersReceiveOnlyLiveProcessLocalChanges()
            throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        LocalEndurainPublishingChangeSignal signal =
                new LocalEndurainPublishingChangeSignal(context);
        AtomicInteger callbacks = new AtomicInteger();
        AutoCloseable observation =
                signal.observe(Runnable::run, callbacks::incrementAndGet);

        signal.signal();
        assertEquals(1, callbacks.get());

        observation.close();
        signal.signal();
        assertEquals(1, callbacks.get());
    }
}
