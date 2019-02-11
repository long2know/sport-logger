package com.long2know.sportlogger;

import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import static android.support.constraint.Constraints.TAG;
import static com.long2know.sportlogger.Config.handler;

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

    long _millisecondTime, _startTime, _timeBuff, _updateTime = 0L ;
    Handler _handler;
    int _seconds, _minutes, _hours, _milliSeconds ;

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
        return rootView;
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
        _startTime = SystemClock.uptimeMillis();
        _handler.postDelayed(runnable, 200);
    }

    public void resetTimer() {
        _millisecondTime = 0L ;
        _startTime = 0L ;
        _timeBuff = 0L ;
        _updateTime = 0L ;
        _seconds = 0 ;
        _minutes = 0 ;
        _milliSeconds = 0 ;
        _duration.setText("00:00:00");
    }

    public void pauseTimer() {
        _timeBuff += _millisecondTime;
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
            _millisecondTime = SystemClock.uptimeMillis() - _startTime;
            _updateTime = _timeBuff + _millisecondTime;
            _seconds = (int) (_updateTime / 1000);
            _hours = _seconds / 3600;
            _minutes = (_seconds / 60) % 60; // we want hh:mm:ss format .. not mm:ss:ms format
            _seconds = _seconds % 60;
            _milliSeconds = (int) (_updateTime % 1000);
//            _duration.setText("" + _minutes + ":"
//                    + String.format("%02d", _seconds) + ":"
//                    + String.format("%03d", _milliSeconds));

            _duration.setText("" + String.format("%02d", _hours) + ":"
                + String.format("%02d", _minutes) + ":"
                + String.format("%02d", _seconds));

            handler.postDelayed(this, 0);
        }

    };
}
