package com.long2know.sportlogger.recording;

import com.long2know.utilities.models.SportActivity;
import com.long2know.utilities.transfer.ActivityTransferProtocol;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class WearDataLayerExporterTest {
    @Test
    public void uniqueDataItemsQueueOfflineActivitiesWithoutAcknowledgingThem()
            throws Exception {
        FakeReceiptStore receipts = new FakeReceiptStore();
        FakeWriter writer = new FakeWriter();
        WearDataLayerExporter exporter = new WearDataLayerExporter(
                "/sportactivity",
                Runnable::run,
                receipts,
                writer);
        CallbackRecorder first = new CallbackRecorder();
        CallbackRecorder second = new CallbackRecorder();

        exporter.export(activity(11, 1_000L), first);
        exporter.export(activity(12, 2_000L), second);

        assertEquals(4, writer.writes.size());
        assertNotEquals(writer.writes.get(0).path, writer.writes.get(2).path);
        assertTrue(writer.writes.get(0).path.startsWith("/sportactivity/"));
        assertEquals("/sportactivity", writer.writes.get(1).path);
        assertTrue(writer.writes.get(2).path.startsWith("/sportactivity/"));
        assertEquals("/sportactivity", writer.writes.get(3).path);
        assertFalse(writer.writes.get(0).legacyAlias);
        assertTrue(writer.writes.get(1).legacyAlias);
        assertEquals(1, first.queued.get());
        assertEquals(1, second.queued.get());
        assertEquals(0, first.acknowledged.get());
        assertEquals(0, second.acknowledged.get());
        assertEquals(11, SportActivity.deserialize(writer.writes.get(0).payload).Id);
        assertEquals(12, SportActivity.deserialize(writer.writes.get(2).payload).Id);
    }

    @Test
    public void durableReceiptIsRequiredBeforeAcknowledgedCallback() throws Exception {
        FakeReceiptStore receipts = new FakeReceiptStore();
        FakeWriter writer = new FakeWriter();
        WearDataLayerExporter exporter = new WearDataLayerExporter(
                "/sportactivity",
                Runnable::run,
                receipts,
                writer);
        SportActivity activity = activity(21, 3_000L);
        CallbackRecorder queued = new CallbackRecorder();

        exporter.export(activity, queued);
        String transferId = writer.writes.get(0).transferId;
        assertEquals(1, queued.queued.get());
        assertEquals(0, queued.acknowledged.get());

        receipts.acknowledge(transferId);
        CallbackRecorder acknowledged = new CallbackRecorder();
        exporter.export(activity, acknowledged);

        assertEquals(2, writer.writes.size());
        assertEquals(0, acknowledged.queued.get());
        assertEquals(1, acknowledged.acknowledged.get());
        assertFalse(receipts.receipts.contains(transferId));
    }

    private static SportActivity activity(int id, long startedAt) {
        SportActivity activity = new SportActivity();
        activity.Id = id;
        activity.StartTimeUTC = new Date(startedAt);
        return activity;
    }

    private static final class FakeReceiptStore implements TransferReceiptStore {
        private final Set<String> receipts = new HashSet<>();

        @Override
        public synchronized void acknowledge(String transferId) {
            receipts.add(transferId);
        }

        @Override
        public synchronized boolean consumeAcknowledgement(String transferId) {
            return receipts.remove(transferId);
        }
    }

    private static final class FakeWriter implements WearDataLayerExporter.DataItemWriter {
        private final List<Write> writes = new ArrayList<>();

        @Override
        public void put(
                String path,
                byte[] payload,
                int activityId,
                String transferId,
                String payloadSha256,
                long attempt,
                boolean legacyAlias,
                WearDataLayerExporter.Completion completion) {
            assertEquals(activityId, SportActivityId.read(payload));
            assertEquals(ActivityTransferProtocol.payloadSha256(payload), payloadSha256);
            writes.add(new Write(
                    path,
                    payload.clone(),
                    transferId,
                    attempt,
                    legacyAlias));
            completion.onSuccess();
        }
    }

    private static final class Write {
        private final String path;
        private final byte[] payload;
        private final String transferId;
        private final long attempt;
        private final boolean legacyAlias;

        private Write(
                String path,
                byte[] payload,
                String transferId,
                long attempt,
                boolean legacyAlias) {
            this.path = path;
            this.payload = payload;
            this.transferId = transferId;
            this.attempt = attempt;
            this.legacyAlias = legacyAlias;
        }
    }

    private static final class CallbackRecorder implements RecordingExporter.Callback {
        private final AtomicInteger queued = new AtomicInteger();
        private final AtomicInteger acknowledged = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();

        @Override
        public void onQueued() {
            queued.incrementAndGet();
        }

        @Override
        public void onAcknowledged() {
            acknowledged.incrementAndGet();
        }

        @Override
        public void onFailure(Throwable error) {
            failed.incrementAndGet();
        }
    }

    private static final class SportActivityId {
        private static int read(byte[] payload) {
            try {
                return SportActivity.deserialize(payload).Id;
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        }
    }
}
