package com.hongqi.flighting.display;

import java.util.Objects;

public final class InstrumentDisplayMetadata {
    private final int displayId;
    private final String name;
    private final boolean defaultDisplay;
    private final boolean valid;
    private final InstrumentDisplayState state;
    private final int flags;
    private final int modeId;

    public InstrumentDisplayMetadata(
        int displayId,
        String name,
        boolean defaultDisplay,
        boolean valid,
        InstrumentDisplayState state,
        int flags,
        int modeId
    ) {
        this.displayId = displayId;
        this.name = Objects.requireNonNull(name);
        this.defaultDisplay = defaultDisplay;
        this.valid = valid;
        this.state = Objects.requireNonNull(state);
        this.flags = flags;
        this.modeId = modeId;
    }

    public int getDisplayId() {
        return displayId;
    }

    public String getName() {
        return name;
    }

    public boolean isDefaultDisplay() {
        return defaultDisplay;
    }

    public boolean isValid() {
        return valid;
    }

    public InstrumentDisplayState getState() {
        return state;
    }

    public int getFlags() {
        return flags;
    }

    public int getModeId() {
        return modeId;
    }
}
