package com.yusd.pixel2dface;

/**
 * Per-session identity decisions. All timestamps use the same normalized monotonic clock.
 * Camera/ML Kit adapters supply values; this class never retains a frame or descriptor.
 */
final class RecognitionEngine {
    static final long MAX_FRAME_AGE_MS = 750L;
    private final IdentityModel model;
    private final float baseThreshold;
    private final RecognitionStabilizer stabilizer = new RecognitionStabilizer();
    private long generation;
    private long startedAt;
    private long latestCapturedAt = -1L;
    private long latestEvaluatedAt = -1L;
    private int openEyeFrames;
    private boolean active;
    private boolean confirmed;

    RecognitionEngine(IdentityModel model, float baseThreshold) {
        if (model == null || !Float.isFinite(baseThreshold) || baseThreshold < 0.24f
                || baseThreshold > model.textureThreshold) {
            throw new IllegalArgumentException("Invalid recognition settings");
        }
        this.model = model;
        this.baseThreshold = baseThreshold;
    }

    synchronized long beginSession(long startedAtMs) {
        if (startedAtMs < 0L) throw new IllegalArgumentException("Invalid session time");
        generation++;
        startedAt = startedAtMs;
        latestCapturedAt = -1L;
        latestEvaluatedAt = -1L;
        confirmed = false;
        active = true;
        stabilizer.reset();
        openEyeFrames = 0;
        return generation;
    }

    synchronized void endSession() {
        active = false;
        clear();
    }

    /** Hard rejection breaks continuity but deliberately keeps timestamp replay protection. */
    synchronized void clear() {
        stabilizer.clear();
        openEyeFrames = 0;
    }

    synchronized Decision recognize(long frameGeneration, long capturedAtMs,
            long evaluatedAtMs, FrameQualityPolicy.Sample sample, float[] texture,
            float[] geometry) {
        // An old callback must neither vote nor erase evidence in the replacement session.
        if (!active || frameGeneration != generation || confirmed) {
            return rejected(FrameQualityPolicy.Reason.STALE_SESSION);
        }
        if (capturedAtMs < startedAt || capturedAtMs <= latestCapturedAt
                || evaluatedAtMs < capturedAtMs || evaluatedAtMs < latestEvaluatedAt) {
            clear();
            return rejected(FrameQualityPolicy.Reason.INVALID_TIME);
        }
        latestCapturedAt = capturedAtMs;
        latestEvaluatedAt = evaluatedAtMs;
        if (evaluatedAtMs - capturedAtMs > MAX_FRAME_AGE_MS) {
            clear();
            return rejected(FrameQualityPolicy.Reason.STALE_FRAME);
        }
        FrameQualityPolicy.Reason problem = FrameQualityPolicy.problem(sample);
        if (problem != FrameQualityPolicy.Reason.NONE) {
            clear();
            return rejected(problem);
        }
        if (!IdentityModel.validLiveInputs(texture, geometry)) {
            clear();
            return rejected(FrameQualityPolicy.Reason.INVALID_DESCRIPTOR);
        }
        // A long sensor gap must also reset the eye counter, not just the vote window.
        if (stabilizer.hasGap(capturedAtMs)) openEyeFrames = 0;
        if (openEyeFrames < Integer.MAX_VALUE) openEyeFrames++;
        float frameThreshold = FrameQualityPolicy.frameThreshold(baseThreshold, sample);
        IdentityModel.Match match = model.compareValidated(texture, geometry, frameThreshold);
        boolean strong = match.isHighConfidence(frameThreshold, model.geometryThreshold)
                && FrameQualityPolicy.highQuality(sample);
        RecognitionStabilizer.Result votes = stabilizer.add(match.textureScore,
                frameThreshold, baseThreshold, match.accepted, strong, capturedAtMs);
        confirmed = votes.confirmed;
        return new Decision(match.accepted ? FrameQualityPolicy.Reason.NONE
                : FrameQualityPolicy.Reason.IDENTITY_MISMATCH,
                match, votes, frameThreshold, true, openEyeFrames);
    }

    private Decision rejected(FrameQualityPolicy.Reason reason) {
        return new Decision(reason, IdentityModel.Match.rejected(),
                RecognitionStabilizer.Result.empty(), baseThreshold, false, 0);
    }

    static final class Decision {
        final FrameQualityPolicy.Reason reason;
        final IdentityModel.Match match;
        final RecognitionStabilizer.Result votes;
        final float frameThreshold;
        final boolean eyesOpen;
        final int openEyeFrames;

        Decision(FrameQualityPolicy.Reason reason, IdentityModel.Match match,
                RecognitionStabilizer.Result votes, float frameThreshold,
                boolean eyesOpen, int openEyeFrames) {
            this.reason = reason;
            this.match = match;
            this.votes = votes;
            this.frameThreshold = frameThreshold;
            this.eyesOpen = eyesOpen;
            this.openEyeFrames = openEyeFrames;
        }
    }
}
