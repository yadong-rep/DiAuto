package com.hongqi.flighting.display;

public final class MirrorResourceLifecycle {
    public enum State {
        IDLE,
        STARTING,
        RUNNING,
        STOPPING
    }

    public static final class SurfaceDecision {
        private final boolean releaseSurface;
        private final boolean createVirtualDisplay;

        private SurfaceDecision(boolean releaseSurface, boolean createVirtualDisplay) {
            this.releaseSurface = releaseSurface;
            this.createVirtualDisplay = createVirtualDisplay;
        }

        public boolean shouldReleaseSurface() {
            return releaseSurface;
        }

        public boolean shouldCreateVirtualDisplay() {
            return createVirtualDisplay;
        }
    }

    public static final class ReleaseActions {
        private final int stopGeneration;
        private final boolean ownsStop;
        private final boolean releaseVirtualDisplay;
        private final boolean dismissPresentation;
        private final boolean unregisterProjectionCallback;
        private final boolean stopProjection;
        private final boolean releaseSurface;

        private ReleaseActions(
            int stopGeneration,
            boolean ownsStop,
            boolean releaseVirtualDisplay,
            boolean dismissPresentation,
            boolean unregisterProjectionCallback,
            boolean stopProjection,
            boolean releaseSurface
        ) {
            this.stopGeneration = stopGeneration;
            this.ownsStop = ownsStop;
            this.releaseVirtualDisplay = releaseVirtualDisplay;
            this.dismissPresentation = dismissPresentation;
            this.unregisterProjectionCallback = unregisterProjectionCallback;
            this.stopProjection = stopProjection;
            this.releaseSurface = releaseSurface;
        }

        public boolean ownsStop() {
            return ownsStop;
        }

        public boolean shouldReleaseVirtualDisplay() {
            return releaseVirtualDisplay;
        }

        public boolean shouldDismissPresentation() {
            return dismissPresentation;
        }

        public boolean shouldUnregisterProjectionCallback() {
            return unregisterProjectionCallback;
        }

        public boolean shouldStopProjection() {
            return stopProjection;
        }

        public boolean shouldReleaseSurface() {
            return releaseSurface;
        }

        public boolean hasActions() {
            return releaseVirtualDisplay
                || dismissPresentation
                || unregisterProjectionCallback
                || stopProjection
                || releaseSurface;
        }
    }

    private State state = State.IDLE;
    private int activeToken;
    private boolean projectionOwned;
    private boolean presentationOwned;
    private boolean surfaceOwned;
    private boolean virtualDisplayOwned;
    private int stopGeneration;

    public synchronized int begin() {
        if (state != State.IDLE) {
            return -1;
        }
        activeToken++;
        state = State.STARTING;
        return activeToken;
    }

    public synchronized boolean acquireProjection(int token) {
        if (!isStarting(token) || projectionOwned) {
            return false;
        }
        projectionOwned = true;
        return true;
    }

    public synchronized boolean acceptProjectionTransfer(int token) {
        return acquireProjection(token);
    }

    public synchronized boolean acquirePresentation(int token) {
        if (!isStarting(token) || presentationOwned) {
            return false;
        }
        presentationOwned = true;
        return true;
    }

    public synchronized boolean acceptPresentationTransfer(int token) {
        return acquirePresentation(token);
    }

    public synchronized SurfaceDecision onSurfaceReady(int token) {
        if (!isStarting(token) || !projectionOwned || !presentationOwned || surfaceOwned) {
            return new SurfaceDecision(true, false);
        }
        surfaceOwned = true;
        return new SurfaceDecision(false, true);
    }

    public synchronized boolean acquireSurface(int token) {
        if (!isStarting(token) || surfaceOwned) {
            return false;
        }
        surfaceOwned = true;
        return true;
    }

    public synchronized boolean acquireVirtualDisplay(int token) {
        if (!isStarting(token) || !projectionOwned || !surfaceOwned || virtualDisplayOwned) {
            return false;
        }
        virtualDisplayOwned = true;
        return true;
    }

    public synchronized boolean acceptVirtualDisplayTransfer(int token) {
        return acquireVirtualDisplay(token);
    }

    public synchronized boolean markRunning(int token) {
        if (!isStarting(token) || !virtualDisplayOwned) {
            return false;
        }
        state = State.RUNNING;
        return true;
    }

    public synchronized ReleaseActions stop(int token) {
        if (state == State.IDLE || state == State.STOPPING || token != activeToken) {
            return new ReleaseActions(0, false, false, false, false, false, false);
        }
        state = State.STOPPING;
        activeToken++;
        ReleaseActions actions = new ReleaseActions(
            ++stopGeneration,
            true,
            virtualDisplayOwned,
            presentationOwned,
            projectionOwned,
            projectionOwned,
            surfaceOwned
        );
        virtualDisplayOwned = false;
        presentationOwned = false;
        projectionOwned = false;
        surfaceOwned = false;
        return actions;
    }

    public synchronized boolean finishStop(ReleaseActions actions) {
        if (actions.ownsStop() && actions.stopGeneration == stopGeneration && state == State.STOPPING) {
            state = State.IDLE;
            return true;
        }
        return false;
    }

    public synchronized boolean isStarting(int token) {
        return token == activeToken && state == State.STARTING;
    }

    public synchronized boolean isActive(int token) {
        return token == activeToken && (state == State.STARTING || state == State.RUNNING);
    }

    public synchronized int getActiveToken() {
        return activeToken;
    }

    public synchronized State getState() {
        return state;
    }
}
