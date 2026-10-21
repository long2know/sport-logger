package com.long2know.sportlogger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.net.Uri;
import android.util.Xml;

import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.long2know.utilities.export.tcx.TcxExportSource;
import com.long2know.utilities.export.tcx.TcxWriter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

@RunWith(AndroidJUnit4.class)
public final class TcxFileProviderTest {
    private static final String NAMESPACE =
            "http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2";

    private Context context;
    private File sharedDirectory;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        sharedDirectory = new File(context.getFilesDir(), "shared-tcx");
        deleteRecursively(sharedDirectory);
        assertTrue(sharedDirectory.mkdirs());
    }

    @After
    public void tearDown() {
        deleteRecursively(sharedDirectory);
    }

    @Test
    public void generatedTcxCanBeReadBackThroughNarrowProvider() throws Exception {
        File outputFile = new File(sharedDirectory, "synthetic-output.tcx");
        try (FileOutputStream output = new FileOutputStream(outputFile)) {
            new TcxWriter().write(new CompleteSource(), output);
        }
        Uri uri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                outputFile);

        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(input, "UTF-8");
            while (parser.getEventType() != XmlPullParser.START_TAG) {
                parser.next();
            }
            assertEquals(NAMESPACE, parser.getNamespace());
            assertEquals("TrainingCenterDatabase", parser.getName());
        }

        try {
            FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    new File(context.getFilesDir(), "received-sport-activities/private.ser"));
            fail("The FileProvider must not expose the private receipt inbox");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage() != null);
        }
    }

    private static final class CompleteSource implements TcxExportSource {
        @Override
        public Long getStartTimeUtcMillis() {
            return 1_700_000_000_000L;
        }

        @Override
        public Double getDurationSeconds() {
            return 60D;
        }

        @Override
        public Double getDistanceMeters() {
            return 100D;
        }

        @Override
        public Integer getCalories() {
            return 10;
        }

        @Override
        public Intensity getIntensity() {
            return Intensity.ACTIVE;
        }

        @Override
        public TriggerMethod getTriggerMethod() {
            return TriggerMethod.MANUAL;
        }

        @Override
        public Sport getSport() {
            return Sport.OTHER;
        }

        @Override
        public Integer getAverageHeartRateBpm() {
            return 100;
        }

        @Override
        public Integer getMaximumHeartRateBpm() {
            return 100;
        }

        @Override
        public String getNotes() {
            return "Clearly synthetic output";
        }

        @Override
        public TrackPointCursor openTrackPoints() {
            return new TrackPointCursor() {
                private boolean available = true;

                @Override
                public boolean moveNext() {
                    boolean result = available;
                    available = false;
                    return result;
                }

                @Override
                public Long getTimeUtcMillis() {
                    return 1_700_000_000_000L;
                }

                @Override
                public boolean hasPosition() {
                    return true;
                }

                @Override
                public double getLatitudeDegrees() {
                    return 47.6205D;
                }

                @Override
                public double getLongitudeDegrees() {
                    return -122.3493D;
                }

                @Override
                public Double getAltitudeMeters() {
                    return 10D;
                }

                @Override
                public Double getDistanceMeters() {
                    return 0D;
                }

                @Override
                public Integer getHeartRateBpm() {
                    return 100;
                }

                @Override
                public void close() {
                }
            };
        }
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
