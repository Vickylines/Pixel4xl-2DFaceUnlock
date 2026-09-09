package com.yusd.pixel2dface;

/** Passive open-eye quality gate. It does not ask the user to blink. */
final class PassiveEyeGate {
    static final float MIN_OPEN_PROBABILITY = 0.70f;
    static final float MIN_CONTOUR_RATIO = 0.10f;

    private PassiveEyeGate() {
    }

    static boolean areBothEyesOpen(Float leftProbability, Float rightProbability,
            float leftContourRatio, float rightContourRatio) {
        // ML Kit's classification is the primary signal. Eye contours alone often retain a
        // plausible height when eyelids are closed, so they may never override a closed result.
        return validProbability(leftProbability) && validProbability(rightProbability)
                && leftProbability >= MIN_OPEN_PROBABILITY
                && rightProbability >= MIN_OPEN_PROBABILITY
                && Float.isFinite(leftContourRatio) && Float.isFinite(rightContourRatio)
                && leftContourRatio >= MIN_CONTOUR_RATIO
                && rightContourRatio >= MIN_CONTOUR_RATIO;
    }

    static boolean areConfidentlyOpen(Float leftProbability, Float rightProbability,
            float leftContourRatio, float rightContourRatio) {
        return areBothEyesOpen(leftProbability, rightProbability,
                leftContourRatio, rightContourRatio)
                && leftProbability >= 0.85f && rightProbability >= 0.85f;
    }

    private static boolean validProbability(Float probability) {
        return probability != null && Float.isFinite(probability)
                && probability >= 0f && probability <= 1f;
    }
}
