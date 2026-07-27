package com.long2know.sportlogger;

final class TerminalExportCoordinator {
    private enum Phase {
        IDLE,
        SENDING,
        ACKNOWLEDGMENT_PENDING,
        ACKNOWLEDGMENT_IN_PROGRESS
    }

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
    private Phase _phase = Phase.IDLE;

    synchronized Attempt begin(long operationId) {
        if (operationId <= 0L || _phase != Phase.IDLE) {
            return null;
        }
        _inFlightOperationId = operationId;
        _inFlightAttemptId = ++_nextAttemptId;
        _phase = Phase.SENDING;
        return new Attempt(operationId, _inFlightAttemptId);
    }

    synchronized boolean failed(Attempt attempt) {
        if (!owns(attempt) || _phase != Phase.SENDING) {
            return false;
        }
        clear();
        return true;
    }

    synchronized boolean succeeded(Attempt attempt) {
        if (!owns(attempt) || _phase != Phase.SENDING) {
            return false;
        }
        _phase = Phase.ACKNOWLEDGMENT_PENDING;
        return true;
    }

    synchronized boolean acknowledgmentStarted(long operationId) {
        if (_inFlightOperationId != operationId
                || _phase != Phase.ACKNOWLEDGMENT_PENDING) {
            return false;
        }
        _phase = Phase.ACKNOWLEDGMENT_IN_PROGRESS;
        return true;
    }

    synchronized boolean acknowledgmentStartFailed(long operationId) {
        if (_inFlightOperationId != operationId
                || _phase != Phase.ACKNOWLEDGMENT_IN_PROGRESS) {
            return false;
        }
        _phase = Phase.ACKNOWLEDGMENT_PENDING;
        return true;
    }

    synchronized boolean acknowledgmentRetryRequired(long operationId) {
        if (_inFlightOperationId != operationId
                || (_phase != Phase.ACKNOWLEDGMENT_PENDING
                        && _phase != Phase.ACKNOWLEDGMENT_IN_PROGRESS)) {
            return false;
        }
        _phase = Phase.ACKNOWLEDGMENT_PENDING;
        return true;
    }

    synchronized boolean acknowledgmentFinished(
            long operationId, boolean acknowledged) {
        if (_inFlightOperationId != operationId
                || (_phase != Phase.ACKNOWLEDGMENT_PENDING
                        && _phase != Phase.ACKNOWLEDGMENT_IN_PROGRESS)) {
            return false;
        }
        if (acknowledged) {
            clear();
        } else {
            _phase = Phase.ACKNOWLEDGMENT_PENDING;
        }
        return true;
    }

    synchronized boolean abandonAcknowledgment(long operationId) {
        if (_inFlightOperationId != operationId
                || (_phase != Phase.ACKNOWLEDGMENT_PENDING
                        && _phase != Phase.ACKNOWLEDGMENT_IN_PROGRESS)) {
            return false;
        }
        clear();
        return true;
    }

    synchronized long acknowledgmentOperationId() {
        return _phase == Phase.ACKNOWLEDGMENT_PENDING
                        || _phase == Phase.ACKNOWLEDGMENT_IN_PROGRESS
                ? _inFlightOperationId
                : 0L;
    }

    synchronized boolean isAcknowledgmentInProgress() {
        return _phase == Phase.ACKNOWLEDGMENT_IN_PROGRESS;
    }

    synchronized boolean isInFlight() {
        return _phase != Phase.IDLE;
    }

    private boolean owns(Attempt attempt) {
        return attempt != null
                && attempt._operationId == _inFlightOperationId
                && attempt._attemptId == _inFlightAttemptId;
    }

    private void clear() {
        _inFlightOperationId = 0L;
        _inFlightAttemptId = 0L;
        _phase = Phase.IDLE;
    }
}
