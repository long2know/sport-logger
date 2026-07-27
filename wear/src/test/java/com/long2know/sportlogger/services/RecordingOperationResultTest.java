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

    @Test
    public void failedRecoveryPersistenceExposesCurrentProcessOnlyLimit() {
        RecordingOperationResult result = RecordingOperationResult.recovery(
                RecordingOperationResult.Status.RECOVERY_PERSISTENCE_FAILED,
                24,
                RecordingOperationResult.RecoveryAction.SHOW_RECOVERY_RETRY,
                RecordingOperationResult.RecoveryRetention.CURRENT_PROCESS_ONLY);

        assertEquals(
                RecordingOperationResult.RecoveryRetention.CURRENT_PROCESS_ONLY,
                result.getRecoveryRetention());
    }

    @Test
    public void acceptedAsyncRequestCarriesOperationAndToken() {
        RecordingOperationResult result = RecordingOperationResult.accepted(
                RecordingOperationResult.Operation.STOP, 19L, 24);

        assertEquals(RecordingOperationResult.Status.ACCEPTED, result.getStatus());
        assertEquals(
                RecordingOperationResult.Operation.STOP,
                result.getOperation());
        assertEquals(19L, result.getOperationToken());
        assertEquals(24, result.getActivityId());
    }

    @Test
    public void terminalStopCarriesDurableCompletionIdentity() {
        RecordingTerminalCompletion completion =
                new RecordingTerminalCompletion(
                        29L,
                        31,
                        37L,
                        RecordingTerminalCompletion.Type.STOP_EXPORT,
                        RecordingTerminalCompletion.Result.SUCCESS);

        RecordingOperationResult result =
                RecordingOperationResult.terminalStopSuccess(completion, 41L);

        assertEquals(RecordingOperationResult.Status.SUCCESS, result.getStatus());
        assertEquals(
                RecordingOperationResult.Operation.STOP,
                result.getOperation());
        assertEquals(41L, result.getOperationToken());
        assertEquals(29L, result.getTerminalCompletionId());
        assertEquals(37L, result.getWriterGeneration());
        assertEquals(31, result.getActivityId());
    }
}
