package com.yusd.pixel2dface;

/** Passive open-eye quality gate. It does not ask the user to blink. */
final class PassiveEyeGate {
    static final float MIN_OPEN_PROBABILITY = 0.55f;
    static final float MIN_CONTOUR_RATIO = 0.10f;

    private PassiveEyeGate() {
    }

    static boolean areBothEyesOpen(Float leftProbability, Float rightProbability,
            float leftContourRatio, float rightContourRatio) {
        // ML Kit's classification is the primary signal. Eye contours alone often retain a
        // plausible height when eyelids are closed, so they may never override a closed result.
        return leftProbability != null && rightProbability != null
                && leftProbability >= MIN_OPEN_PROBABILITY
                && rightProbability >= MIN_OPEN_PROBABILITY
                && leftContourRatio >= MIN_CONTOUR_RATIO
                && rightContourRatio >= MIN_CONTOUR_RATIO;
    }
}
