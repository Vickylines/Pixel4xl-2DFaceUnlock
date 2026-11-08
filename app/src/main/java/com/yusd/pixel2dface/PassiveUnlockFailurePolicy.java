package com.yusd.pixel2dface;

/** Bounds an unsuccessful passive scan without treating an ordinary blink as a failure. */
final class PassiveUnlockFailurePolicy {
    static final long CLOSED_EYES_LIMIT_MS = 1_500L;
    private static final long MAX_FRAME_GAP_MS = 350L;
    private long closedSince = -1L;
    private long lastFrame = -1L;

    boolean shouldStop(FrameQualityPolicy.Reason reason, long capturedAt) {
        if (capturedAt < 0L || capturedAt <= lastFrame) {
            closedSince = -1L;
            return false;
        }
        if (lastFrame >= 0L && capturedAt - lastFrame > MAX_FRAME_GAP_MS) closedSince = -1L;
        lastFrame = capturedAt;
        if (reason != FrameQualityPolicy.Reason.EYES_CLOSED) {
            closedSince = -1L;
            return false;
        }
        if (closedSince < 0L) closedSince = capturedAt;
        return capturedAt - closedSince >= CLOSED_EYES_LIMIT_MS;
    }

    void breakContinuity() { closedSince = -1L; }
}
