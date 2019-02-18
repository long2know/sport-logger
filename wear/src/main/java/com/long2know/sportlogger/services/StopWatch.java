package com.long2know.sportlogger.services;

import android.os.Handler;
import android.os.SystemClock;

import com.long2know.utilities.models.SharedData;

public class StopWatch {
    long _millisecondTime, _startTime, _timeBuff, _updateTime = 0L ;
    int _seconds, _minutes, _hours, _milliSeconds ;
    String _duration;
    Handler _handler;

    public StopWatch() {
        // Moves the current Thread into the background
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        _handler = new Handler();
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
        _duration = "00:00:00";
    }

    public void pauseTimer() {
        _timeBuff += _millisecondTime;
        _handler.removeCallbacks(runnable);
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

//            _duration.setText("" + String.format("%02d", _hours) + ":"
//                    + String.format("%02d", _minutes) + ":"
//                    + String.format("%02d", _seconds));

            _duration = String.format("%02d", _hours) + ":"
                    + String.format("%02d", _minutes) + ":"
                    + String.format("%02d", _seconds);

            SharedData.getInstance().Duration = _duration;
            _handler.postDelayed(this, 0);
        }

    };
}
