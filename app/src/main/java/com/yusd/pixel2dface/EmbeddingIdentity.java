package com.yusd.pixel2dface;

import java.util.List;

/** Immutable MobileFaceNet identity. No matching or automatic migration between model spaces. */
final class EmbeddingIdentity implements IdentityTemplate {
    static final int DIMENSIONS = 192;
    static final int SAMPLE_COUNT = 10;
    static final float MAX_THRESHOLD = 0.28f;
    static final float MIN_THRESHOLD = 0.18f;
    static final String MODEL_ID = "mobilefacenet-d635649-rgb112-fivepoint-v1";
    private final float[] centroid;
    private final float threshold;

    EmbeddingIdentity(float[] centroid, float threshold) {
        if (!validVector(centroid) || !Float.isFinite(threshold)
                || threshold < MIN_THRESHOLD || threshold > MAX_THRESHOLD) {
            throw new IllegalArgumentException("Invalid embedding identity");
        }
        this.centroid = centroid.clone();
        this.threshold = threshold;
    }

    @Override public float maximumThreshold() { return threshold; }
    @Override public float minimumThreshold() { return 0.10f; }
    @Override public boolean validSample(float[] descriptor, float[] geometry) {
        return validVector(descriptor);
    }
    @Override public IdentityEvidence evaluate(float[] descriptor, float[] geometry, float limit) {
        if (!validVector(descriptor) || !Float.isFinite(limit) || limit < minimumThreshold()
                || limit > threshold) return IdentityEvidence.rejected();
        float score = distance(centroid, descriptor);
        // The candidate backend deliberately uses the ordinary five-observation path.
        // A four-frame path needs device-specific accuracy/false-accept calibration first.
        return new IdentityEvidence(score, score <= limit, false);
    }
    float[] snapshot() { return centroid.clone(); }

    static EmbeddingIdentity enroll(List<float[]> samples) {
        if (samples == null || samples.size() != SAMPLE_COUNT) {
            throw new IllegalArgumentException("Incomplete embedding enrollment");
        }
        float[] mean = new float[DIMENSIONS];
        for (int i = 0; i < samples.size(); i++) {
            float[] sample = samples.get(i);
            if (!validVector(sample)) throw new IllegalArgumentException("Invalid enrollment vector");
            for (int j = 0; j < i; j++) {
                if (distance(sample, samples.get(j)) > 0.22f) {
                    throw new IllegalArgumentException("Inconsistent enrollment identity");
                }
            }
            for (int d = 0; d < DIMENSIONS; d++) mean[d] += sample[d];
        }
        mean = normalize(mean);
        float worst = 0f;
        for (float[] sample : samples) worst = Math.max(worst, distance(mean, sample));
        // Enrollment can tighten the conservative cap; it cannot learn a looser acceptance limit.
        return new EmbeddingIdentity(mean, Math.max(MIN_THRESHOLD,
                Math.min(MAX_THRESHOLD, worst * 1.6f + 0.10f)));
    }

    static boolean validVector(float[] vector) {
        if (vector == null || vector.length != DIMENSIONS) return false;
        double squared = 0d;
        for (float value : vector) {
            if (!Float.isFinite(value) || Math.abs(value) > 1.001f) return false;
            squared += (double) value * value;
        }
        return Math.abs(squared - 1d) <= 0.002d;
    }

    static float[] normalize(float[] vector) {
        if (vector == null || vector.length != DIMENSIONS) {
            throw new IllegalArgumentException("Invalid embedding dimensions");
        }
        double squared = 0d;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite embedding");
            squared += (double) value * value;
        }
        if (!Double.isFinite(squared) || squared < 1e-12d) {
            throw new IllegalArgumentException("Empty embedding");
        }
        float[] result = new float[DIMENSIONS];
        double norm = Math.sqrt(squared);
        for (int i = 0; i < result.length; i++) result[i] = (float) (vector[i] / norm);
        return result;
    }

    static float distance(float[] first, float[] second) {
        double dot = 0, firstNorm = 0, secondNorm = 0;
        for (int i = 0; i < DIMENSIONS; i++) {
            dot += (double) first[i] * second[i];
            firstNorm += (double) first[i] * first[i];
            secondNorm += (double) second[i] * second[i];
        }
        return (float) Math.max(0d, Math.min(2d, 1d - dot / Math.sqrt(firstNorm * secondNorm)));
    }
}
