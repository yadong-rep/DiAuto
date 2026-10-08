package com.hongqi.flighting.display;

import java.util.List;
import java.util.Objects;

public final class MirrorDisplayLifecycle {
    public enum State {
        IDLE,
        STARTING,
        RUNNING
    }

    private final InstrumentDisplayPolicy displayPolicy;
    private State state = State.IDLE;
    private int activeToken;
    private int targetDisplayId = -1;
    private boolean listenerRegistered;

    public MirrorDisplayLifecycle(InstrumentDisplayPolicy displayPolicy) {
        this.displayPolicy = Objects.requireNonNull(displayPolicy);
    }

    public boolean begin(int token) {
        if (state != State.IDLE) {
            return false;
        }
        activeToken = token;
        state = State.STARTING;
        return true;
    }

    public InstrumentDisplaySelection resolveStart(
        int token,
        List<InstrumentDisplayMetadata> displays
    ) {
        if (!isStarting(token)) {
            return InstrumentDisplaySelection.absent();
        }
        InstrumentDisplaySelection selection = displayPolicy.select(displays);
        if (selection.getOutcome() == InstrumentDisplaySelection.Outcome.FOUND) {
            targetDisplayId = selection.getDisplay().getDisplayId();
            return selection;
        }
        stop(token);
        return selection;
    }

    public boolean markRunning(int token) {
        if (!isStarting(token) || targetDisplayId < 0) {
            return false;
        }
        state = State.RUNNING;
        return true;
    }

    public boolean listenerRegistered(int token) {
        if (!isActive(token) || listenerRegistered) {
            return false;
        }
        listenerRegistered = true;
        return true;
    }

    public boolean listenerRegistrationFailed(int token) {
        if (!isActive(token)) {
            return false;
        }
        listenerRegistered = false;
        return stop(token);
    }

    public boolean detachListener(int token) {
        if (token != activeToken || !listenerRegistered) {
            return false;
        }
        listenerRegistered = false;
        return true;
    }

    public boolean destroy(int token) {
        detachListener(token);
        return stop(token);
    }

    public boolean listenerCallbackFailed(int token) {
        return destroy(token);
    }

    public boolean onDisplayEvent(int token, List<InstrumentDisplayMetadata> displays) {
        if (!isActive(token)) {
            return false;
        }
        InstrumentDisplaySelection selection = displayPolicy.select(displays);
        if (selection.getOutcome() == InstrumentDisplaySelection.Outcome.FOUND
            && selection.getDisplay().getDisplayId() == targetDisplayId) {
            return false;
        }
        stop(token);
        return true;
    }

    public boolean stop(int token) {
        if (!isActive(token)) {
            return false;
        }
        activeToken++;
        targetDisplayId = -1;
        listenerRegistered = false;
        state = State.IDLE;
        return true;
    }

    public boolean isActive(int token) {
        return token == activeToken && state != State.IDLE;
    }

    public boolean hasActiveListener() {
        return listenerRegistered;
    }

    public int getActiveToken() {
        return activeToken;
    }

    public int getTargetDisplayId() {
        return targetDisplayId;
    }

    public State getState() {
        return state;
    }

    private boolean isStarting(int token) {
        return token == activeToken && state == State.STARTING;
    }
}
