package com.long2know.sportlogger.recording;

import com.long2know.utilities.models.SportActivity;

public interface RecordingExporter {
    interface Callback {
        void onSuccess();

        void onFailure(Throwable error);
    }

    void export(SportActivity activity, Callback callback);
}
