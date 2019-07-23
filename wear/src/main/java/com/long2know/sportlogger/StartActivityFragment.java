package com.long2know.sportlogger;

import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import androidx.fragment.app.Fragment;
import static androidx.constraintlayout.widget.Constraints.TAG;

/**
 * Fragment that appears in the "content_frame", just shows the currently selected planet.
 */
public class StartActivityFragment extends Fragment {

    private ImageButton _start;

    public StartActivityFragment() {
        // Empty constructor required for fragment subclasses
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_start_activity, container, false);
        _start = rootView.findViewById(R.id.btn_start_activity);
        attachEventHandlers();
        return rootView;
    }

    private void attachEventHandlers() {
        _start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ((MainActivity) getActivity()).startNewActivity();
            }
        });
    }

    public void onEnterAmbientInFragment(Bundle ambientDetails) {
        Log.d(TAG, "StartAcivityFragment.onEnterAmbient() " + ambientDetails);

        // Convert image to grayscale for ambient mode.
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0);

        ColorMatrixColorFilter filter = new ColorMatrixColorFilter(matrix);
    }

    /** Restores the UI to active (non-ambient) mode. */
    public void onExitAmbientInFragment() {
        Log.d(TAG, "StartAcivityFragment.onExitAmbient()");
    }
}