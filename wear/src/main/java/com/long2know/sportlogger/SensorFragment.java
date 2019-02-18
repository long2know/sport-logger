package com.long2know.sportlogger;

import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.long2know.utilities.models.Config;
import com.long2know.utilities.models.LocationData;
import com.long2know.utilities.models.SharedData;

import static android.support.constraint.Constraints.TAG;
import static com.long2know.utilities.models.Config.handler;

/**
 * Fragment that appears in the "content_frame", just shows the currently selected planet.
 */
public class SensorFragment extends Fragment {
    private TextView _heartRate;
    private TextView _latitude;
    private TextView _longitude;
    private TextView _totalDistance;
    private TextView _steps;
    private TextView _duration;
    Handler _handler;

    public SensorFragment() {
        // Empty constructor required for fragment subclasses
        _handler = new Handler();
    }

    @Override
    public void onDestroy () {
        _handler.removeCallbacks(runnable);
        super.onDestroy ();
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_sensors, container, false);

        _heartRate = rootView.findViewById(R.id.hrm);
        _latitude = rootView.findViewById(R.id.latitude);
        _longitude = rootView.findViewById(R.id.longitude);
        _totalDistance = rootView.findViewById(R.id.totalDistance);
        _steps = rootView.findViewById(R.id.steps);
        _duration = rootView.findViewById(R.id.tvTimer);

        if (SharedData.getInstance().IsRecording) {
            refresh();
        }

        return rootView;
    }

    public void refresh() {
        _duration.setText(SharedData.getInstance().Duration);
        updateHeartRate(SharedData.getInstance().getData().HeartRate);
        updateLocation(SharedData.getInstance().getData());
        updateSteps(SharedData.getInstance().getData().Steps);
        startTImer();
    }

    public void updateHeartRate(float heartRate) {
        if (_heartRate != null) {
            _heartRate.setText(Float.toString(heartRate));
        }
    }

    public void updateLocation(LocationData data) {
        if (_latitude != null) {
            _latitude.setText(Config.SevenSigDigits.format(data.Latitude));
            _longitude.setText(Config.SevenSigDigits.format(data.Longitude));
            _totalDistance.setText(Config.TwoSigDigits.format(data.TotalDistance));
        }
    }

    public void updateSteps(int steps) {
        if (_steps != null) {
            _steps.setText(Integer.toString(steps));
        }
    }

    public void startTImer() {
        _handler.postDelayed(runnable, 200);
    }

    public void resetTimer() {
        if (_duration != null) {
            _duration.setText("00:00:00");
        }
    }

    public void pauseTimer() {
        _handler.removeCallbacks(runnable);
    }

    public void onEnterAmbientInFragment(Bundle ambientDetails) {
        Log.d(TAG, "SensorFragment.onEnterAmbient() " + ambientDetails);

        // Convert image to grayscale for ambient mode.
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0);

        ColorMatrixColorFilter filter = new ColorMatrixColorFilter(matrix);
    }

    /** Restores the UI to active (non-ambient) mode. */
    public void onExitAmbientInFragment() {
        Log.d(TAG, "SensorFragment.onExitAmbient()");
    }

    public Runnable runnable = new Runnable() {
        public void run() {
            _duration.setText(SharedData.getInstance().Duration);
            handler.postDelayed(this, 0);
        }

    };
}
