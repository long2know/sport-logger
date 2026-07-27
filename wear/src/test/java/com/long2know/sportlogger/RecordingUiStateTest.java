package com.long2know.sportlogger;

import com.long2know.sportlogger.services.RecordingOperationResult;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecordingUiStateTest {
    @Test
    public void backgroundFailureRendersAfterFragmentStateBecomesSafe() {
        RecordingUiState state = new RecordingUiState();
        RecordingOperationResult failure = RecordingOperationResult.recovery(
                RecordingOperationResult.Status.WRITER_FAILED,
                42,
                RecordingOperationResult.RecoveryAction.SHOW_RECOVERY_RETRY);
        AtomicInteger renders = new AtomicInteger();
        AtomicReference<RecordingUiState.Screen> renderedScreen =
                new AtomicReference<>();
        AtomicReference<RecordingOperationResult> renderedFailure =
                new AtomicReference<>();

        state.requestStatus(RecordingUiState.Screen.RECORDING);
        assertTrue(state.renderIfSafe(true, (screen, result) -> {
            renders.incrementAndGet();
            renderedScreen.set(screen);
            renderedFailure.set(result);
        }));
        assertEquals(RecordingUiState.Screen.RECORDING, renderedScreen.get());

        state.requestFailure(RecordingUiState.Screen.RECOVERY, failure);
        assertFalse(state.renderIfSafe(false, (screen, result) -> {
            renders.incrementAndGet();
            renderedScreen.set(screen);
            renderedFailure.set(result);
        }));
        assertTrue(state.hasPendingRender());
        assertEquals(1, renders.get());

        state.requestStatus(RecordingUiState.Screen.RECOVERY);
        assertTrue(state.renderIfSafe(true, (screen, result) -> {
            renders.incrementAndGet();
            renderedScreen.set(screen);
            renderedFailure.set(result);
        }));

        assertEquals(2, renders.get());
        assertEquals(RecordingUiState.Screen.RECOVERY, renderedScreen.get());
        assertEquals(failure, renderedFailure.get());
        assertFalse(state.hasPendingRender());
    }
}
