package com.long2know.sportlogger;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.long2know.utilities.models.SharedData;

public class RecoveryActivityFragment extends Fragment {
    private TextView _status;
    private ImageButton _retry;
    private TextView _retryLabel;
    private View _terminalActions;
    private ImageButton _stop;
    private ImageButton _discard;
    private boolean _operationPending;
    private boolean _terminalExportPending;
    private boolean _terminalRetryEnabled;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(
                R.layout.fragment_recovery_activity, container, false);
        _status = rootView.findViewById(R.id.recording_recovery_status);
        _retry = rootView.findViewById(
                R.id.btn_retry_recording_recovery);
        _retryLabel = rootView.findViewById(
                R.id.recording_recovery_retry_label);
        _terminalActions = rootView.findViewById(
                R.id.recording_recovery_terminal_actions);
        _stop = rootView.findViewById(
                R.id.btn_stop_retained_activity);
        _discard = rootView.findViewById(
                R.id.btn_discard_retained_activity);
        _retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                MainActivity activity = (MainActivity) getActivity();
                if (activity != null) {
                    if (_terminalExportPending) {
                        activity.retryPendingTerminalExport();
                    } else {
                        activity.retryRecordingRecovery();
                    }
                }
            }
        });
        _stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                MainActivity activity = (MainActivity) getActivity();
                if (activity != null) {
                    activity.stopActivity();
                }
            }
        });
        _discard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                MainActivity activity = (MainActivity) getActivity();
                if (activity != null) {
                    activity.discardActivity();
                }
            }
        });
        refreshStatus();
        applyOperationPending();
        return rootView;
    }

    @Override
    public void onDestroyView() {
        _status = null;
        _retry = null;
        _retryLabel = null;
        _terminalActions = null;
        _stop = null;
        _discard = null;
        super.onDestroyView();
    }

    void setOperationPending(boolean operationPending) {
        _operationPending = operationPending;
        applyOperationPending();
    }

    private void applyOperationPending() {
        if (_retry != null) {
            boolean enabled = !_operationPending
                    && (!_terminalExportPending || _terminalRetryEnabled);
            _retry.setEnabled(enabled);
            _retry.setAlpha(enabled ? 1.0F : 0.45F);
        }
        boolean hasOwnedActivity =
                SharedData.getInstance().ActivityId > 0;
        boolean terminalActionEnabled =
                !_operationPending
                        && !_terminalExportPending
                        && hasOwnedActivity;
        setActionEnabled(_stop, terminalActionEnabled);
        setActionEnabled(_discard, terminalActionEnabled);
        if (_terminalActions != null) {
            _terminalActions.setVisibility(
                    !_terminalExportPending && hasOwnedActivity
                            ? View.VISIBLE
                            : View.GONE);
        }
    }

    void showRecordingRecovery() {
        _terminalExportPending = false;
        _terminalRetryEnabled = false;
        refreshStatus();
        applyOperationPending();
    }

    void showTerminalExportPending(boolean retryEnabled) {
        _terminalExportPending = true;
        _terminalRetryEnabled = retryEnabled;
        refreshStatus();
        applyOperationPending();
    }

    void refreshStatus() {
        if (_status == null) {
            return;
        }
        if (_terminalExportPending) {
            _status.setText(
                    _terminalRetryEnabled
                            ? R.string.terminal_export_retry_required
                            : R.string.terminal_export_in_progress);
            if (_retry != null) {
                _retry.setContentDescription(
                        getString(R.string.retry_terminal_export));
            }
            if (_retryLabel != null) {
                _retryLabel.setText(R.string.retry_terminal_export);
            }
        } else {
            SharedData shared = SharedData.getInstance();
            if (shared.ActivityId <= 0) {
                _status.setText(
                        R.string.recording_persistence_unavailable);
            } else {
                _status.setText(
                        shared.RecoveryCurrentProcessOnly
                                ? R.string.recording_recovery_not_persisted
                                : R.string.recording_recovery_required);
            }
            if (_retry != null) {
                _retry.setContentDescription(
                        getString(R.string.retry_recording_recovery));
            }
            if (_retryLabel != null) {
                _retryLabel.setText(R.string.retry_recording_recovery);
            }
        }
    }

    private static void setActionEnabled(
            ImageButton action, boolean enabled) {
        if (action != null) {
            action.setEnabled(enabled);
            action.setAlpha(enabled ? 1.0F : 0.45F);
        }
    }
}
