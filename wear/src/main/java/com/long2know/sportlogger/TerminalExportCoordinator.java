package com.long2know.sportlogger;

final class TerminalExportCoordinator {
    static final class Attempt {
        private final long _operationId;
        private final long _attemptId;

        private Attempt(long operationId, long attemptId) {
            _operationId = operationId;
            _attemptId = attemptId;
        }

        long getOperationId() {
            return _operationId;
        }
    }

    private long _nextAttemptId;
    private long _inFlightOperationId;
    private long _inFlightAttemptId;
    private boolean _awaitingAcknowledgment;

    synchronized Attempt begin(long operationId) {
        if (operationId <= 0L || _inFlightOperationId != 0L) {
            return null;
        }
        _inFlightOperationId = operationId;
        _inFlightAttemptId = ++_nextAttemptId;
        _awaitingAcknowledgment = false;
        return new Attempt(operationId, _inFlightAttemptId);
    }

    synchronized boolean failed(Attempt attempt) {
        if (!owns(attempt) || _awaitingAcknowledgment) {
            return false;
        }
        clear();
        return true;
    }

    synchronized boolean acknowledgmentStartFailed(Attempt attempt) {
        if (!owns(attempt) || !_awaitingAcknowledgment) {
            return false;
        }
        clear();
        return true;
    }

    synchronized boolean succeeded(Attempt attempt) {
        if (!owns(attempt) || _awaitingAcknowledgment) {
            return false;
        }
        _awaitingAcknowledgment = true;
        return true;
    }

    synchronized boolean acknowledgmentFinished(long operationId) {
        if (_inFlightOperationId != operationId
                || !_awaitingAcknowledgment) {
            return false;
        }
        clear();
        return true;
    }

    synchronized boolean isInFlight() {
        return _inFlightOperationId != 0L;
    }

    private boolean owns(Attempt attempt) {
        return attempt != null
                && attempt._operationId == _inFlightOperationId
                && attempt._attemptId == _inFlightAttemptId;
    }

    private void clear() {
        _inFlightOperationId = 0L;
        _inFlightAttemptId = 0L;
        _awaitingAcknowledgment = false;
    }
}
