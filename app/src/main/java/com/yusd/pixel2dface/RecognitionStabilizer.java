package com.yusd.pixel2dface;

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

    // Fixed primitive ring: the camera hot path never allocates an Observation per frame.
    private final float[] scores = new float[WINDOW_SIZE];
    private final boolean[] accepted = new boolean[WINDOW_SIZE];
    private final boolean[] strong = new boolean[WINDOW_SIZE];
    private final long[] times = new long[WINDOW_SIZE];
    private int first;
    private int size;
    private long latestTime = -1L;
    private float lastBaseThreshold = Float.NaN;

    Result add(float score, float frameThreshold, float baseThreshold,
            boolean secondaryChecksPassed, boolean highConfidence, long now) {
        if (now < 0L || now <= latestTime) {
            clear();
            return Result.empty();
        }
        boolean gap = hasGap(now);
        latestTime = now;
        if (!Float.isFinite(score) || score < 0f || !validThreshold(frameThreshold)
                || !validThreshold(baseThreshold)) {
            clear();
            return Result.empty();
        }
        if (gap || Float.compare(lastBaseThreshold, baseThreshold) != 0) clear();
        lastBaseThreshold = baseThreshold;
        while (size > 0 && now - times[first] > MAX_WINDOW_MS) {
            removeFirst();
        }
        boolean matched = secondaryChecksPassed
                && score <= Math.min(frameThreshold, baseThreshold);
        if (size == WINDOW_SIZE) removeFirst();
        int index = (first + size) % WINDOW_SIZE;
        scores[index] = score;
        accepted[index] = matched;
        strong[index] = matched && highConfidence;
        times[index] = now;
        size++;
        return evaluate(baseThreshold);
    }

    void clear() {
        first = 0;
        size = 0;
    }

    /** Only an explicitly new session may reset the monotonic timestamp watermark. */
    void reset() {
        clear();
        latestTime = -1L;
        lastBaseThreshold = Float.NaN;
    }

    boolean hasGap(long now) {
        return latestTime >= 0L && now > latestTime && now - latestTime > MAX_FRAME_GAP_MS;
    }

    private void removeFirst() {
        first = (first + 1) % WINDOW_SIZE;
        size--;
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
        for (int offset = 0; offset < size; offset++) {
            int index = (first + offset) % WINDOW_SIZE;
            consecutive = accepted[index] ? consecutive + 1 : 0;
            if (strong[index]) {
                if (consecutiveStrong == 0) {
                    strongStartedAt = times[index];
                }
                consecutiveStrong++;
            } else {
                consecutiveStrong = 0;
                strongStartedAt = -1L;
            }
            if (!accepted[index]) {
                continue;
            }
            if (firstAcceptedAt < 0L) {
                firstAcceptedAt = times[index];
            }
            matches++;
            minimum = Math.min(minimum, scores[index]);
            maximum = Math.max(maximum, scores[index]);
            sum += scores[index];
        }
        float mean = matches == 0 ? Float.MAX_VALUE : sum / matches;
        boolean stable = matches > 0 && maximum - minimum <= MAX_ACCEPTED_SPREAD;
        long lastAt = size == 0 ? 0L : times[(first + size - 1) % WINDOW_SIZE];
        boolean fast = consecutiveStrong >= REQUIRED_MATCHES
                && lastAt - strongStartedAt >= MIN_EVIDENCE_MS
                && maximum - minimum <= 0.035f;
        boolean ordinary = size >= MIN_OBSERVATIONS
                && matches >= REQUIRED_MATCHES && consecutive >= 2
                && firstAcceptedAt >= 0L && lastAt - firstAcceptedAt >= MIN_EVIDENCE_MS;
        boolean confirmed = (ordinary || fast) && stable && validThreshold(baseThreshold)
                && mean <= baseThreshold - MEAN_SAFETY_MARGIN;
        return new Result(size, matches, mean, stable, confirmed,
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

        static Result empty() {
            return new Result(0, 0, Float.MAX_VALUE, false, false, false);
        }
    }
}
