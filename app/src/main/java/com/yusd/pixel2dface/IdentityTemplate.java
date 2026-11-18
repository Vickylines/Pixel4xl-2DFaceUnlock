package com.yusd.pixel2dface;

/** Backend-independent identity contract. Smaller distances always indicate a closer match. */
interface IdentityTemplate {
    float maximumThreshold();
    float minimumThreshold();
    /** Validate the descriptor before scoring. Invalid input returns IdentityEvidence.INVALID. */
    IdentityEvidence evaluate(float[] descriptor, float[] geometry, float threshold);
}
