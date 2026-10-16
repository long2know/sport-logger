package com.long2know.sportlogger;

import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.long2know.utilities.models.Config;
import com.long2know.utilities.models.LocationData;
import com.long2know.utilities.models.SharedData;

public class SensorFragment extends Fragment {
    private static final String TAG = "SensorFragment";

    private TextView _heartRate;
    private TextView _latitude;
    private TextView _longitude;
    private TextView _totalDistance;
    private TextView _steps;
    private TextView _duration;

    public SensorFragment() {
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

        if (SharedData.getInstance().isRecording()) {
            refresh();
        }

        return rootView;
    }

    public void refresh() {
        SharedData sharedData = SharedData.getInstance();
        LocationData data = sharedData.getData();
        if (_duration != null) {
            _duration.setText(sharedData.getDuration());
        }
        updateHeartRate(data.HeartRate);
        updateLocation(data);
        updateSteps(data.Steps);
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

    public void resetTimer() {
        if (_duration != null) {
            _duration.setText("00:00:00");
        }
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
}
