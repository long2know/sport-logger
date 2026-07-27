package com.long2know.sportlogger.services;

final class RecordingStateMachine {
    enum State {
        IDLE,
        STARTING,
        RECORDING,
        PAUSING,
        PAUSED,
        RESUMING,
        STOPPING,
        DISCARDING,
        RECOVERING,
        RECOVERY_REQUIRED,
        SHUTTING_DOWN
    }

    enum Operation {
        START,
        PAUSE,
        RESUME,
        STOP,
        DISCARD,
        RECOVER,
        SHUTDOWN
    }

    enum Decision {
        ACCEPTED,
        NO_OP,
        INVALID
    }

    private State _state = State.IDLE;
    private State _rollbackState = State.IDLE;
    private Operation _activeOperation;

    synchronized Decision begin(Operation operation) {
        if (operation == Operation.SHUTDOWN) {
            if (_state == State.SHUTTING_DOWN) {
                return Decision.NO_OP;
            }
            _rollbackState = _state;
            _activeOperation = operation;
            _state = State.SHUTTING_DOWN;
            return Decision.ACCEPTED;
        }
        if (_state == State.SHUTTING_DOWN) {
            return Decision.INVALID;
        }

        switch (operation) {
            case START:
                if (_state == State.STARTING || _state == State.RECORDING) {
                    return Decision.NO_OP;
                }
                return _state == State.IDLE
                        ? accept(operation, State.STARTING)
                        : Decision.INVALID;
            case PAUSE:
                if (_state == State.PAUSING
                        || _state == State.PAUSED) {
                    return Decision.NO_OP;
                }
                return _state == State.RECORDING
                        ? accept(operation, State.PAUSING)
                        : Decision.INVALID;
            case RESUME:
                if (_state == State.RESUMING || _state == State.RECORDING) {
                    return Decision.NO_OP;
                }
                return _state == State.PAUSED
                        ? accept(operation, State.RESUMING)
                        : Decision.INVALID;
            case STOP:
                if (_state == State.STOPPING || _state == State.IDLE) {
                    return Decision.NO_OP;
                }
                return isRetainedActivityState(_state)
                        ? accept(operation, State.STOPPING)
                        : Decision.INVALID;
            case DISCARD:
                if (_state == State.DISCARDING || _state == State.IDLE) {
                    return Decision.NO_OP;
                }
                return isRetainedActivityState(_state)
                        ? accept(operation, State.DISCARDING)
                        : Decision.INVALID;
            case RECOVER:
                if (_state == State.RECOVERING) {
                    return Decision.NO_OP;
                }
                return _state == State.RECOVERY_REQUIRED
                        ? accept(operation, State.RECOVERING)
                        : Decision.INVALID;
            default:
                return Decision.INVALID;
        }
    }

    synchronized boolean completeSuccess(Operation operation) {
        if (_activeOperation != operation) {
            return false;
        }
        switch (operation) {
            case START:
            case RESUME:
                _state = State.RECORDING;
                break;
            case PAUSE:
            case RECOVER:
                _state = State.PAUSED;
                break;
            case STOP:
            case DISCARD:
                _state = State.IDLE;
                break;
            case SHUTDOWN:
                _state = State.SHUTTING_DOWN;
                break;
            default:
                return false;
        }
        _activeOperation = null;
        return true;
    }

    synchronized boolean completeFailure(Operation operation, boolean fenceFailed) {
        if (_activeOperation != operation) {
            return false;
        }
        _state = fenceFailed ? State.RECOVERY_REQUIRED : _rollbackState;
        _activeOperation = null;
        return true;
    }

    synchronized State getState() {
        return _state;
    }

    synchronized State getStableState() {
        switch (_state) {
            case STARTING:
            case PAUSING:
            case RESUMING:
            case STOPPING:
            case DISCARDING:
            case RECOVERING:
                return _rollbackState;
            default:
                return _state;
        }
    }

    synchronized void failGeneration() {
        _activeOperation = null;
        _rollbackState = _state;
        _state = State.RECOVERY_REQUIRED;
    }

    synchronized void restoreOwnedActivity() {
        _activeOperation = null;
        _rollbackState = State.RECOVERY_REQUIRED;
        _state = State.RECOVERY_REQUIRED;
    }

    synchronized void restorePaused() {
        _activeOperation = null;
        _rollbackState = State.PAUSED;
        _state = State.PAUSED;
    }

    synchronized void restoreIdle() {
        _activeOperation = null;
        _rollbackState = State.IDLE;
        _state = State.IDLE;
    }

    private Decision accept(Operation operation, State transitionState) {
        _rollbackState = _state;
        _activeOperation = operation;
        _state = transitionState;
        return Decision.ACCEPTED;
    }

    private static boolean isRetainedActivityState(State state) {
        return state == State.RECORDING
                || state == State.PAUSED;
    }
}
