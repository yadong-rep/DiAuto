package com.hongqi.flighting.display;

import java.util.Objects;

public final class InstrumentDisplaySelection {
    public enum Outcome {
        FOUND,
        ABSENT,
        INVALID,
        REMOVED
    }

    private final Outcome outcome;
    private final InstrumentDisplayMetadata display;

    private InstrumentDisplaySelection(Outcome outcome, InstrumentDisplayMetadata display) {
        this.outcome = Objects.requireNonNull(outcome);
        this.display = display;
    }

    public static InstrumentDisplaySelection found(InstrumentDisplayMetadata display) {
        return new InstrumentDisplaySelection(Outcome.FOUND, Objects.requireNonNull(display));
    }

    public static InstrumentDisplaySelection absent() {
        return new InstrumentDisplaySelection(Outcome.ABSENT, null);
    }

    public static InstrumentDisplaySelection invalid(InstrumentDisplayMetadata display) {
        return new InstrumentDisplaySelection(Outcome.INVALID, Objects.requireNonNull(display));
    }

    public static InstrumentDisplaySelection removed(InstrumentDisplayMetadata display) {
        return new InstrumentDisplaySelection(Outcome.REMOVED, Objects.requireNonNull(display));
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public InstrumentDisplayMetadata getDisplay() {
        return display;
    }
}
