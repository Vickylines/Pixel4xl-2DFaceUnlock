package com.yusd.pixel2dface;

import java.util.ArrayDeque;

/** Short, monotonic-time votes. Hard quality/eye/face failures must call clear(). */
final class RecognitionStabilizer {
    static final int WINDOW_SIZE = 6;
    static final int MIN_OBSERVATIONS = 5;
    static final int REQUIRED_MATCHES = 4;
    static final long MAX_WINDOW_MS = 900L;
    static final long MAX_FRAME_GAP_MS = 350L;
    // Four distinct 30 fps sensor frames span about 100 ms. This rejects timestamp bursts
    // without forcing good frames to wait for a sixth observation on a 30 fps camera.
    static final long MIN_EVIDENCE_MS = 90L;
    private static final float MAX_ACCEPTED_SPREAD = 0.055f;
    private static final float MEAN_SAFETY_MARGIN = 0.004f;

    private final ArrayDeque<Observation> observations = new ArrayDeque<>(WINDOW_SIZE);

    Result add(float score, float frameThreshold, float baseThreshold,
            boolean secondaryChecksPassed, boolean highConfidence, long now) {
        Observation previous = observations.peekLast();
        if (now < 0L || (previous != null && now <= previous.time)) {
            clear();
            return evaluate(baseThreshold);
        }
        if (previous != null && now - previous.time > MAX_FRAME_GAP_MS) {
            clear();
        }
        while (!observations.isEmpty() && now - observations.peekFirst().time > MAX_WINDOW_MS) {
            observations.removeFirst();
        }
        boolean accepted = secondaryChecksPassed && Float.isFinite(score) && score >= 0f
                && validThreshold(frameThreshold) && validThreshold(baseThreshold)
                && score <= Math.min(frameThreshold, baseThreshold);
        observations.addLast(new Observation(score, accepted, accepted && highConfidence, now));
        while (observations.size() > WINDOW_SIZE) {
            observations.removeFirst();
        }
        return evaluate(baseThreshold);
    }

    void clear() {
        observations.clear();
    }

    private Result evaluate(float baseThreshold) {
        int matches = 0;
        int consecutive = 0;
        int consecutiveStrong = 0;
        long firstAcceptedAt = -1L;
        long strongStartedAt = -1L;
        float minimum = Float.MAX_VALUE;
        float maximum = -Float.MAX_VALUE;
        float sum = 0f;
        for (Observation observation : observations) {
            consecutive = observation.accepted ? consecutive + 1 : 0;
            if (observation.highConfidence) {
                if (consecutiveStrong == 0) {
                    strongStartedAt = observation.time;
                }
                consecutiveStrong++;
            } else {
                consecutiveStrong = 0;
                strongStartedAt = -1L;
            }
            if (!observation.accepted) {
                continue;
            }
            if (firstAcceptedAt < 0L) {
                firstAcceptedAt = observation.time;
            }
            matches++;
            minimum = Math.min(minimum, observation.score);
            maximum = Math.max(maximum, observation.score);
            sum += observation.score;
        }
        float mean = matches == 0 ? Float.MAX_VALUE : sum / matches;
        boolean stable = matches > 0 && maximum - minimum <= MAX_ACCEPTED_SPREAD;
        long lastAt = observations.isEmpty() ? 0L : observations.peekLast().time;
        boolean fast = consecutiveStrong >= REQUIRED_MATCHES
                && lastAt - strongStartedAt >= MIN_EVIDENCE_MS
                && maximum - minimum <= 0.035f;
        boolean ordinary = observations.size() >= MIN_OBSERVATIONS
                && matches >= REQUIRED_MATCHES && consecutive >= 2
                && firstAcceptedAt >= 0L && lastAt - firstAcceptedAt >= MIN_EVIDENCE_MS;
        boolean confirmed = (ordinary || fast) && stable && validThreshold(baseThreshold)
                && mean <= baseThreshold - MEAN_SAFETY_MARGIN;
        return new Result(observations.size(), matches, mean, stable, confirmed,
                confirmed && fast);
    }

    private static boolean validThreshold(float value) {
        return Float.isFinite(value) && value > 0f && value <= 1f;
    }

    static final class Result {
        final int observations;
        final int matches;
        final float meanScore;
        final boolean stable;
        final boolean confirmed;
        final boolean fastPath;

        Result(int observations, int matches, float meanScore, boolean stable,
                boolean confirmed, boolean fastPath) {
            this.observations = observations;
            this.matches = matches;
            this.meanScore = meanScore;
            this.stable = stable;
            this.confirmed = confirmed;
            this.fastPath = fastPath;
        }
    }

    private static final class Observation {
        final float score;
        final boolean accepted;
        final boolean highConfidence;
        final long time;

        Observation(float score, boolean accepted, boolean highConfidence, long time) {
            this.score = score;
            this.accepted = accepted;
            this.highConfidence = highConfidence;
            this.time = time;
        }
    }
}
