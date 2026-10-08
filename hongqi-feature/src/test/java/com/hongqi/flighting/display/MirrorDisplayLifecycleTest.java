package com.hongqi.flighting.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public final class MirrorDisplayLifecycleTest {
    @Test
    public void givenExactTargetWhenStartResolvesThenItStaysActiveAndCanRun() {
        // Given
        MirrorDisplayLifecycle lifecycle = new MirrorDisplayLifecycle(new InstrumentDisplayPolicy());
        int token = begin(lifecycle);

        // When
        InstrumentDisplaySelection selection = lifecycle.resolveStart(token, displays(target(7)));
        boolean running = lifecycle.markRunning(token);

        // Then
        assertEquals(InstrumentDisplaySelection.Outcome.FOUND, selection.getOutcome());
        assertTrue(running);
        assertEquals(MirrorDisplayLifecycle.State.RUNNING, lifecycle.getState());
        assertEquals(7, lifecycle.getTargetDisplayId());
    }

    @Test
    public void givenTargetAbsentWhenStartResolvesThenItTerminatesWithoutWaiting() {
        // Given
        MirrorDisplayLifecycle lifecycle = new MirrorDisplayLifecycle(new InstrumentDisplayPolicy());
        int token = begin(lifecycle);

        // When
        InstrumentDisplaySelection selection = lifecycle.resolveStart(token, defaultOnly());

        // Then
        assertEquals(InstrumentDisplaySelection.Outcome.ABSENT, selection.getOutcome());
        assertEquals(MirrorDisplayLifecycle.State.IDLE, lifecycle.getState());
        assertFalse(lifecycle.isActive(token));
        assertFalse(lifecycle.hasActiveListener());
    }

    @Test
    public void givenTargetIsOffWhenStartResolvesThenItTerminatesWithoutRunning() {
        // Given
        MirrorDisplayLifecycle lifecycle = new MirrorDisplayLifecycle(new InstrumentDisplayPolicy());
        int token = begin(lifecycle);

        // When
        InstrumentDisplaySelection selection = lifecycle.resolveStart(token, displays(offTarget(7)));

        // Then
        assertEquals(InstrumentDisplaySelection.Outcome.INVALID, selection.getOutcome());
        assertEquals(MirrorDisplayLifecycle.State.IDLE, lifecycle.getState());
        assertFalse(lifecycle.markRunning(token));
    }

    @Test
    public void givenRunningTargetWhenItIsRemovedThenCleanupIsRequestedOnce() {
        // Given
        MirrorDisplayLifecycle lifecycle = runningLifecycle(7);
        int token = lifecycle.getActiveToken();

        // When
        boolean cleanupRequired = lifecycle.onDisplayEvent(token, defaultOnly());
        boolean duplicateCleanupRequired = lifecycle.onDisplayEvent(token, defaultOnly());

        // Then
        assertTrue(cleanupRequired);
        assertFalse(duplicateCleanupRequired);
        assertEquals(MirrorDisplayLifecycle.State.IDLE, lifecycle.getState());
        assertFalse(lifecycle.hasActiveListener());
    }

    @Test
    public void givenStartingTargetWhenItIsRemovedThenCleanupIsRequestedOnce() {
        // Given
        MirrorDisplayLifecycle lifecycle = new MirrorDisplayLifecycle(new InstrumentDisplayPolicy());
        int token = begin(lifecycle);
        lifecycle.resolveStart(token, displays(target(7)));

        // When
        boolean cleanupRequired = lifecycle.onDisplayEvent(token, defaultOnly());
        boolean duplicateCleanupRequired = lifecycle.onDisplayEvent(token, defaultOnly());

        // Then
        assertTrue(cleanupRequired);
        assertFalse(duplicateCleanupRequired);
        assertEquals(MirrorDisplayLifecycle.State.IDLE, lifecycle.getState());
        assertFalse(lifecycle.hasActiveListener());
    }

    @Test
    public void givenRunningTargetWhenItChangesInvalidThenCleanupIsRequested() {
        // Given
        MirrorDisplayLifecycle lifecycle = runningLifecycle(7);
        int token = lifecycle.getActiveToken();

        // When
        boolean cleanupRequired = lifecycle.onDisplayEvent(token, displays(offTarget(7)));

        // Then
        assertTrue(cleanupRequired);
        assertEquals(MirrorDisplayLifecycle.State.IDLE, lifecycle.getState());
    }

    @Test
    public void givenStaleTokenWhenItReceivesEventThenItDoesNothing() {
        // Given
        MirrorDisplayLifecycle lifecycle = runningLifecycle(7);
        int staleToken = lifecycle.getActiveToken() - 1;

        // When
        boolean cleanupRequired = lifecycle.onDisplayEvent(staleToken, defaultOnly());

        // Then
        assertFalse(cleanupRequired);
        assertEquals(MirrorDisplayLifecycle.State.RUNNING, lifecycle.getState());
        assertEquals(7, lifecycle.getTargetDisplayId());
    }

    @Test
    public void givenStartingLifecycleWhenStoppedThenLateCallbacksDoNothing() {
        // Given
        MirrorDisplayLifecycle lifecycle = new MirrorDisplayLifecycle(new InstrumentDisplayPolicy());
        int token = begin(lifecycle);

        // When
        boolean stopped = lifecycle.stop(token);
        boolean lateCleanupRequired = lifecycle.onDisplayEvent(token, displays(target(7)));

        // Then
        assertTrue(stopped);
        assertFalse(lateCleanupRequired);
        assertEquals(MirrorDisplayLifecycle.State.IDLE, lifecycle.getState());
        assertFalse(lifecycle.hasActiveListener());
    }

    @Test
    public void givenStartingLifecycleWhenListenerRegistrationFailsThenItReturnsIdleWithoutOwnership() {
        // Given
        MirrorDisplayLifecycle lifecycle = new MirrorDisplayLifecycle(new InstrumentDisplayPolicy());
        int token = begin(lifecycle);

        // When
        boolean stopped = lifecycle.listenerRegistrationFailed(token);

        // Then
        assertTrue(stopped);
        assertEquals(MirrorDisplayLifecycle.State.IDLE, lifecycle.getState());
        assertFalse(lifecycle.hasActiveListener());
    }

    @Test
    public void givenRegisteredListenerWhenDetachFailsExternallyThenOwnershipIsAlreadyCleared() {
        // Given
        MirrorDisplayLifecycle lifecycle = new MirrorDisplayLifecycle(new InstrumentDisplayPolicy());
        int token = begin(lifecycle);
        assertTrue(lifecycle.listenerRegistered(token));

        // When
        boolean detached = lifecycle.detachListener(token);

        // Then
        assertTrue(detached);
        assertFalse(lifecycle.hasActiveListener());
        assertTrue(lifecycle.isActive(token));
    }

    @Test
    public void givenRegisteredLifecycleWhenDestroyedThenItReachesIdleWithoutOwnership() {
        // Given
        MirrorDisplayLifecycle lifecycle = new MirrorDisplayLifecycle(new InstrumentDisplayPolicy());
        int token = begin(lifecycle);
        assertTrue(lifecycle.listenerRegistered(token));

        // When
        boolean stopped = lifecycle.destroy(token);

        // Then
        assertTrue(stopped);
        assertEquals(MirrorDisplayLifecycle.State.IDLE, lifecycle.getState());
        assertFalse(lifecycle.hasActiveListener());
    }

    @Test
    public void givenRegisteredLifecycleWhenCallbackInventoryFailsThenItTerminatesOnce() {
        // Given
        MirrorDisplayLifecycle lifecycle = new MirrorDisplayLifecycle(new InstrumentDisplayPolicy());
        int token = begin(lifecycle);
        assertTrue(lifecycle.listenerRegistered(token));

        // When
        boolean stopped = lifecycle.listenerCallbackFailed(token);
        boolean duplicateStopped = lifecycle.listenerCallbackFailed(token);

        // Then
        assertTrue(stopped);
        assertFalse(duplicateStopped);
        assertEquals(MirrorDisplayLifecycle.State.IDLE, lifecycle.getState());
        assertFalse(lifecycle.hasActiveListener());
    }

    private MirrorDisplayLifecycle runningLifecycle(int displayId) {
        MirrorDisplayLifecycle lifecycle = new MirrorDisplayLifecycle(new InstrumentDisplayPolicy());
        int token = begin(lifecycle);
        lifecycle.resolveStart(token, displays(target(displayId)));
        lifecycle.markRunning(token);
        return lifecycle;
    }

    private int begin(MirrorDisplayLifecycle lifecycle) {
        int token = 7;
        assertTrue(lifecycle.begin(token));
        return token;
    }

    private java.util.List<InstrumentDisplayMetadata> defaultOnly() {
        return Collections.singletonList(defaultDisplay());
    }

    private java.util.List<InstrumentDisplayMetadata> displays(InstrumentDisplayMetadata target) {
        return Arrays.asList(defaultDisplay(), target);
    }

    private InstrumentDisplayMetadata defaultDisplay() {
        return new InstrumentDisplayMetadata(0, "Built-in Screen", true, true, InstrumentDisplayState.ON, 0, 1);
    }

    private InstrumentDisplayMetadata target(int displayId) {
        return new InstrumentDisplayMetadata(
            displayId,
            "PresentationScreen",
            false,
            true,
            InstrumentDisplayState.ON,
            0,
            1
        );
    }

    private InstrumentDisplayMetadata offTarget(int displayId) {
        return new InstrumentDisplayMetadata(
            displayId,
            "PresentationScreen",
            false,
            true,
            InstrumentDisplayState.OFF,
            0,
            1
        );
    }
}
