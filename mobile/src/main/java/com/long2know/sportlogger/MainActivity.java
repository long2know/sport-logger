package com.long2know.sportlogger;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.lifecycle.ViewModelProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.long2know.sportlogger.history.PhoneActivityDataSource;
import com.long2know.utilities.export.tcx.TcxExportException;
import com.long2know.utilities.export.tcx.TcxWriter;

public final class MainActivity extends AppCompatActivity
        implements PhoneActivityViewModel.Observer {
    private static final int REQUEST_CREATE_TCX = 9001;
    private static final String STATE_SELECTED_ACTIVITY = "selectedActivity";

    private final BroadcastReceiver activityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            viewModel.loadHistory();
        }
    };
    private final TcxWriter tcxWriter = new TcxWriter();

    private PhoneActivityViewModel viewModel;
    private PhoneActivityViewModel.State currentState;
    private View historyPanel;
    private ProgressBar historyProgress;
    private TextView historyStatus;
    private TextView historyEmpty;
    private ListView activityList;
    private ActivityListAdapter activityAdapter;
    private View detailPanel;
    private ProgressBar detailProgress;
    private View detailContent;
    private TextView detailTitle;
    private TextView detailTransferState;
    private TextView detailStart;
    private TextView detailEnd;
    private TextView detailDuration;
    private TextView detailDistance;
    private TextView detailPointCount;
    private TextView detailRoute;
    private TextView detailHeartRate;
    private TextView detailDescriptionLabel;
    private TextView detailDescription;
    private TextView exportStatus;
    private ProgressBar exportProgress;
    private Button saveButton;
    private Button shareButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();

        viewModel = new ViewModelProvider(this).get(PhoneActivityViewModel.class);
        viewModel.initialize(this);

        String restoredSelection = savedInstanceState == null
                ? null
                : savedInstanceState.getString(STATE_SELECTED_ACTIVITY);
        if (viewModel.getSelectedStorageId() == null && restoredSelection != null) {
            viewModel.selectActivity(restoredSelection);
        } else if (viewModel.getSelectedStorageId() == null) {
            viewModel.loadHistory();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        viewModel.attach(this);
        LocalBroadcastManager.getInstance(this).registerReceiver(
                activityReceiver,
                new IntentFilter(ListenerService.ACTION_ACTIVITY_RECEIVED));
    }

    @Override
    protected void onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(activityReceiver);
        viewModel.detach(this);
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        String selectedStorageId = currentState != null
                && currentState.getScreen() == PhoneActivityViewModel.Screen.DETAIL
                ? viewModel.getSelectedStorageId()
                : null;
        if (selectedStorageId != null) {
            outState.putString(STATE_SELECTED_ACTIVITY, selectedStorageId);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        if (currentState != null
                && currentState.getScreen() == PhoneActivityViewModel.Screen.DETAIL) {
            viewModel.showHistory();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CREATE_TCX) {
            return;
        }
        Uri destination = data == null ? null : data.getData();
        if (resultCode == Activity.RESULT_OK && destination != null) {
            viewModel.saveToUri(destination);
        } else {
            viewModel.saveCancelled();
        }
    }

    @Override
    public void onStateChanged(PhoneActivityViewModel.State state) {
        currentState = state;
        boolean showHistory =
                state.getScreen() == PhoneActivityViewModel.Screen.HISTORY;
        historyPanel.setVisibility(showHistory ? View.VISIBLE : View.GONE);
        detailPanel.setVisibility(showHistory ? View.GONE : View.VISIBLE);
        if (showHistory) {
            renderHistory(state);
        } else {
            renderDetail(state);
        }
    }

    @Override
    public void onEvent(PhoneActivityViewModel.PendingEvent event) {
        viewModel.consumeEvent(event.getId());
        if (event.getType() == PhoneActivityViewModel.EventType.CREATE_DOCUMENT) {
            launchCreateDocument(event.getSuggestedFileName());
        } else if (event.getType() == PhoneActivityViewModel.EventType.SHARE_FILE) {
            launchShare(event);
        }
    }

    private void bindViews() {
        historyPanel = findViewById(R.id.history_panel);
        historyProgress = findViewById(R.id.history_progress);
        historyStatus = findViewById(R.id.history_status);
        historyEmpty = findViewById(R.id.history_empty);
        activityList = findViewById(R.id.activity_list);
        activityAdapter = new ActivityListAdapter(getLayoutInflater());
        activityList.setAdapter(activityAdapter);
        activityList.setOnItemClickListener(
                (parent, view, position, id) ->
                        viewModel.selectActivity(
                                activityAdapter.getItem(position).getStorageId()));
        findViewById(R.id.refresh_button).setOnClickListener(view -> viewModel.loadHistory());

        detailPanel = findViewById(R.id.detail_panel);
        detailProgress = findViewById(R.id.detail_progress);
        detailContent = findViewById(R.id.detail_content);
        detailTitle = findViewById(R.id.detail_title);
        detailTransferState = findViewById(R.id.detail_transfer_state);
        detailStart = findViewById(R.id.detail_start);
        detailEnd = findViewById(R.id.detail_end);
        detailDuration = findViewById(R.id.detail_duration);
        detailDistance = findViewById(R.id.detail_distance);
        detailPointCount = findViewById(R.id.detail_point_count);
        detailRoute = findViewById(R.id.detail_route);
        detailHeartRate = findViewById(R.id.detail_heart_rate);
        detailDescriptionLabel = findViewById(R.id.detail_description_label);
        detailDescription = findViewById(R.id.detail_description);
        exportStatus = findViewById(R.id.export_status);
        exportProgress = findViewById(R.id.export_progress);
        saveButton = findViewById(R.id.save_tcx_button);
        shareButton = findViewById(R.id.share_tcx_button);
        findViewById(R.id.detail_back_button).setOnClickListener(view -> viewModel.showHistory());
        saveButton.setOnClickListener(view -> viewModel.requestSave());
        shareButton.setOnClickListener(view -> viewModel.requestShare());
    }

    private void renderHistory(PhoneActivityViewModel.State state) {
        PhoneActivityDataSource.HistoryResult result = state.getHistory();
        historyProgress.setVisibility(state.isHistoryLoading() ? View.VISIBLE : View.GONE);
        if (result != null) {
            activityAdapter.setActivities(result.getActivities());
        }

        String status = null;
        if (state.getFailure() != null) {
            status = getString(R.string.history_load_error);
        } else if (result != null && result.getUnavailableCount() > 0) {
            status = getResources().getQuantityString(
                    R.plurals.history_partial_unavailable,
                    result.getUnavailableCount(),
                    result.getUnavailableCount());
        } else if (result != null && result.isTruncated()) {
            status = getResources().getQuantityString(
                    R.plurals.history_truncated,
                    result.getTotalStoredCount(),
                    result.getActivities().size(),
                    result.getTotalStoredCount());
        } else if (state.isHistoryLoading()) {
            status = getString(R.string.history_loading);
        } else if (result != null && !result.getActivities().isEmpty()) {
            status = getResources().getQuantityString(
                    R.plurals.history_activity_count,
                    result.getActivities().size(),
                    result.getActivities().size());
        }
        setOptionalText(historyStatus, status);

        boolean empty = !state.isHistoryLoading()
                && result != null
                && result.getActivities().isEmpty();
        historyEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty && result.getUnavailableCount() > 0) {
            historyEmpty.setText(R.string.history_no_readable_activities);
        } else {
            historyEmpty.setText(R.string.history_empty);
        }
        activityList.setVisibility(
                result != null && !result.getActivities().isEmpty()
                        ? View.VISIBLE
                        : View.GONE);
    }

    private void renderDetail(PhoneActivityViewModel.State state) {
        PhoneActivityDataSource.ActivityRecord record = state.getActivity();
        detailProgress.setVisibility(state.isDetailLoading() ? View.VISIBLE : View.GONE);
        detailContent.setVisibility(record == null ? View.GONE : View.VISIBLE);
        if (record != null) {
            PhoneActivityDataSource.ActivityListItem item = record.getSummary();
            detailTitle.setText(ActivityTextFormatter.title(this, item));
            detailTransferState.setText(ActivityTextFormatter.transferState(this, item));
            detailStart.setText(ActivityTextFormatter.dateTime(this, item.getStartTimeUtcMillis()));
            detailEnd.setText(ActivityTextFormatter.dateTime(this, item.getEndTimeUtcMillis()));
            detailDuration.setText(ActivityTextFormatter.duration(this, item.getDurationSeconds()));
            detailDistance.setText(ActivityTextFormatter.distance(this, item.getDistanceMeters()));
            detailPointCount.setText(getResources().getQuantityString(
                    R.plurals.track_point_count,
                    item.getTrackPointCount(),
                    item.getTrackPointCount()));
            detailRoute.setText(item.getRoutePointCount() == 0
                    ? getString(R.string.route_unavailable)
                    : getResources().getQuantityString(
                    R.plurals.route_point_count,
                    item.getRoutePointCount(),
                    item.getRoutePointCount()));
            detailHeartRate.setText(item.getHeartRatePointCount() == 0
                    ? getString(R.string.heart_rate_unavailable)
                    : getResources().getQuantityString(
                    R.plurals.heart_rate_point_count,
                    item.getHeartRatePointCount(),
                    item.getHeartRatePointCount()));
            String description = record.getDescription();
            boolean hasDescription = description != null;
            detailDescriptionLabel.setVisibility(
                    hasDescription ? View.VISIBLE : View.GONE);
            detailDescription.setVisibility(
                    hasDescription ? View.VISIBLE : View.GONE);
            detailDescription.setText(description);
        }

        exportProgress.setVisibility(state.isExporting() ? View.VISIBLE : View.GONE);
        boolean actionsEnabled = record != null
                && !state.isDetailLoading()
                && !state.isExporting();
        saveButton.setEnabled(actionsEnabled);
        shareButton.setEnabled(actionsEnabled);
        setOptionalText(exportStatus, detailStatus(state));
    }

    private String detailStatus(PhoneActivityViewModel.State state) {
        if (state.isExporting()) {
            return getString(R.string.export_in_progress);
        }
        switch (state.getNotice()) {
            case EXPORT_SAVED:
                return getString(R.string.export_saved);
            case EXPORT_CANCELLED:
                return getString(R.string.export_cancelled);
            case EXPORT_FAILED_DESTINATION_REMOVED:
                return getString(R.string.export_failed_removed);
            case EXPORT_FAILED_PARTIAL_DESTINATION:
                return getString(R.string.export_failed_partial);
            case SHARE_LAUNCH_FAILED:
                return getString(R.string.share_launch_failed);
            case NONE:
            default:
                break;
        }
        Throwable failure = state.getFailure();
        if (failure instanceof TcxExportException) {
            TcxExportException exportFailure = (TcxExportException) failure;
            switch (exportFailure.getCode()) {
                case CANCELLED:
                    return getString(R.string.export_cancelled);
                case MISSING_START_TIME:
                case MISSING_DURATION:
                case MISSING_DISTANCE:
                    return getString(R.string.export_required_data_missing);
                default:
                    return getString(R.string.export_invalid_data);
            }
        }
        if (failure != null) {
            return state.getActivity() == null
                    ? getString(R.string.detail_load_error)
                    : getString(R.string.export_failed);
        }
        if (state.getActivity() != null) {
            try {
                if (tcxWriter.assess(state.getActivity().getExportSource())
                        .hasFormatPlaceholders()) {
                    return getString(R.string.export_format_placeholder_warning);
                }
            } catch (TcxExportException error) {
                return getString(R.string.export_required_data_missing);
            }
        }
        return getString(R.string.export_privacy_note);
    }

    private void launchCreateDocument(String suggestedFileName) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(TcxWriter.MEDIA_TYPE)
                .putExtra(Intent.EXTRA_TITLE, suggestedFileName);
        try {
            startActivityForResult(intent, REQUEST_CREATE_TCX);
        } catch (ActivityNotFoundException error) {
            viewModel.shareLaunchFailed(error);
        }
    }

    private void launchShare(PhoneActivityViewModel.PendingEvent event) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    event.getSharedFile());
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType(TcxWriter.MEDIA_TYPE)
                    .putExtra(Intent.EXTRA_STREAM, uri);
            send.setClipData(ClipData.newUri(
                    getContentResolver(),
                    event.getSuggestedFileName(),
                    uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, getString(R.string.share_tcx_chooser)));
        } catch (RuntimeException error) {
            viewModel.shareLaunchFailed(error);
        }
    }

    private static void setOptionalText(TextView view, String value) {
        boolean visible = value != null && !value.isEmpty();
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
        view.setText(value);
    }
}
