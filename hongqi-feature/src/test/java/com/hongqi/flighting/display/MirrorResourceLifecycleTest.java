package com.hongqi.flighting.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public final class MirrorResourceLifecycleTest {
    @Test
    public void givenResolvedTargetWhenDisplayStartupWaitsForResourcesThenItRemainsStarting() {
        // Given
        MirrorDisplayLifecycle lifecycle = new MirrorDisplayLifecycle(new InstrumentDisplayPolicy());
        int token = 7;
        assertTrue(lifecycle.begin(token));

        // When
        lifecycle.resolveStart(token, Arrays.asList(defaultDisplay(), targetDisplay()));

        // Then
        assertEquals(MirrorDisplayLifecycle.State.STARTING, lifecycle.getState());
    }

    @Test
    public void givenRunningResourcesWhenDismissalAndProjectionStopRaceThenCleanupReleasesEachOnce() {
        MirrorResourceLifecycle lifecycle = new MirrorResourceLifecycle();
        int token = lifecycle.begin();
        assertTrue(lifecycle.acquireProjection(token));
        assertTrue(lifecycle.acquirePresentation(token));
        assertTrue(lifecycle.acquireSurface(token));
        assertTrue(lifecycle.acquireVirtualDisplay(token));

        MirrorResourceLifecycle.ReleaseActions dismissal = lifecycle.stop(token);
        MirrorResourceLifecycle.ReleaseActions projectionStop = lifecycle.stop(token);

        assertTrue(dismissal.shouldReleaseVirtualDisplay());
        assertTrue(dismissal.shouldDismissPresentation());
        assertTrue(dismissal.shouldUnregisterProjectionCallback());
        assertTrue(dismissal.shouldStopProjection());
        assertTrue(dismissal.shouldReleaseSurface());
        assertFalse(projectionStop.hasActions());
        assertEquals(MirrorResourceLifecycle.State.STOPPING, lifecycle.getState());
        assertTrue(lifecycle.finishStop(dismissal));
        assertEquals(MirrorResourceLifecycle.State.IDLE, lifecycle.getState());
    }

    @Test
    public void givenInvalidatedTokenWhenSurfaceArrivesThenItIsReleasedWithoutCreatingVirtualDisplay() {
        MirrorResourceLifecycle lifecycle = new MirrorResourceLifecycle();
        int token = lifecycle.begin();
        lifecycle.stop(token);

        MirrorResourceLifecycle.SurfaceDecision decision = lifecycle.onSurfaceReady(token);

        assertTrue(decision.shouldReleaseSurface());
        assertFalse(decision.shouldCreateVirtualDisplay());
    }

    @Test
    public void givenStartingResourcesWhenShowFailsThenStopIsIdempotent() {
        MirrorResourceLifecycle lifecycle = new MirrorResourceLifecycle();
        int token = lifecycle.begin();
        assertTrue(lifecycle.acquireProjection(token));
        assertTrue(lifecycle.acquirePresentation(token));

        MirrorResourceLifecycle.ReleaseActions failedShow = lifecycle.stop(token);
        MirrorResourceLifecycle.ReleaseActions duplicateStop = lifecycle.stop(token);

        assertTrue(failedShow.shouldDismissPresentation());
        assertTrue(failedShow.shouldUnregisterProjectionCallback());
        assertTrue(failedShow.shouldStopProjection());
        assertFalse(duplicateStop.hasActions());
    }

    @Test
    public void givenStartingLifecycleWhenStoppedThenItInvalidatesTokenAndReturnsIdle() {
        MirrorResourceLifecycle lifecycle = new MirrorResourceLifecycle();
        int token = lifecycle.begin();

        MirrorResourceLifecycle.ReleaseActions stop = lifecycle.stop(token);
        assertTrue(lifecycle.finishStop(stop));

        assertFalse(stop.hasActions());
        assertFalse(lifecycle.isActive(token));
        assertEquals(MirrorResourceLifecycle.State.IDLE, lifecycle.getState());
    }

    @Test
    public void givenStoppingLifecycleWhenStoppedAgainThenItHasNoReleaseActions() {
        MirrorResourceLifecycle lifecycle = new MirrorResourceLifecycle();
        int token = lifecycle.begin();
        assertTrue(lifecycle.acquireProjection(token));

        lifecycle.stop(token);

        assertFalse(lifecycle.stop(token).hasActions());
        assertEquals(MirrorResourceLifecycle.State.STOPPING, lifecycle.getState());
    }

    @Test
    public void givenStoppingLifecycleWhenStartIsRequestedThenItRejectsTheNewToken() {
        MirrorResourceLifecycle lifecycle = new MirrorResourceLifecycle();
        int token = lifecycle.begin();
        lifecycle.stop(token);

        assertEquals(-1, lifecycle.begin());
    }

    @Test
    public void givenRunningLifecycleWhenProjectionRevokesBeforeDismissalThenOnlyFirstStopOwnsCleanup() {
        MirrorResourceLifecycle lifecycle = runningLifecycle();
        int token = lifecycle.getActiveToken();

        MirrorResourceLifecycle.ReleaseActions projectionStop = lifecycle.stop(token);
        MirrorResourceLifecycle.ReleaseActions dismissal = lifecycle.stop(token);

        assertTrue(projectionStop.shouldStopProjection());
        assertTrue(projectionStop.shouldDismissPresentation());
        assertFalse(dismissal.hasActions());
    }

    @Test
    public void givenReentrantStopWhenOnlyFirstCallerOwnsTransitionThenSecondCannotFinishIt() {
        MirrorResourceLifecycle lifecycle = runningLifecycle();
        int token = lifecycle.getActiveToken();

        MirrorResourceLifecycle.ReleaseActions firstStop = lifecycle.stop(token);
        MirrorResourceLifecycle.ReleaseActions secondStop = lifecycle.stop(token);

        assertTrue(firstStop.ownsStop());
        assertFalse(secondStop.ownsStop());
        assertFalse(lifecycle.finishStop(secondStop));
        assertEquals(MirrorResourceLifecycle.State.STOPPING, lifecycle.getState());
        assertTrue(lifecycle.finishStop(firstStop));
        assertEquals(MirrorResourceLifecycle.State.IDLE, lifecycle.getState());
    }

    @Test
    public void givenStopBeforeProjectionTransferWhenTransferArrivesThenItIsRejected() {
        MirrorResourceLifecycle lifecycle = new MirrorResourceLifecycle();
        int token = lifecycle.begin();
        lifecycle.stop(token);

        assertFalse(lifecycle.acceptProjectionTransfer(token));
    }

    @Test
    public void givenStopBeforePresentationTransferWhenTransferArrivesThenItIsRejected() {
        MirrorResourceLifecycle lifecycle = new MirrorResourceLifecycle();
        int token = lifecycle.begin();
        lifecycle.stop(token);

        assertFalse(lifecycle.acceptPresentationTransfer(token));
    }

    @Test
    public void givenStopBeforeVirtualDisplayTransferWhenTransferArrivesThenItIsRejected() {
        MirrorResourceLifecycle lifecycle = new MirrorResourceLifecycle();
        int token = lifecycle.begin();
        assertTrue(lifecycle.acquireProjection(token));
        assertTrue(lifecycle.acquirePresentation(token));
        assertTrue(lifecycle.acquireSurface(token));
        lifecycle.stop(token);

        assertFalse(lifecycle.acceptVirtualDisplayTransfer(token));
    }

    @Test
    public void givenRunningLifecycleWhenDisplayIsRemovedThenItReleasesAllAppResourcesOnce() {
        MirrorResourceLifecycle lifecycle = runningLifecycle();
        int token = lifecycle.getActiveToken();

        MirrorResourceLifecycle.ReleaseActions removal = lifecycle.stop(token);

        assertTrue(removal.shouldReleaseVirtualDisplay());
        assertTrue(removal.shouldDismissPresentation());
        assertTrue(removal.shouldUnregisterProjectionCallback());
        assertTrue(removal.shouldStopProjection());
        assertTrue(removal.shouldReleaseSurface());
    }

    @Test
    public void givenRunningResourcesWhenSurfaceIsDestroyedThenItStopsAndLateCallbackCannotRestart() {
        MirrorResourceLifecycle lifecycle = runningLifecycle();
        int token = lifecycle.getActiveToken();

        MirrorResourceLifecycle.ReleaseActions surfaceDestroyed = lifecycle.stop(token);
        MirrorResourceLifecycle.SurfaceDecision lateSurface = lifecycle.onSurfaceReady(token);

        assertTrue(surfaceDestroyed.shouldReleaseSurface());
        assertTrue(lateSurface.shouldReleaseSurface());
        assertFalse(lateSurface.shouldCreateVirtualDisplay());
    }

    @Test
    public void givenIdleLifecycleWhenStoppedThenItHasNoReleaseActions() {
        MirrorResourceLifecycle lifecycle = new MirrorResourceLifecycle();

        assertFalse(lifecycle.stop(0).hasActions());
    }

    private MirrorResourceLifecycle runningLifecycle() {
        MirrorResourceLifecycle lifecycle = new MirrorResourceLifecycle();
        int token = lifecycle.begin();
        assertTrue(lifecycle.acquireProjection(token));
        assertTrue(lifecycle.acquirePresentation(token));
        assertTrue(lifecycle.acquireSurface(token));
        assertTrue(lifecycle.acquireVirtualDisplay(token));
        assertTrue(lifecycle.markRunning(token));
        return lifecycle;
    }

    private InstrumentDisplayMetadata defaultDisplay() {
        return new InstrumentDisplayMetadata(
            0,
            "Built-in Screen",
            true,
            true,
            InstrumentDisplayState.ON,
            0,
            1
        );
    }

    private Path serviceSource() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("hongqi-feature/src/main"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Unable to locate the Android project root");
        }
        return current.resolve("hongqi-feature/src/main/java/com/hongqi/flighting/service/ScreenCaptureService.java");
    }

    private InstrumentDisplayMetadata targetDisplay() {
        return new InstrumentDisplayMetadata(
            7,
            "PresentationScreen",
            false,
            true,
            InstrumentDisplayState.ON,
            0,
            1
        );
    }
}
