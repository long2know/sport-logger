package com.long2know.sportlogger.publishing.endurain;

import android.content.Context;

import androidx.core.content.FileProvider;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public final class EndurainPublishingFileProviderTest {
    @Test
    public void privateArtifactsAndCredentialMaterialAreNotShareable()
            throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File artifact = new File(
                context.getNoBackupFilesDir(),
                "endurain-publishing-v1/fixture/activity.tcx");
        File credential = new File(
                context.getNoBackupFilesDir(),
                "private-credential-material/fixture.bin");
        writeFixture(artifact);
        writeFixture(credential);
        try {
            assertNotShareable(context, artifact);
            assertNotShareable(context, credential);
        } finally {
            assertTrue(artifact.delete());
            assertTrue(artifact.getParentFile().delete());
            assertTrue(credential.delete());
            assertTrue(credential.getParentFile().delete());
        }
    }

    private static void writeFixture(File file) throws Exception {
        assertTrue(file.getParentFile().isDirectory()
                || file.getParentFile().mkdirs());
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(new byte[]{1, 2, 3});
            output.getFD().sync();
        }
    }

    private static void assertNotShareable(Context context, File file) {
        try {
            FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    file);
            fail("Private publishing material must not be exposed");
        } catch (IllegalArgumentException expected) {
        }
    }
}
