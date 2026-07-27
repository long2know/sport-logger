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
    private boolean _operationPending;

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(
                R.layout.fragment_recovery_activity, container, false);
        _status = rootView.findViewById(R.id.recording_recovery_status);
        refreshStatus();
        _retry = rootView.findViewById(
                R.id.btn_retry_recording_recovery);
        _retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                MainActivity activity = (MainActivity) getActivity();
                if (activity != null) {
                    activity.retryRecordingRecovery();
                }
            }
        });
        applyOperationPending();
        return rootView;
    }

    @Override
    public void onDestroyView() {
        _status = null;
        _retry = null;
        super.onDestroyView();
    }

    void setOperationPending(boolean operationPending) {
        _operationPending = operationPending;
        applyOperationPending();
    }

    private void applyOperationPending() {
        if (_retry != null) {
            _retry.setEnabled(!_operationPending);
            _retry.setAlpha(_operationPending ? 0.45F : 1.0F);
        }
    }

    void refreshStatus() {
        if (_status == null) {
            return;
        }
        _status.setText(
                SharedData.getInstance().RecoveryCurrentProcessOnly
                        ? R.string.recording_recovery_not_persisted
                        : R.string.recording_recovery_required);
    }
}
