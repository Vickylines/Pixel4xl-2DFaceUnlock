package com.yusd.pixel2dface;

import static com.yusd.pixel2dface.FocusMotion.clamp;
import static com.yusd.pixel2dface.FocusMotion.ease;

/** The scan and its confirmation share the same clock through the last arc of a turn. */
final class IslandMotion {
    static final long CYCLE_MS = 2160;
    static final long TURN_START_MS = 425;
    static final long TURN_END_MS = 1495;
    static final long WRITE_END_MS = 395;
    static final long CHECK_HOLD_MS = 330;
    static final long CLOSE_MS = 435;
    static final long RESTORE_MS = 150;

    private static final long RESET_START_MS = 1795;
    private static final long RESET_END_MS = 2110;
    private static final long FACE_REVEALED_MS = 275;
    private static final long JOIN_LEAD_MS = 55;
    private static final long SILVER_LEAD_MS = 60;

    private IslandMotion() { }

    static float turn(float phase, float elapsed) {
        float t = clamp((phase * CYCLE_MS + elapsed - TURN_START_MS) / (TURN_END_MS - TURN_START_MS));
        float tail = 1 - t;
        // One broad velocity peak before halfway, followed by a longer landing.
        return clamp(t * t * (3 - 2 * t + .60f * tail * tail));
    }

    static float reset(float phase) { return ease(RESET_START_MS, RESET_END_MS, phase * CYCLE_MS); }

    static float confirmationPhase(float phase, float scanAge, boolean completedLap) {
        if (phase * CYCLE_MS > TURN_START_MS) return phase;
        // Both ends of a lap show the same silver face. Reuse it after a completed orbit.
        if (completedLap) return 1;
        // An early first match may borrow the quiet part of the entrance. The face starts
        // in exactly the same pose and still makes one full turn at the normal velocity.
        float waitForFace = Math.max(0, FACE_REVEALED_MS - scanAge);
        return Math.max(phase, (TURN_START_MS - waitForFace) / CYCLE_MS);
    }

    static long resultDuration(float phase) {
        long untilWriting = (long) Math.ceil(Math.max(TURN_END_MS - JOIN_LEAD_MS - phase * CYCLE_MS,
                SILVER_LEAD_MS * reset(phase)));
        return untilWriting + WRITE_END_MS + CHECK_HOLD_MS + CLOSE_MS;
    }

    static float writingAge(float elapsed, float duration) {
        return elapsed - (duration - WRITE_END_MS - CHECK_HOLD_MS - CLOSE_MS);
    }

    static float collapse(float elapsed, float duration) {
        float t = clamp((elapsed - (duration - CLOSE_MS)) / CLOSE_MS);
        // Fit to the island height in Apple's 30 fps Face ID film: half closed at about
        // 150 ms, then a soft tail. Both endpoint velocities stay zero.
        float shoulder = t * (1 - t);
        return clamp(t * t * (3 - 2 * t) + shoulder * shoulder * (5.30f - 2.77f * t));
    }
}
