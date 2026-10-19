package com.hongqi.flighting.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public final class InstrumentDisplayPolicyTest {
    private final InstrumentDisplayPolicy policy = new InstrumentDisplayPolicy();

    @Test
    public void selectsExactNameWhenAvailableWithDefaultDisplayPresent() {
        InstrumentDisplayMetadata defaultDisplay = display(
            0,
            "Built-in Screen",
            true,
            true,
            InstrumentDisplayState.ON
        );
        InstrumentDisplayMetadata presentationScreen = display(
            7,
            "PresentationScreen",
            false,
            true,
            InstrumentDisplayState.ON
        );

        InstrumentDisplaySelection selection = policy.select(
            Arrays.asList(defaultDisplay, presentationScreen)
        );

        assertEquals(InstrumentDisplaySelection.Outcome.FOUND, selection.getOutcome());
        assertSame(presentationScreen, selection.getDisplay());
    }

    @Test
    public void returnsAbsentWhenOnlyTheDefaultDisplayExists() {
        InstrumentDisplaySelection selection = policy.select(Collections.singletonList(display(
            0,
            "PresentationScreen",
            true,
            true,
            InstrumentDisplayState.ON
        )));

        assertEquals(InstrumentDisplaySelection.Outcome.ABSENT, selection.getOutcome());
    }

    @Test
    public void returnsAbsentWhenAnUnrelatedDisplayUsesIdOne() {
        InstrumentDisplaySelection selection = policy.select(Arrays.asList(
            display(0, "Built-in Screen", true, true, InstrumentDisplayState.ON),
            display(1, "OtherExternal", false, true, InstrumentDisplayState.ON)
        ));

        assertEquals(InstrumentDisplaySelection.Outcome.ABSENT, selection.getOutcome());
    }

    @Test
    public void returnsInvalidWhenTheExactTargetIsOff() {
        InstrumentDisplaySelection selection = policy.select(Arrays.asList(
            display(0, "Built-in Screen", true, true, InstrumentDisplayState.ON),
            display(7, "PresentationScreen", false, true, InstrumentDisplayState.OFF)
        ));

        assertEquals(InstrumentDisplaySelection.Outcome.INVALID, selection.getOutcome());
    }

    @Test
    public void returnsInvalidWhenTheExactTargetHasUnknownState() {
        InstrumentDisplaySelection selection = policy.select(Arrays.asList(
            display(0, "Built-in Screen", true, true, InstrumentDisplayState.ON),
            display(7, "PresentationScreen", false, true, InstrumentDisplayState.UNKNOWN)
        ));

        assertEquals(InstrumentDisplaySelection.Outcome.INVALID, selection.getOutcome());
    }

    @Test
    public void returnsRemovedWhenTheExactTargetIsNoLongerValid() {
        InstrumentDisplaySelection selection = policy.select(Arrays.asList(
            display(0, "Built-in Screen", true, true, InstrumentDisplayState.ON),
            display(7, "PresentationScreen", false, false, InstrumentDisplayState.ON)
        ));

        assertEquals(InstrumentDisplaySelection.Outcome.REMOVED, selection.getOutcome());
    }

    @Test
    public void doesNotFallBackWhenTheExactTargetIsInvalid() {
        InstrumentDisplaySelection selection = policy.select(Arrays.asList(
            display(0, "Built-in Screen", true, true, InstrumentDisplayState.ON),
            display(7, "PresentationScreen", false, true, InstrumentDisplayState.OFF),
            display(1, "OtherExternal", false, true, InstrumentDisplayState.ON)
        ));

        assertEquals(InstrumentDisplaySelection.Outcome.INVALID, selection.getOutcome());
    }

    private InstrumentDisplayMetadata display(
        int displayId,
        String name,
        boolean defaultDisplay,
        boolean valid,
        InstrumentDisplayState state
    ) {
        return new InstrumentDisplayMetadata(displayId, name, defaultDisplay, valid, state, 0, 1);
    }
}
