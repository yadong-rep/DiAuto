package com.hongqi.flighting.display;

import java.util.List;
import java.util.Objects;

public final class InstrumentDisplayPolicy {
    private static final String TARGET_NAME = "PresentationScreen";

    private boolean allowDefaultDisplay;

    public void setAllowDefaultDisplay(boolean allowDefaultDisplay) {
        this.allowDefaultDisplay = allowDefaultDisplay;
    }

    public InstrumentDisplaySelection select(List<InstrumentDisplayMetadata> displays) {
        for (InstrumentDisplayMetadata display : Objects.requireNonNull(displays)) {
            if (isTarget(display)) {
                return selectTarget(display);
            }
        }
        if (allowDefaultDisplay) {
            for (InstrumentDisplayMetadata display : Objects.requireNonNull(displays)) {
                if (display.isDefaultDisplay() && display.isValid()) {
                    return InstrumentDisplaySelection.found(display);
                }
            }
        }
        return InstrumentDisplaySelection.absent();
    }

    private boolean isTarget(InstrumentDisplayMetadata display) {
        return TARGET_NAME.equals(display.getName()) && !display.isDefaultDisplay();
    }

    private InstrumentDisplaySelection selectTarget(InstrumentDisplayMetadata display) {
        if (!display.isValid()) {
            return InstrumentDisplaySelection.removed(display);
        }
        if (display.getState() != InstrumentDisplayState.ON) {
            return InstrumentDisplaySelection.invalid(display);
        }
        return InstrumentDisplaySelection.found(display);
    }
}
