package com.yusd.pixel2dface;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PassiveUnlockFailurePolicyTest {
    @Test public void persistentClosedEyesStopTheScanAfterTheGracePeriod() {
        PassiveUnlockFailurePolicy policy = new PassiveUnlockFailurePolicy();
        for (long at = 1_000; at < 2_500; at += 250) {
            assertFalse(policy.shouldStop(FrameQualityPolicy.Reason.EYES_CLOSED, at));
        }
        assertTrue(policy.shouldStop(FrameQualityPolicy.Reason.EYES_CLOSED, 2_500));
    }

    @Test public void ordinaryBlinkDoesNotEndTheSession() {
        PassiveUnlockFailurePolicy policy = new PassiveUnlockFailurePolicy();
        assertFalse(policy.shouldStop(FrameQualityPolicy.Reason.EYES_CLOSED, 1_000));
        assertFalse(policy.shouldStop(FrameQualityPolicy.Reason.EYES_CLOSED, 1_200));
        assertFalse(policy.shouldStop(FrameQualityPolicy.Reason.NONE, 1_300));
        assertFalse(policy.shouldStop(FrameQualityPolicy.Reason.EYES_CLOSED, 1_400));
    }

    @Test public void FaceLossAndDetectorRejectionBreakClosedEyeContinuity() {
        PassiveUnlockFailurePolicy policy = new PassiveUnlockFailurePolicy();
        for (long at = 1_000; at <= 2_250; at += 250) policy.shouldStop(FrameQualityPolicy.Reason.EYES_CLOSED, at);
        assertFalse(policy.shouldStop(FrameQualityPolicy.Reason.NO_FACE, 2_400));
        assertFalse(policy.shouldStop(FrameQualityPolicy.Reason.EYES_CLOSED, 2_500));
        policy.breakContinuity();
        assertFalse(policy.shouldStop(FrameQualityPolicy.Reason.EYES_CLOSED, 2_700));
    }

    @Test public void LongGapCannotCountAsContinuousClosedEyeEvidence() {
        PassiveUnlockFailurePolicy policy = new PassiveUnlockFailurePolicy();
        assertFalse(policy.shouldStop(FrameQualityPolicy.Reason.EYES_CLOSED, 1_000));
        assertFalse(policy.shouldStop(FrameQualityPolicy.Reason.EYES_CLOSED, 5_000));
        assertFalse(policy.shouldStop(FrameQualityPolicy.Reason.EYES_CLOSED, 5_250));
    }

    @Test public void ReplayedOrInvalidTimesCannotTriggerAnAbort() {
        PassiveUnlockFailurePolicy policy = new PassiveUnlockFailurePolicy();
        assertFalse(policy.shouldStop(FrameQualityPolicy.Reason.EYES_CLOSED, -1));
        for (long at = 1_000; at <= 2_250; at += 250) policy.shouldStop(FrameQualityPolicy.Reason.EYES_CLOSED, at);
        assertFalse(policy.shouldStop(FrameQualityPolicy.Reason.EYES_CLOSED, 2_250));
        assertFalse(policy.shouldStop(FrameQualityPolicy.Reason.EYES_CLOSED, 2_000));
        assertFalse(policy.shouldStop(FrameQualityPolicy.Reason.EYES_CLOSED, 2_500));
    }
}
