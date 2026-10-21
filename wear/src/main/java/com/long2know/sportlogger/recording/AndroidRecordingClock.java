package com.long2know.sportlogger.recording;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;

public final class AndroidRecordingClock implements RecordingController.Clock {
    private final Context context;

    public AndroidRecordingClock(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    @Override
    public long elapsedRealtimeMillis() {
        return SystemClock.elapsedRealtime();
    }

    @Override
    public int bootCount() {
        try {
            return Settings.Global.getInt(
                    context.getContentResolver(),
                    Settings.Global.BOOT_COUNT,
                    -1);
        } catch (RuntimeException error) {
            return -1;
        }
    }
}
