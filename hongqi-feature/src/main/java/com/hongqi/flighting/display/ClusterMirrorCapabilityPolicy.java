package com.hongqi.flighting.display;

import java.util.Objects;

public final class ClusterMirrorCapabilityPolicy {
    // 方案A实验构建：镜像窗口提升为 TYPE_APPLICATION_OVERLAY(2038)，
    // 与原车 Pateo 导航窗口同层，后添加者在上，从而覆盖原车导航。
    private static final Decision DEFAULT_DECISION = Decision.supported(
        Reason.EXPERIMENTAL_OVERLAY
    );

    public Decision evaluate() {
        return DEFAULT_DECISION;
    }

    public enum Outcome {
        UNSUPPORTED,
        SUPPORTED
    }

    public enum Reason {
        NAVIGATION_REENTRY_RISK,
        EXPERIMENTAL_OVERLAY
    }

    public static final class Decision {
        private final Outcome outcome;
        private final Reason reason;

        private Decision(Outcome outcome, Reason reason) {
            this.outcome = Objects.requireNonNull(outcome);
            this.reason = Objects.requireNonNull(reason);
        }

        public static Decision unsupported(Reason reason) {
            return new Decision(Outcome.UNSUPPORTED, reason);
        }

        public static Decision supported(Reason reason) {
            return new Decision(Outcome.SUPPORTED, reason);
        }

        public Outcome getOutcome() {
            return outcome;
        }

        public Reason getReason() {
            return reason;
        }
    }
}