package com.long2know.sportlogger;

import com.long2know.sportlogger.services.RecordingOperationResult;

final class RecordingUiState {
    enum Screen {
        START,
        RECORDING,
        PAUSED,
        RECOVERY
    }

    interface Renderer {
        void render(Screen screen, RecordingOperationResult failure);
    }

    private Screen _screen = Screen.START;
    private RecordingOperationResult _failure;
    private boolean _pending = true;

    synchronized void requestStatus(Screen screen) {
        _screen = screen;
        _pending = true;
    }

    synchronized void requestFailure(
            Screen screen, RecordingOperationResult failure) {
        _screen = screen;
        _failure = failure;
        _pending = true;
    }

    synchronized boolean renderIfSafe(boolean safe, Renderer renderer) {
        if (!safe || !_pending) {
            return false;
        }
        RecordingOperationResult failure = _failure;
        _failure = null;
        _pending = false;
        renderer.render(_screen, failure);
        return true;
    }

    synchronized boolean hasPendingRender() {
        return _pending;
    }
}
