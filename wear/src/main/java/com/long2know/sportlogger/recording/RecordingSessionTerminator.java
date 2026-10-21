package com.long2know.sportlogger.recording;

public interface RecordingSessionTerminator {
    RecordingSessionTerminator NO_OP =
            (activityId, preserveFinalData, finalSnapshot, callback) ->
                    callback.onComplete();

    interface Callback {
        void onComplete();

        void onFailure(Throwable error);
    }

    void endSession(
            int activityId,
            boolean preserveFinalData,
            RecordingSnapshot finalSnapshot,
            Callback callback);
}
