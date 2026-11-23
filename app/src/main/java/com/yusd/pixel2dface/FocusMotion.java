package com.yusd.pixel2dface;

/** Timing for the Focus scan, handwritten confirmation and closed-lock feedback. */
final class FocusMotion {
    static final long CYCLE_MS = 1320L;
    private static final float TURN_START = .037f;
    private static final float TURN_END = .690f;
    static final long CHECK_FINISH_MS = 390L;
    static final long SHAKE_START_MS = 144L;
    static final long FAILURE_DURATION_MS = SHAKE_START_MS + 256L + 640L;

    private FocusMotion() { }

    static float fold(float turn) {
        return ease(0, .164f, turn) * (1 - ease(.79f, 1, turn));
    }

    static float scanTurn(float phase) {
        return ease(TURN_START, TURN_END, phase);
    }

    static long remainingTurnMs(float startPhase) {
        return (long) Math.ceil(Math.max(0, TURN_END - startPhase) * CYCLE_MS);
    }

    static long resultDuration(float startPhase, boolean failed) {
        return failed ? FAILURE_DURATION_MS : remainingTurnMs(startPhase) + CHECK_FINISH_MS + 50L;
    }

    static float lockOffset(float elapsed) {
        float t = elapsed - SHAKE_START_MS;
        if (t <= 68) return -7.0f * ease(0, 68, t);
        if (t <= 152) return -7.0f + 11.8f * ease(68, 152, t);
        return 4.8f * (1 - ease(152, 256, t));
    }

    static float lockScale(float elapsed) {
        return 1 - .44f * ease(120, 300, elapsed - SHAKE_START_MS);
    }

    static float hintTravel(float elapsed) {
        float t = elapsed - SHAKE_START_MS - 256;
        if (t <= 200) {
            float u = clamp(t / 200f);
            return u * u * (.74f - .28f * u);
        }
        // Match position and velocity at the handoff, then settle into the caption.
        float u = clamp((t - 200) / 440f), tail = 1 - u;
        return .46f + .54f * ease(0, 1, u) + 1.408f * u * tail * tail;
    }

    static float resultTurn(float startPhase, float turnProgress, long remainingMs) {
        if (turnProgress >= 1 || remainingMs == 0) return 1;
        return scanTurn(startPhase + turnProgress * remainingMs / CYCLE_MS);
    }

    static float ease(float start, float end, float value) {
        float t = clamp((value - start) / (end - start));
        return t * t * (3 - 2 * t);
    }

    static float clamp(float value) {
        return Math.max(0, Math.min(1, value));
    }
}
