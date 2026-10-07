package com.yusd.pixel2dface;

import java.util.ArrayList;
import java.util.List;

/** Owns independent enrollment evidence; the camera workspace can be reused immediately. */
final class EnrollmentSession {
    static final int REQUIRED_SAMPLES = 10;
    private static final long SAMPLE_INTERVAL_MS = 280;
    private final List<float[]> textures = new ArrayList<>();
    private final List<float[]> geometries = new ArrayList<>();
    private long lastObservedAt = -1;
    private long lastAcceptedAt = -1;

    boolean add(long capturedAtMs, FrameQualityPolicy.Sample sample,
            float[] texture, float[] geometry) {
        if (capturedAtMs < 0 || capturedAtMs <= lastObservedAt || isComplete()) return false;
        lastObservedAt = capturedAtMs;
        if (lastAcceptedAt >= 0 && capturedAtMs - lastAcceptedAt < SAMPLE_INTERVAL_MS) return false;
        if (FrameQualityPolicy.problem(sample) != FrameQualityPolicy.Reason.NONE
                || Math.abs(sample.yaw) > 18 || Math.abs(sample.pitch) > 16
                || Math.abs(sample.roll) > 18 || !IdentityModel.validLiveInputs(texture, geometry)) {
            return false;
        }
        textures.add(texture.clone());
        geometries.add(geometry.clone());
        lastAcceptedAt = capturedAtMs;
        return true;
    }

    int count() { return textures.size(); }
    boolean isComplete() { return count() == REQUIRED_SAMPLES; }
    IdentityModel buildModel() {
        if (!isComplete()) throw new IllegalStateException("Enrollment is incomplete");
        return IdentityModel.enroll(textures, geometries);
    }
    void clear() { textures.clear(); geometries.clear(); }
}
