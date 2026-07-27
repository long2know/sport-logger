package com.long2know.sportlogger;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;

import androidx.fragment.app.Fragment;

public class RecoveryActivityFragment extends Fragment {
    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(
                R.layout.fragment_recovery_activity, container, false);
        ImageButton retry = rootView.findViewById(
                R.id.btn_retry_recording_recovery);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                MainActivity activity = (MainActivity) getActivity();
                if (activity != null) {
                    activity.retryRecordingRecovery();
                }
            }
        });
        return rootView;
    }
}
