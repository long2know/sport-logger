package com.long2know.sportlogger;

import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.long2know.utilities.models.Config;
import com.long2know.utilities.models.LocationData;
import com.long2know.utilities.models.SharedData;

/**
 * Fragment that appears in the "content_frame", just shows the currently selected planet.
 */
public class SensorFragment extends Fragment {
    private static final String TAG = "SensorFragment";

    private TextView _heartRate;
    private TextView _latitude;
    private TextView _longitude;
    private TextView _totalDistance;
    private TextView _steps;
    private TextView _duration;
    private final Handler _handler;
    private final CancellableCallbackLoop _timerLoop;

    public SensorFragment() {
        // Empty constructor required for fragment subclasses
        _handler = new Handler(Looper.getMainLooper());
        _timerLoop = new CancellableCallbackLoop(
                new CancellableCallbackLoop.Scheduler() {
                    @Override
                    public void postDelayed(Runnable callback, long delayMillis) {
                        _handler.postDelayed(callback, delayMillis);
                    }

                    @Override
                    public void removeCallbacks(Runnable callback) {
                        _handler.removeCallbacks(callback);
                    }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        updateDuration();
                    }
                },
                200L);
    }

    @Override
    public void onDestroy () {
        pauseTimer();
        super.onDestroy ();
    }

    @Override
    public void onPause() {
        pauseTimer();
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (SharedData.getInstance().IsRecording
                && !SharedData.getInstance().IsPaused) {
            startTImer();
        }
    }

    @Override
    public void onDestroyView() {
        pauseTimer();
        _heartRate = null;
        _latitude = null;
        _longitude = null;
        _totalDistance = null;
        _steps = null;
        _duration = null;
        super.onDestroyView();
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
        updateDuration();
        updateHeartRate(SharedData.getInstance().getData().HeartRate);
        updateLocation(SharedData.getInstance().getData());
        updateSteps(SharedData.getInstance().getData().Steps);
        if (SharedData.getInstance().IsRecording
                && !SharedData.getInstance().IsPaused) {
            startTImer();
        }
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
        if (!isAdded() || getView() == null) {
            return;
        }
        _timerLoop.start();
    }

    public void resetTimer() {
        if (_duration != null) {
            _duration.setText("00:00:00");
        }
    }

    public void pauseTimer() {
        _timerLoop.stop();
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

    private void updateDuration() {
        SharedData shared = SharedData.getInstance();
        if (!shared.IsRecording || shared.IsPaused || !isAdded() || _duration == null) {
            _timerLoop.stop();
            return;
        }
        _duration.setText(shared.Duration);
    }
}
