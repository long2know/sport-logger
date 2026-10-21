package com.long2know.sportlogger.publishing.endurain.profile;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 26)
public final class EndurainConnectionProfileStoreTest {
    private static final String PREFERENCES =
            "endurain_connection_profiles_v1";

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        SharedPreferences preferences =
                context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        assertTrue(preferences.edit().clear().commit());
    }

    @Test
    public void settingsPersistWithoutCredentialMaterial() {
        EndurainConnectionProfileStore first =
                EndurainConnectionProfileStore.forApplication(context);
        EndurainConnectionProfile created = first.save(
                null,
                "Home Endurain",
                "https://ENDURAIN.example:443/base/",
                true);
        EndurainConnectionProfile restored =
                EndurainConnectionProfileStore.forApplication(context)
                        .get(created.getConnectionIdentity());

        assertEquals(
                "https://endurain.example/base",
                restored.getNormalizedBaseUrl());
        assertEquals("Home Endurain", restored.getLabel());
        assertTrue(restored.isEnabled());
        assertEquals(
                created.getCredentialReference(),
                restored.getCredentialReference());
        assertFalse(restored.getCredentialReference()
                .contains("endurain.example"));
        assertFalse(context.getSharedPreferences(
                        PREFERENCES,
                        Context.MODE_PRIVATE)
                .getAll()
                .toString()
                .contains("fixture-api-key"));
    }

    @Test
    public void identityStaysScopedToOneNormalizedInstance() {
        EndurainConnectionProfileStore store =
                EndurainConnectionProfileStore.forApplication(context);
        EndurainConnectionProfile first = store.save(
                null,
                "Primary",
                "https://one.example",
                true);

        try {
            store.save(
                    first.getConnectionIdentity(),
                    "Moved",
                    "https://two.example",
                    true);
            fail("Connection identity must remain server scoped");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("cannot be moved"));
        }

        EndurainConnectionProfile second = store.save(
                null,
                "Secondary",
                "https://two.example",
                false);
        assertNotEquals(
                first.getConnectionIdentity(),
                second.getConnectionIdentity());
        assertNotEquals(
                first.getCredentialReference(),
                second.getCredentialReference());
    }

    @Test
    public void nonHttpsAndUnsafeLabelsAreRejected() {
        EndurainConnectionProfileStore store =
                EndurainConnectionProfileStore.forApplication(context);

        assertRejected(() -> store.save(
                null,
                "Insecure",
                "http://endurain.example",
                true));
        assertRejected(() -> store.save(
                null,
                "Line one\nLine two",
                "https://endurain.example",
                true));
    }

    private static void assertRejected(Runnable action) {
        try {
            action.run();
            fail("Invalid connection settings must be rejected");
        } catch (IllegalArgumentException expected) {
        }
    }
}
