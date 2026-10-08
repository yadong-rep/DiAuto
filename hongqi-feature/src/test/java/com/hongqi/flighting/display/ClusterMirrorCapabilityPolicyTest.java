package com.hongqi.flighting.display;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public final class ClusterMirrorCapabilityPolicyTest {
    @Test
    public void givenExperimentalBuildWhenCapabilityIsEvaluatedThenItIsSupportedViaOverlayWindow() {
        // Given
        ClusterMirrorCapabilityPolicy policy = new ClusterMirrorCapabilityPolicy();

        // When
        ClusterMirrorCapabilityPolicy.Decision decision = policy.evaluate();

        // Then
        assertEquals(ClusterMirrorCapabilityPolicy.Outcome.SUPPORTED, decision.getOutcome());
        assertEquals(
            ClusterMirrorCapabilityPolicy.Reason.EXPERIMENTAL_OVERLAY,
            decision.getReason()
        );
    }

    @Test
    public void givenPolicyWhenTypesAreInspectedThenSupportedAndUnsupportedOutcomesExist() {
        // Given
        ClusterMirrorCapabilityPolicy.Outcome[] expectedOutcomes = {
            ClusterMirrorCapabilityPolicy.Outcome.UNSUPPORTED,
            ClusterMirrorCapabilityPolicy.Outcome.SUPPORTED
        };
        ClusterMirrorCapabilityPolicy.Reason[] expectedReasons = {
            ClusterMirrorCapabilityPolicy.Reason.NAVIGATION_REENTRY_RISK,
            ClusterMirrorCapabilityPolicy.Reason.EXPERIMENTAL_OVERLAY
        };

        // When
        ClusterMirrorCapabilityPolicy.Outcome[] actualOutcomes =
            ClusterMirrorCapabilityPolicy.Outcome.values();
        ClusterMirrorCapabilityPolicy.Reason[] actualReasons =
            ClusterMirrorCapabilityPolicy.Reason.values();

        // Then
        assertArrayEquals(expectedOutcomes, actualOutcomes);
        assertArrayEquals(expectedReasons, actualReasons);
    }

}