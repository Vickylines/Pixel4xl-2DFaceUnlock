package com.yusd.pixel2dface;

/** One identity comparison; quality, replay protection and temporal votes belong to the engine. */
final class IdentityEvidence {
    final float score;
    final boolean accepted;
    final boolean highConfidence;

    IdentityEvidence(float score, boolean accepted, boolean highConfidence) {
        this.score = score;
        this.accepted = accepted;
        this.highConfidence = accepted && highConfidence;
    }

    static IdentityEvidence rejected() {
        return new IdentityEvidence(Float.MAX_VALUE, false, false);
    }
}
