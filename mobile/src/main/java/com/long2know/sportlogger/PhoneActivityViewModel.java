package com.long2know.sportlogger;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.lifecycle.ViewModel;

import com.long2know.sportlogger.history.LegacyInboxActivityDataSource;
import com.long2know.sportlogger.history.PhoneActivityDataSource;
import com.long2know.sportlogger.transfer.PhoneActivityStore;
import com.long2know.utilities.export.tcx.TcxCancellation;
import com.long2know.utilities.export.tcx.TcxWriter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PhoneActivityViewModel extends ViewModel {
    public enum Screen {
        HISTORY,
        DETAIL
    }

    public enum Notice {
        NONE,
        EXPORT_SAVED,
        EXPORT_CANCELLED,
        EXPORT_FAILED_DESTINATION_REMOVED,
        EXPORT_FAILED_PARTIAL_DESTINATION,
        SHARE_LAUNCH_FAILED
    }

    public enum EventType {
        CREATE_DOCUMENT,
        SHARE_FILE
    }

    public interface Observer {
        void onStateChanged(State state);

        void onEvent(PendingEvent event);
    }

    public static final class State {
        private final Screen screen;
        private final PhoneActivityDataSource.HistoryResult history;
        private final PhoneActivityDataSource.ActivityRecord activity;
        private final String selectedStorageId;
        private final boolean historyLoading;
        private final boolean detailLoading;
        private final boolean exporting;
        private final Throwable failure;
        private final Notice notice;

        private State(
                Screen screen,
                PhoneActivityDataSource.HistoryResult history,
                PhoneActivityDataSource.ActivityRecord activity,
                String selectedStorageId,
                boolean historyLoading,
                boolean detailLoading,
                boolean exporting,
                Throwable failure,
                Notice notice) {
            this.screen = screen;
            this.history = history;
            this.activity = activity;
            this.selectedStorageId = selectedStorageId;
            this.historyLoading = historyLoading;
            this.detailLoading = detailLoading;
            this.exporting = exporting;
            this.failure = failure;
            this.notice = notice;
        }

        public Screen getScreen() {
            return screen;
        }

        public PhoneActivityDataSource.HistoryResult getHistory() {
            return history;
        }

        public PhoneActivityDataSource.ActivityRecord getActivity() {
            return activity;
        }

        public String getSelectedStorageId() {
            return selectedStorageId;
        }

        public boolean isHistoryLoading() {
            return historyLoading;
        }

        public boolean isDetailLoading() {
            return detailLoading;
        }

        public boolean isExporting() {
            return exporting;
        }

        public Throwable getFailure() {
            return failure;
        }

        public Notice getNotice() {
            return notice;
        }
    }

    public static final class PendingEvent {
        private final long id;
        private final EventType type;
        private final String storageId;
        private final String suggestedFileName;
        private final File sharedFile;

        private PendingEvent(
                long id,
                EventType type,
                String storageId,
                String suggestedFileName,
                File sharedFile) {
            this.id = id;
            this.type = type;
            this.storageId = storageId;
            this.suggestedFileName = suggestedFileName;
            this.sharedFile = sharedFile;
        }

        public long getId() {
            return id;
        }

        public EventType getType() {
            return type;
        }

        public String getStorageId() {
            return storageId;
        }

        public String getSuggestedFileName() {
            return suggestedFileName;
        }

        public File getSharedFile() {
            return sharedFile;
        }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final TcxWriter tcxWriter = new TcxWriter();
    private final TcxCancellation cancellation =
            () -> Thread.currentThread().isInterrupted();

    private Context applicationContext;
    private PhoneActivityDataSource dataSource;
    private Observer observer;
    private Screen screen = Screen.HISTORY;
    private PhoneActivityDataSource.HistoryResult history;
    private PhoneActivityDataSource.ActivityRecord activity;
    private String selectedStorageId;
    private String pendingSaveStorageId;
    private boolean historyLoading;
    private boolean detailLoading;
    private boolean exporting;
    private Throwable failure;
    private Notice notice = Notice.NONE;
    private PendingEvent pendingEvent;
    private long eventId;
    private long historyGeneration;
    private long detailGeneration;
    private long exportGeneration;

    @MainThread
    public void initialize(Context context) {
        if (applicationContext != null) {
            return;
        }
        applicationContext = context.getApplicationContext();
        dataSource = new LegacyInboxActivityDataSource(
                new PhoneActivityStore(applicationContext));
    }

    @MainThread
    public void attach(Observer observer) {
        this.observer = observer;
        notifyObserver();
    }

    @MainThread
    public void detach(Observer observer) {
        if (this.observer == observer) {
            this.observer = null;
        }
    }

    @MainThread
    public void showHistory() {
        screen = Screen.HISTORY;
        selectedStorageId = null;
        activity = null;
        detailLoading = false;
        detailGeneration++;
        failure = null;
        notice = Notice.NONE;
        notifyObserver();
        loadHistory();
    }

    @MainThread
    public void loadHistory() {
        requireInitialized();
        long generation = ++historyGeneration;
        historyLoading = true;
        if (screen == Screen.HISTORY) {
            failure = null;
        }
        notifyObserver();
        worker.execute(() -> {
            try {
                PhoneActivityDataSource.HistoryResult result =
                        dataSource.loadHistory(
                                LegacyInboxActivityDataSource.DEFAULT_HISTORY_LIMIT);
                mainHandler.post(() -> {
                    if (generation != historyGeneration) {
                        return;
                    }
                    history = result;
                    historyLoading = false;
                    if (screen == Screen.HISTORY) {
                        failure = null;
                    }
                    notifyObserver();
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (generation != historyGeneration) {
                        return;
                    }
                    historyLoading = false;
                    if (screen == Screen.HISTORY) {
                        failure = error;
                    }
                    notifyObserver();
                });
            }
        });
    }

    @MainThread
    public void selectActivity(String storageId) {
        requireInitialized();
        screen = Screen.DETAIL;
        if (activity == null
                || !storageId.equals(activity.getSummary().getStorageId())) {
            activity = null;
        }
        selectedStorageId = storageId;
        detailLoading = true;
        failure = null;
        notice = Notice.NONE;
        long generation = ++detailGeneration;
        notifyObserver();
        worker.execute(() -> {
            try {
                PhoneActivityDataSource.ActivityRecord record =
                        dataSource.loadActivity(storageId);
                mainHandler.post(() -> {
                    if (generation != detailGeneration
                            || !storageId.equals(selectedStorageId)) {
                        return;
                    }
                    activity = record;
                    detailLoading = false;
                    failure = null;
                    notifyObserver();
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (generation != detailGeneration
                            || !storageId.equals(selectedStorageId)) {
                        return;
                    }
                    activity = null;
                    detailLoading = false;
                    failure = error;
                    notifyObserver();
                });
            }
        });
    }

    @MainThread
    public void requestSave() {
        PhoneActivityDataSource.ActivityRecord record = activity;
        if (record == null || exporting) {
            return;
        }
        String storageId = record.getSummary().getStorageId();
        long generation = ++exportGeneration;
        exporting = true;
        failure = null;
        notice = Notice.NONE;
        notifyObserver();
        worker.execute(() -> {
            try {
                tcxWriter.validate(record.getExportSource());
                String fileName = exportFileName(record);
                mainHandler.post(() -> {
                    if (generation != exportGeneration) {
                        return;
                    }
                    exporting = false;
                    pendingSaveStorageId = storageId;
                    pendingEvent = new PendingEvent(
                            ++eventId,
                            EventType.CREATE_DOCUMENT,
                            storageId,
                            fileName,
                            null);
                    notifyObserver();
                });
            } catch (Exception error) {
                postExportFailure(generation, error);
            }
        });
    }

    @MainThread
    public void saveToUri(Uri destination) {
        requireInitialized();
        String storageId = pendingSaveStorageId != null
                ? pendingSaveStorageId
                : selectedStorageId;
        if (storageId == null || destination == null || exporting) {
            return;
        }
        pendingSaveStorageId = storageId;
        long generation = ++exportGeneration;
        exporting = true;
        failure = null;
        notice = Notice.NONE;
        notifyObserver();
        worker.execute(() -> writeDestination(generation, storageId, destination));
    }

    @MainThread
    public void saveCancelled() {
        pendingSaveStorageId = null;
        notice = Notice.EXPORT_CANCELLED;
        notifyObserver();
    }

    @MainThread
    public void requestShare() {
        PhoneActivityDataSource.ActivityRecord record = activity;
        if (record == null || exporting) {
            return;
        }
        String storageId = record.getSummary().getStorageId();
        long generation = ++exportGeneration;
        exporting = true;
        failure = null;
        notice = Notice.NONE;
        notifyObserver();
        worker.execute(() -> writeSharedFile(generation, storageId, record));
    }

    @MainThread
    public void consumeEvent(long id) {
        if (pendingEvent != null && pendingEvent.id == id) {
            pendingEvent = null;
        }
    }

    @MainThread
    public void shareLaunchFailed(Throwable error) {
        failure = error;
        notice = Notice.SHARE_LAUNCH_FAILED;
        notifyObserver();
    }

    public String getSelectedStorageId() {
        return selectedStorageId;
    }

    private void writeDestination(long generation, String storageId, Uri destination) {
        ContentResolver resolver = applicationContext.getContentResolver();
        boolean removed = false;
        try {
            PhoneActivityDataSource.ActivityRecord record =
                    loadRecordForExport(storageId);
            tcxWriter.validate(record.getExportSource());
            try (OutputStream output = resolver.openOutputStream(destination, "wt")) {
                if (output == null) {
                    throw new IOException("The selected destination cannot be opened");
                }
                tcxWriter.write(record.getExportSource(), output, cancellation);
            }
            mainHandler.post(() -> {
                if (generation != exportGeneration) {
                    return;
                }
                exporting = false;
                pendingSaveStorageId = null;
                failure = null;
                notice = Notice.EXPORT_SAVED;
                notifyObserver();
            });
            return;
        } catch (Exception error) {
            try {
                removed = resolver.delete(destination, null, null) > 0;
            } catch (RuntimeException ignored) {
                removed = false;
            }
            final boolean destinationRemoved = removed;
            mainHandler.post(() -> {
                if (generation != exportGeneration) {
                    return;
                }
                exporting = false;
                pendingSaveStorageId = null;
                failure = error;
                notice = destinationRemoved
                        ? Notice.EXPORT_FAILED_DESTINATION_REMOVED
                        : Notice.EXPORT_FAILED_PARTIAL_DESTINATION;
                notifyObserver();
            });
        }
    }

    private void writeSharedFile(
            long generation,
            String storageId,
            PhoneActivityDataSource.ActivityRecord record) {
        File sharedDirectory = new File(applicationContext.getFilesDir(), "shared-tcx");
        File pending = null;
        try {
            tcxWriter.validate(record.getExportSource());
            if (!sharedDirectory.exists() && !sharedDirectory.mkdirs()) {
                throw new IOException("Could not create the TCX share directory");
            }
            if (!sharedDirectory.isDirectory()) {
                throw new IOException("TCX share storage is unavailable");
            }
            File destination = new File(sharedDirectory, exportFileName(record));
            pending = new File(sharedDirectory, destination.getName() + ".pending");
            try (FileOutputStream output = new FileOutputStream(pending, false)) {
                tcxWriter.write(record.getExportSource(), output, cancellation);
                output.getFD().sync();
            }
            if (!pending.renameTo(destination)) {
                throw new IOException("Could not finalize the shared TCX file");
            }
            deleteOtherSharedFiles(sharedDirectory, destination);
            File ready = destination;
            mainHandler.post(() -> {
                if (generation != exportGeneration) {
                    return;
                }
                exporting = false;
                failure = null;
                if (storageId.equals(selectedStorageId)) {
                    pendingEvent = new PendingEvent(
                            ++eventId,
                            EventType.SHARE_FILE,
                            storageId,
                            destination.getName(),
                            ready);
                }
                notifyObserver();
            });
        } catch (Exception error) {
            if (pending != null) {
                pending.delete();
            }
            postExportFailure(generation, error);
        }
    }

    private PhoneActivityDataSource.ActivityRecord loadRecordForExport(String storageId)
            throws IOException {
        PhoneActivityDataSource.ActivityRecord current = activity;
        if (current != null && storageId.equals(current.getSummary().getStorageId())) {
            return current;
        }
        return dataSource.loadActivity(storageId);
    }

    private void postExportFailure(long generation, Throwable error) {
        mainHandler.post(() -> {
            if (generation != exportGeneration) {
                return;
            }
            exporting = false;
            failure = error;
            notice = Notice.NONE;
            notifyObserver();
        });
    }

    private static String exportFileName(
            PhoneActivityDataSource.ActivityRecord record) {
        Long start = record.getSummary().getStartTimeUtcMillis();
        long timestamp = start == null ? 0L : start;
        SimpleDateFormat format =
                new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return "SportLogger-" + format.format(new Date(timestamp)) + ".tcx";
    }

    private static void deleteOtherSharedFiles(
            File sharedDirectory,
            File retained) {
        File[] files = sharedDirectory.listFiles(
                file -> file.isFile()
                        && file.getName().endsWith(".tcx")
                        && !file.equals(retained));
        if (files == null) {
            return;
        }
        for (File file : files) {
            file.delete();
        }
    }

    private void notifyObserver() {
        Observer current = observer;
        if (current == null) {
            return;
        }
        current.onStateChanged(new State(
                screen,
                history,
                activity,
                selectedStorageId,
                historyLoading,
                detailLoading,
                exporting,
                failure,
                notice));
        if (pendingEvent != null) {
            current.onEvent(pendingEvent);
        }
    }

    private void requireInitialized() {
        if (dataSource == null) {
            throw new IllegalStateException("PhoneActivityViewModel is not initialized");
        }
    }

    @Override
    protected void onCleared() {
        worker.shutdownNow();
        super.onCleared();
    }
}
