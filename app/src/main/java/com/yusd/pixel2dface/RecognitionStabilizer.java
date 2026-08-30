package com.yusd.pixel2dface;

import java.util.ArrayDeque;

/**
 * Keeps a short temporal vote instead of discarding all progress after one noisy camera frame.
 */
final class RecognitionStabilizer {
    static final int WINDOW_SIZE = 6;
    static final int MIN_OBSERVATIONS = 5;
    static final int REQUIRED_MATCHES = 4;
    private static final float MAX_ACCEPTED_SPREAD = 0.055f;
    private static final float MEAN_SAFETY_MARGIN = 0.004f;

    private final ArrayDeque<Observation> observations = new ArrayDeque<>(WINDOW_SIZE);

    Result add(float score, float frameThreshold, float baseThreshold) {
        return add(score, frameThreshold, baseThreshold, true);
    }

    Result add(float score, float frameThreshold, float baseThreshold,
            boolean secondaryChecksPassed) {
        boolean accepted = secondaryChecksPassed
                && Float.isFinite(score) && score <= frameThreshold;
        append(new Observation(score, accepted));
        return evaluate(baseThreshold);
    }

    Result reject(float baseThreshold) {
        append(new Observation(Float.MAX_VALUE, false));
        return evaluate(baseThreshold);
    }

    void clear() {
        observations.clear();
    }

    private void append(Observation observation) {
        observations.addLast(observation);
        while (observations.size() > WINDOW_SIZE) {
            observations.removeFirst();
        }
    }

    private Result evaluate(float baseThreshold) {
        int matches = 0;
        float minimum = Float.MAX_VALUE;
        float maximum = -Float.MAX_VALUE;
        float sum = 0f;
        boolean lastAccepted = false;
        for (Observation observation : observations) {
            lastAccepted = observation.accepted;
            if (!observation.accepted) {
                continue;
            }
            matches++;
            minimum = Math.min(minimum, observation.score);
            maximum = Math.max(maximum, observation.score);
            sum += observation.score;
        }
        float mean = matches == 0 ? Float.MAX_VALUE : sum / matches;
        boolean stable = matches > 0 && maximum - minimum <= MAX_ACCEPTED_SPREAD;
        boolean confirmed = observations.size() >= MIN_OBSERVATIONS
                && matches >= REQUIRED_MATCHES
                && lastAccepted
                && stable
                && mean <= baseThreshold - MEAN_SAFETY_MARGIN;
        return new Result(observations.size(), matches, mean, stable, confirmed);
    }

    static final class Result {
        final int observations;
        final int matches;
        final float meanScore;
        final boolean stable;
        final boolean confirmed;

        Result(int observations, int matches, float meanScore, boolean stable,
                boolean confirmed) {
            this.observations = observations;
            this.matches = matches;
            this.meanScore = meanScore;
            this.stable = stable;
            this.confirmed = confirmed;
        }
    }

    private static final class Observation {
        final float score;
        final boolean accepted;

        Observation(float score, boolean accepted) {
            this.score = score;
            this.accepted = accepted;
        }
    }
}
