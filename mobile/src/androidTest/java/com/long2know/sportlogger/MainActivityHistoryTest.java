package com.long2know.sportlogger;

import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.anything;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Xml;
import android.view.KeyEvent;

import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.long2know.sportlogger.transfer.PhoneActivityStore;
import com.long2know.utilities.export.tcx.TcxWriter;
import com.long2know.utilities.models.SportActivity;
import com.long2know.utilities.models.SportTrackPoint;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;

@RunWith(AndroidJUnit4.class)
public final class MainActivityHistoryTest {
    private Instrumentation instrumentation;
    private Context context;
    private MainActivity activity;

    @Before
    public void setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        deleteRecursively(new File(context.getFilesDir(), "received-sport-activities"));
        deleteRecursively(new File(context.getFilesDir(), "shared-tcx"));
    }

    @After
    public void tearDown() {
        if (activity != null) {
            instrumentation.runOnMainSync(activity::finish);
            activity = null;
        }
        deleteRecursively(new File(context.getFilesDir(), "received-sport-activities"));
        deleteRecursively(new File(context.getFilesDir(), "shared-tcx"));
    }

    @Test
    public void persistedHistoryAndDetailSurviveActivityRecreation() throws Exception {
        persist(activity("Synthetic restart activity", 1_700_000_000_000L));
        launch();
        waitForText("Synthetic restart activity");
        capture("phone-history.png");

        onData(anything())
                .inAdapterView(withId(R.id.activity_list))
                .atPosition(0)
                .perform(click());
        waitForText("Elapsed duration");

        instrumentation.runOnMainSync(activity::recreate);
        waitForText("Synthetic restart activity");
        onView(withText("Elapsed duration")).check(matches(isDisplayed()));
        onView(withText("Received from watch")).check(matches(isDisplayed()));
        onView(withId(R.id.detail_title)).perform(scrollTo());
        capture("phone-detail-after-restart.png");
    }

    @Test
    public void corruptRecordShowsPartialStateWithoutCrashing() throws Exception {
        persist(activity("Synthetic readable activity", 1_700_000_100_000L));
        File directory =
                new File(context.getFilesDir(), "received-sport-activities");
        try (FileOutputStream output =
                     new FileOutputStream(new File(directory, "corrupt.ser"))) {
            output.write(new byte[]{0x01, 0x02, 0x03});
        }

        launch();

        waitForText("Synthetic readable activity");
        waitForText("1 stored activity is unavailable or corrupt. Readable activities are shown.");
        capture("phone-partial-corrupt.png");
    }

    @Test
    public void legacyExportUsesVisibleSchemaPlaceholdersAndWritesDocument() throws Exception {
        persist(activity("Synthetic export activity", 1_700_000_200_000L));
        launch();
        waitForText("Synthetic export activity");
        onData(anything())
                .inAdapterView(withId(R.id.activity_list))
                .atPosition(0)
                .perform(click());
        waitForText("Save TCX");
        waitForText(context.getString(R.string.export_format_placeholder_warning));
        capture("phone-export-placeholder-warning.png");

        File sharedDirectory = new File(context.getFilesDir(), "shared-tcx");
        assertTrue(sharedDirectory.exists() || sharedDirectory.mkdirs());
        File destination = new File(sharedDirectory, "ui-export-output.tcx");
        Uri destinationUri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                destination);
        onView(withId(R.id.save_tcx_button)).perform(click());
        instrumentation.waitForIdleSync();
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        waitForText(context.getString(R.string.export_cancelled));
        Intent resultData = new Intent().setData(destinationUri);
        instrumentation.runOnMainSync(() -> activity.onActivityResult(
                9001,
                android.app.Activity.RESULT_OK,
                resultData));
        waitForText(context.getString(R.string.export_saved));

        assertTrue(destination.isFile());
        assertTrue(destination.length() > 0L);
        assertExportPlaceholders(destination);
        publishEvidence(destination, "synthetic-ui-export.tcx", TcxWriter.MEDIA_TYPE);
        capture("phone-export-saved.png");
    }

    @Test
    public void legacyShareGeneratesReadableTcxBeforeChooser() throws Exception {
        persist(activity("Synthetic share activity", 1_700_000_300_000L));
        launch();
        waitForText("Synthetic share activity");
        onData(anything())
                .inAdapterView(withId(R.id.activity_list))
                .atPosition(0)
                .perform(click());
        waitForText("Share TCX");

        onView(withId(R.id.share_tcx_button)).perform(click());

        File shared = waitForSharedTcx();
        assertExportPlaceholders(shared);
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        waitForText(context.getString(R.string.export_format_placeholder_warning));
    }

    private void persist(SportActivity sportActivity) throws Exception {
        byte[] payload = SportActivity.serialize(sportActivity);
        assertTrue(new PhoneActivityStore(context).persist(
                "synthetic-watch",
                "synthetic-" + sportActivity.Id,
                payload));
    }

    private void launch() {
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity = (MainActivity) instrumentation.startActivitySync(intent);
        instrumentation.waitForIdleSync();
    }

    private void waitForText(String value) {
        Throwable lastFailure = null;
        long deadline = SystemClock.uptimeMillis() + 8_000L;
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                onView(withText(value)).check(matches(isDisplayed()));
                return;
            } catch (AssertionError | RuntimeException error) {
                lastFailure = error;
                SystemClock.sleep(100L);
            }
        }
        if (lastFailure != null) {
            throw new AssertionError("Timed out waiting for text: " + value, lastFailure);
        }
        fail("Timed out waiting for text: " + value);
    }

    private File waitForSharedTcx() {
        File directory = new File(context.getFilesDir(), "shared-tcx");
        long deadline = SystemClock.uptimeMillis() + 8_000L;
        while (SystemClock.uptimeMillis() < deadline) {
            File[] files = directory.listFiles(
                    file -> file.isFile() && file.getName().endsWith(".tcx"));
            if (files != null && files.length == 1 && files[0].length() > 0L) {
                return files[0];
            }
            SystemClock.sleep(100L);
        }
        throw new AssertionError("Timed out waiting for shared TCX output");
    }

    private void capture(String fileName) throws Exception {
        instrumentation.waitForIdleSync();
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        String relativePath = Environment.DIRECTORY_DOWNLOADS + "/SportLoggerEvidence/";
        context.getContentResolver().delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                        + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                new String[]{fileName, relativePath});
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri destination = context.getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values);
        assertNotNull(destination);
        try (OutputStream output =
                     context.getContentResolver().openOutputStream(destination, "w")) {
            assertNotNull(output);
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            screenshot.recycle();
        }
        ContentValues complete = new ContentValues();
        complete.put(MediaStore.MediaColumns.IS_PENDING, 0);
        assertTrue(context.getContentResolver().update(
                destination,
                complete,
                null,
                null) == 1);
    }

    private void publishEvidence(
            File source,
            String fileName,
            String mediaType) throws Exception {
        String relativePath = Environment.DIRECTORY_DOWNLOADS + "/SportLoggerEvidence/";
        context.getContentResolver().delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                        + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                new String[]{fileName, relativePath});
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mediaType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri destination = context.getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values);
        assertNotNull(destination);
        try (InputStream input = new FileInputStream(source);
             OutputStream output =
                     context.getContentResolver().openOutputStream(destination, "w")) {
            assertNotNull(output);
            byte[] buffer = new byte[16 * 1024];
            for (int read = input.read(buffer); read != -1; read = input.read(buffer)) {
                output.write(buffer, 0, read);
            }
        }
        ContentValues complete = new ContentValues();
        complete.put(MediaStore.MediaColumns.IS_PENDING, 0);
        assertTrue(context.getContentResolver().update(
                destination,
                complete,
                null,
                null) == 1);
    }

    private static void assertExportPlaceholders(File file) throws Exception {
        boolean foundCaloriesPlaceholder = false;
        boolean foundCaloriesValue = false;
        int placeholderCount = 0;
        try (InputStream input = new FileInputStream(file)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(input, "UTF-8");
            for (int event = parser.getEventType();
                 event != XmlPullParser.END_DOCUMENT;
                 event = parser.next()) {
                if (event != XmlPullParser.START_TAG) {
                    continue;
                }
                if ("Calories".equals(parser.getName())) {
                    foundCaloriesValue = "0".equals(parser.nextText());
                } else if ("Placeholder".equals(parser.getName())) {
                    placeholderCount++;
                    if ("Calories".equals(parser.getAttributeValue(null, "Field"))) {
                        foundCaloriesPlaceholder = true;
                    }
                }
            }
        }
        assertTrue(foundCaloriesValue);
        assertTrue(foundCaloriesPlaceholder);
        assertTrue(placeholderCount >= 4);
    }

    private static SportActivity activity(String name, long startTime) {
        SportActivity activity = new SportActivity();
        activity.Id = (int) (startTime % 100_000L) + 1;
        activity.Name = name;
        activity.Description = "Clearly synthetic test activity";
        activity.StartTimeUTC = new Date(startTime);
        activity.EndTimeUTC = new Date(startTime + 65_000L);
        SportTrackPoint first = new SportTrackPoint();
        first.TimeStampUTC = new Date(startTime);
        first.Latitude = 47.6205D;
        first.Longitude = -122.3493D;
        first.HeartRate = 100D;
        SportTrackPoint second = new SportTrackPoint();
        second.TimeStampUTC = new Date(startTime + 1_000L);
        second.Latitude = 47.6206D;
        second.Longitude = -122.3492D;
        second.HeartRate = 105D;
        activity.SportTrackPoints.add(first);
        activity.SportTrackPoints.add(second);
        return activity;
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
