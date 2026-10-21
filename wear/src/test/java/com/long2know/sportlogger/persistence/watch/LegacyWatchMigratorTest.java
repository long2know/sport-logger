package com.long2know.sportlogger.persistence.watch;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.long2know.utilities.contract.CanonicalContractException;
import com.long2know.utilities.contract.CanonicalIds;
import com.long2know.utilities.contract.CanonicalPayload;
import com.long2know.utilities.contract.CanonicalTransfers;
import com.long2know.utilities.contract.proto.ActivityRecord;
import com.long2know.utilities.contract.proto.MeasurementSample;
import com.long2know.utilities.contract.proto.Position;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class LegacyWatchMigratorTest {
    private static final String SOURCE_IDENTITY = "sportlogger:test-watch:legacy-db";

    private WatchDatabase database;
    private WatchActivityRepository repository;
    private LegacyWatchMigrator migrator;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        database = Room.inMemoryDatabaseBuilder(context, WatchDatabase.class)
                .allowMainThreadQueries()
                .build();
        repository = new WatchActivityRepository(database, Runnable::run);
        migrator = new LegacyWatchMigrator(repository);
    }

    @After
    public void tearDown() {
        database.close();
    }

    @Test
    public void emptyFixtureCreatesVerifiedReceiptWithoutSessions() throws Exception {
        LegacyWatchMigrator.MigrationResult first =
                migrator.migrate(fixture("empty.db"), SOURCE_IDENTITY, 10_000L);
        LegacyWatchMigrator.MigrationResult repeat =
                migrator.migrate(fixture("empty.db"), SOURCE_IDENTITY, 20_000L);

        assertEquals(LegacyWatchMigrator.STATUS_COMPLETE, first.getStatus());
        assertEquals(0L, first.getSourceActivityCount());
        assertEquals(0L, first.getImportedActivityCount());
        assertEquals(64, first.getCanonicalSha256().length());
        assertFalse(first.isAlreadyCompleted());
        assertTrue(repeat.isAlreadyCompleted());
        assertEquals(first.getReceiptId(), repeat.getReceiptId());
    }

    @Test
    public void representativeFixturePreservesPrecisionPresenceAndDeterministicIds()
            throws Exception {
        File fixture = fixture("representative.db");
        String before = sha256(fixture);

        LegacyWatchMigrator.MigrationResult result =
                migrator.migrate(fixture, SOURCE_IDENTITY, 10_000L);

        assertEquals(before, sha256(fixture));
        assertEquals(LegacyWatchMigrator.STATUS_COMPLETE, result.getStatus());
        assertEquals(2L, result.getSourceActivityCount());
        assertEquals(3L, result.getSourceSampleCount());
        assertEquals(2L, result.getImportedActivityCount());
        assertEquals(3L, result.getImportedSampleCount());
        assertEquals(0L, result.getQuarantineCount());
        List<WatchActivityRepository.OutboxItem> outbox =
                repository.readyOutbox(10).join();
        assertEquals(2, outbox.size());

        String firstSession =
                CanonicalIds.deterministicLegacySessionId(SOURCE_IDENTITY, 1L);
        WatchActivityRepository.OutboxItem first = outbox.stream()
                .filter(item -> item.getSessionId().equals(firstSession))
                .findFirst()
                .orElseThrow(AssertionError::new);
        ActivityRecord firstActivity =
                CanonicalTransfers.parseActivityTransfer(first.getPayload())
                        .getPayload()
                        .getActivity();
        assertEquals(321.12500000000006D,
                firstActivity.getSummary().getDistanceM().getValue(),
                0D);
        Position precise = firstActivity.getSamples(0).getLocation().getValue();
        assertEquals(47.6205123456789D, precise.getLatitudeDegrees(), 0D);
        assertEquals(-122.3493123456789D, precise.getLongitudeDegrees(), 0D);
        assertFalse(firstActivity.getSamples(1).getLocation().getValue().hasAltitudeM());

        String zeroSession =
                CanonicalIds.deterministicLegacySessionId(SOURCE_IDENTITY, 2L);
        WatchActivityRepository.OutboxItem zero = outbox.stream()
                .filter(item -> item.getSessionId().equals(zeroSession))
                .findFirst()
                .orElseThrow(AssertionError::new);
        Position zeroPosition =
                CanonicalTransfers.parseActivityTransfer(zero.getPayload())
                        .getPayload()
                        .getActivity()
                        .getSamples(0)
                        .getLocation()
                        .getValue();
        assertEquals(0D, zeroPosition.getLatitudeDegrees(), 0D);
        assertEquals(0D, zeroPosition.getLongitudeDegrees(), 0D);
    }

    @Test
    public void malformedAndPartialFixturesQuarantineWithoutInventingValues()
            throws Exception {
        LegacyWatchMigrator.MigrationResult malformed = migrator.migrate(
                fixture("malformed.db"),
                SOURCE_IDENTITY + ":malformed",
                10_000L);
        LegacyWatchMigrator.MigrationResult partial = migrator.migrate(
                fixture("partial.db"),
                SOURCE_IDENTITY + ":partial",
                20_000L);

        assertEquals(LegacyWatchMigrator.STATUS_PARTIAL, malformed.getStatus());
        assertEquals(2L, malformed.getSourceActivityCount());
        assertEquals(1L, malformed.getImportedActivityCount());
        assertEquals(0L, malformed.getImportedSampleCount());
        assertTrue(malformed.getQuarantineCount() >= 2L);
        assertEquals(LegacyWatchMigrator.STATUS_PARTIAL, partial.getStatus());
        assertEquals(2L, partial.getSourceActivityCount());
        assertEquals(1L, partial.getImportedActivityCount());
        assertTrue(partial.getQuarantineCount() >= 2L);
    }

    @Test
    public void orphanAndCorruptFixturesHaveTypedHealthOutcomes() throws Exception {
        LegacyWatchMigrator.MigrationResult orphan = migrator.migrate(
                fixture("orphan.db"),
                SOURCE_IDENTITY + ":orphan",
                10_000L);
        LegacyWatchMigrator.MigrationResult corrupt = migrator.migrate(
                fixture("corrupt.db"),
                SOURCE_IDENTITY + ":corrupt",
                20_000L);

        assertEquals(LegacyWatchMigrator.STATUS_PARTIAL, orphan.getStatus());
        assertEquals(1L, orphan.getOrphanCount());
        assertEquals(LegacyWatchMigrator.STATUS_CORRUPT, corrupt.getStatus());
        assertEquals(0L, corrupt.getImportedActivityCount());
        assertTrue(corrupt.getQuarantineCount() >= 1L);
    }

    @Test
    public void interruptionLeavesNoReceiptAndRetryUsesSameIdsWithoutDuplicates()
            throws Exception {
        try {
            migrator.migrate(
                    fixture("representative.db"),
                    SOURCE_IDENTITY + ":interrupt",
                    10_000L,
                    imported -> {
                        if (imported == 1) {
                            throw new SimulatedInterruption();
                        }
                    });
            fail("Expected simulated interruption");
        } catch (SimulatedInterruption expected) {
            assertEquals(1, repository.readyOutbox(10).join().size());
        }

        LegacyWatchMigrator.MigrationResult retry = migrator.migrate(
                fixture("representative.db"),
                SOURCE_IDENTITY + ":interrupt",
                20_000L);

        assertEquals(LegacyWatchMigrator.STATUS_COMPLETE, retry.getStatus());
        assertEquals(2L, retry.getImportedActivityCount());
        assertEquals(2, repository.readyOutbox(10).join().size());
        assertFalse(retry.isAlreadyCompleted());
        LegacyWatchMigrator.MigrationResult repeat = migrator.migrate(
                fixture("representative.db"),
                SOURCE_IDENTITY + ":interrupt",
                30_000L);
        assertTrue(repeat.isAlreadyCompleted());
        assertEquals(2, repository.readyOutbox(10).join().size());
    }

    @Test
    public void largeFixtureValidatesAllPointsAndStableCanonicalChecksum()
            throws Exception {
        LegacyWatchMigrator.MigrationResult result = migrator.migrate(
                fixture("large.db"),
                SOURCE_IDENTITY + ":large",
                10_000L);

        assertEquals(LegacyWatchMigrator.STATUS_COMPLETE, result.getStatus());
        assertEquals(1_000L, result.getSourceSampleCount());
        assertEquals(1_000L, result.getImportedSampleCount());
        assertEquals(64, result.getCanonicalSha256().length());
        assertNotEquals(
                repeat("0", 64),
                result.getCanonicalSha256());
    }

    @Test
    public void fixtureManifestPinsEveryDatabaseHash() throws Exception {
        Properties manifest = new Properties();
        try (java.io.InputStream input = Files.newInputStream(
                fixture("fixture-manifest.properties").toPath())) {
            manifest.load(input);
        }
        File directory = fixture("empty.db").getParentFile();
        File[] databases = directory.listFiles(
                file -> file.getName().endsWith(".db"));
        assertNotNull(databases);
        for (File fixture : databases) {
            assertEquals(
                    manifest.getProperty(fixture.getName() + ".sha256"),
                    sha256(fixture));
            assertNotNull(manifest.getProperty(fixture.getName() + ".expected"));
        }
    }

    @Test
    public void timestampParserAcceptsVerifiedBlockAndRejectsMixedDigits() {
        String arabicIndic = translateDigits("20240229123456", 0x0660);
        assertEquals(
                1_709_210_096_000L,
                LegacyTimestampParser.parseUtcMillis(arabicIndic));
        try {
            LegacyTimestampParser.parseUtcMillis(
                    arabicIndic.substring(0, arabicIndic.length() - 1) + "6");
            fail("Expected mixed digit block rejection");
        } catch (CanonicalContractException expected) {
            assertEquals("INVALID_LEGACY_TIMESTAMP", expected.getCode());
        }
    }

    private static File fixture(String name) {
        return new File("src/test/resources/legacy-db-fixtures", name);
    }

    private static String sha256(File file) throws Exception {
        return CanonicalPayload.hex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(file.toPath())));
    }

    private static String translateDigits(String ascii, int zeroCodePoint) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < ascii.length(); index++) {
            result.appendCodePoint(zeroCodePoint + ascii.charAt(index) - '0');
        }
        return result.toString();
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }

    private static final class SimulatedInterruption extends RuntimeException {
    }
}
