package com.yusd.pixel2dface;

import java.util.ArrayList;
import java.util.List;

/** Ten time-separated, consistent identity samples. A rejected frame cannot replace prior evidence. */
final class EmbeddingEnrollment {
    private final List<float[]> samples = new ArrayList<>();
    private long latestObserved = -1;
    private long latestAccepted = -1;

    boolean add(long time, FrameQualityPolicy.Sample quality, float[] vector) {
        if (time < 0 || time <= latestObserved || isComplete()) return false;
        latestObserved = time;
        if (latestAccepted >= 0 && time - latestAccepted < 280) return false;
        if (FrameQualityPolicy.problem(quality) != FrameQualityPolicy.Reason.NONE
                || Math.abs(quality.yaw) > 18 || Math.abs(quality.pitch) > 16
                || Math.abs(quality.roll) > 18 || !EmbeddingIdentity.validVector(vector)) return false;
        for (float[] previous : samples) {
            if (EmbeddingIdentity.distance(previous, vector) > 0.22f) return false;
        }
        samples.add(vector.clone());
        latestAccepted = time;
        return true;
    }
    int count() { return samples.size(); }
    boolean isComplete() { return count() == EmbeddingIdentity.SAMPLE_COUNT; }
    EmbeddingIdentity buildModel() { return EmbeddingIdentity.enroll(samples); }
}
