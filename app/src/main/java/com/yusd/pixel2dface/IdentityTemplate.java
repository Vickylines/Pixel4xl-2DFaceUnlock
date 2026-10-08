package com.yusd.pixel2dface;

/** Backend-independent identity contract. Smaller distances always indicate a closer match. */
interface IdentityTemplate {
    float maximumThreshold();
    float minimumThreshold();
    boolean validSample(float[] descriptor, float[] geometry);
    IdentityEvidence evaluate(float[] descriptor, float[] geometry, float threshold);
}
