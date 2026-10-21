package com.long2know.sportlogger.publishing.endurain.factory;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.long2know.sportlogger.persistence.phone.PhoneDatabase;
import com.long2know.sportlogger.publishing.endurain.EndurainCredentialGateway;
import com.long2know.sportlogger.publishing.endurain.profile.EndurainConnectionProfile;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 26)
public final class EndurainPublishingFactoryTest {
    private Context context;
    private ExecutorService worker;
    private EndurainPublishingController controller;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(PhoneDatabase.DATABASE_NAME);
        assertTrue(context.getSharedPreferences(
                        "endurain_connection_profiles_v1",
                        Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit());
        worker = Executors.newSingleThreadExecutor();
    }

    @After
    public void tearDown() throws Exception {
        if (controller != null) {
            controller.close();
        }
        worker.shutdown();
        assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
        context.deleteDatabase(PhoneDatabase.DATABASE_NAME);
        context.getSharedPreferences(
                        "endurain_connection_profiles_v1",
                        Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
    }

    @Test
    public void productionFactoryComposesRealPersistentController()
            throws Exception {
        controller = EndurainPublishingFactory.create(context, worker)
                .get(10, TimeUnit.SECONDS);
        AtomicInteger callbacks = new AtomicInteger();
        AutoCloseable observation =
                controller.observe(Runnable::run, callbacks::incrementAndGet);
        EndurainConnectionProfile profile = controller.saveConnection(
                        null,
                        "Factory fixture",
                        "https://endurain.example",
                        true)
                .get(10, TimeUnit.SECONDS);
        assertTrue(callbacks.get() > 0);
        observation.close();
        EndurainPublishingSnapshot snapshot = controller.statusList()
                .get(10, TimeUnit.SECONDS);

        assertEquals(1, snapshot.getConnections().size());
        assertEquals(
                profile.getConnectionIdentity(),
                snapshot.getConnections()
                        .get(0)
                        .getProfile()
                        .getConnectionIdentity());
        assertEquals(
                EndurainCredentialGateway.State.NOT_CONFIGURED,
                snapshot.getConnections()
                        .get(0)
                        .getCredentialState());
        assertTrue(snapshot.getDeliveries().isEmpty());
    }
}
