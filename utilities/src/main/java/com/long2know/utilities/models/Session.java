package com.long2know.utilities.models;

import android.app.Application;

public class Session extends Application {
    private static boolean _isBound;
    private static boolean _isStarted;

    public static void setBoundToService(boolean isBound)    {
        Session._isBound = isBound;
    }

    public static boolean isBoundToService()     {
        return _isBound;
    }

    public static void setStarted(boolean isBound)    {
        Session._isBound = _isStarted;
    }

    public static boolean isStarted()     {
        return _isStarted;
    }
}