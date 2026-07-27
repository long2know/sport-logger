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
        FENCE_FAILED,
        SHUTTING_DOWN
    }

    enum Operation {
        START,
        PAUSE,
        RESUME,
        STOP,
        DISCARD,
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
                        || _state == State.PAUSED
                        || _state == State.FENCE_FAILED) {
                    return Decision.NO_OP;
                }
                return _state == State.RECORDING
                        ? accept(operation, State.PAUSING)
                        : Decision.INVALID;
            case RESUME:
                if (_state == State.RESUMING || _state == State.RECORDING) {
                    return Decision.NO_OP;
                }
                return _state == State.PAUSED || _state == State.FENCE_FAILED
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
        _state = fenceFailed ? State.FENCE_FAILED : _rollbackState;
        _activeOperation = null;
        return true;
    }

    synchronized State getState() {
        return _state;
    }

    synchronized void failGeneration() {
        _activeOperation = null;
        _rollbackState = _state;
        _state = State.SHUTTING_DOWN;
    }

    private Decision accept(Operation operation, State transitionState) {
        _rollbackState = _state;
        _activeOperation = operation;
        _state = transitionState;
        return Decision.ACCEPTED;
    }

    private static boolean isRetainedActivityState(State state) {
        return state == State.RECORDING
                || state == State.PAUSED
                || state == State.FENCE_FAILED;
    }
}
