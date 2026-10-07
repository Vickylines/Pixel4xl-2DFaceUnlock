package com.yusd.pixel2dface;

/** Passive open-eye quality gate. It does not ask the user to blink. */
final class PassiveEyeGate {
    static final float MIN_OPEN_PROBABILITY = 0.70f;
    static final float MIN_CONTOUR_RATIO = 0.10f;

    enum Problem { NONE, CLASSIFICATION_UNAVAILABLE, PROBABILITY_REJECTED, CONTOUR_UNRELIABLE }

    private PassiveEyeGate() {
    }

    static boolean areBothEyesOpen(Float leftProbability, Float rightProbability,
            float leftContourRatio, float rightContourRatio) {
        // ML Kit's classification is the primary signal. Eye contours alone often retain a
        // plausible height when eyelids are closed, so they may never override a closed result.
        return problem(leftProbability, rightProbability, leftContourRatio, rightContourRatio)
                == Problem.NONE;
    }

    /** Fixed diagnostic category; does not expose measurements or relax the eye gate. */
    static Problem problem(Float leftProbability, Float rightProbability,
            float leftContourRatio, float rightContourRatio) {
        if (!validProbability(leftProbability) || !validProbability(rightProbability)) {
            return Problem.CLASSIFICATION_UNAVAILABLE;
        }
        if (leftProbability < MIN_OPEN_PROBABILITY || rightProbability < MIN_OPEN_PROBABILITY) {
            return Problem.PROBABILITY_REJECTED;
        }
        if (!validContourRatio(leftContourRatio) || !validContourRatio(rightContourRatio)) {
            return Problem.CONTOUR_UNRELIABLE;
        }
        return Problem.NONE;
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

    private static boolean validContourRatio(float ratio) {
        return Float.isFinite(ratio) && ratio >= MIN_CONTOUR_RATIO && ratio <= 1f;
    }
}
