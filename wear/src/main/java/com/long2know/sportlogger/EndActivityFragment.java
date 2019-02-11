package com.long2know.sportlogger;

import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;

import static android.support.constraint.Constraints.TAG;

/**
 * Fragment that appears in the "content_frame", just shows the currently selected planet.
 */
public class EndActivityFragment extends Fragment {

    private ImageButton _resume;
    private ImageButton _stop;
    private ImageButton _discard;

    public EndActivityFragment() {
        // Empty constructor required for fragment subclasses
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_end_activity, container, false);
        _resume = rootView.findViewById(R.id.btn_resume_activity);
        _stop = rootView.findViewById(R.id.btn_end_activity);
        _discard = rootView.findViewById(R.id.btn_discard_activity);
        attachEventHandlers();
        return rootView;
    }

    private void attachEventHandlers() {
        _resume.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ((MainActivity) getActivity()).resumeActivity();
            }
        });

        _stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ((MainActivity) getActivity()).stopActivity();
            }
        });

        _discard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ((MainActivity) getActivity()).discardActivity();
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