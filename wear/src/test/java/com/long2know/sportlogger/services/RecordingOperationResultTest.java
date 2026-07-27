package com.long2know.sportlogger.services;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RecordingOperationResultTest {
    @Test
    public void lifecycleFailureCarriesAnExplicitUiRecoveryAction() {
        RecordingOperationResult result = RecordingOperationResult.recovery(
                RecordingOperationResult.Status.WRITER_FAILED,
                14,
                RecordingOperationResult.RecoveryAction.SHOW_PAUSED_CONTROLS);

        assertEquals(14, result.getActivityId());
        assertEquals(
                RecordingOperationResult.RecoveryAction.SHOW_PAUSED_CONTROLS,
                result.getRecoveryAction());
    }
}
