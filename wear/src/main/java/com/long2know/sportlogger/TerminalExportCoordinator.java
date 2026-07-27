package com.long2know.sportlogger;

final class TerminalExportCoordinator {
    interface Acknowledger {
        boolean acknowledge(long operationId);
    }

    private long _inFlightOperationId;

    synchronized boolean begin(long operationId) {
        if (operationId <= 0L || _inFlightOperationId != 0L) {
            return false;
        }
        _inFlightOperationId = operationId;
        return true;
    }

    synchronized void failed(long operationId) {
        if (_inFlightOperationId == operationId) {
            _inFlightOperationId = 0L;
        }
    }

    boolean succeeded(long operationId, Acknowledger acknowledger) {
        synchronized (this) {
            if (_inFlightOperationId != operationId) {
                return false;
            }
        }
        boolean acknowledged = false;
        try {
            acknowledged = acknowledger.acknowledge(operationId);
        } catch (RuntimeException exception) {
            acknowledged = false;
        } finally {
            synchronized (this) {
                if (_inFlightOperationId == operationId) {
                    _inFlightOperationId = 0L;
                }
            }
        }
        return acknowledged;
    }

    synchronized boolean isInFlight() {
        return _inFlightOperationId != 0L;
    }

    synchronized void abandon() {
        _inFlightOperationId = 0L;
    }
}
