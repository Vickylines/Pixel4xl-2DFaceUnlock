package com.yusd.pixel2dface;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PassiveUnlockFailurePolicyTest {
    @Test public void missingEyeClassificationCannotCountAsPersistentClosedEyes() {
        assertUnavailableEyesDoNotStop(new FrameQualityPolicy.Sample(1, null, .99f,
                .18f, .18f, 0f, 0f, 0f, .3f, 0f, 0f, 100f, 30f, 8f));
    }

    @Test public void missingEyeContoursCannotCountAsPersistentClosedEyes() {
        assertUnavailableEyesDoNotStop(new FrameQualityPolicy.Sample(1, .99f, .99f,
                0f, .18f, 0f, 0f, 0f, .3f, 0f, 0f, 100f, 30f, 8f));
    }

    @Test public void unavailableEyeFrameBreaksClosedEyeContinuity() {
        PassiveUnlockFailurePolicy policy = new PassiveUnlockFailurePolicy();
        for (long at = 1_000; at <= 2_250; at += 250) {
            assertFalse(policy.shouldStop(FrameQualityPolicy.Reason.EYES_CLOSED, at));
        }
        FrameQualityPolicy.Sample missing = new FrameQualityPolicy.Sample(1, null, .99f,
                .18f, .18f, 0f, 0f, 0f, .3f, 0f, 0f, 100f, 30f, 8f);
        assertFalse(policy.shouldStop(FrameQualityPolicy.problem(missing), 2_400));
        assertFalse(policy.shouldStop(FrameQualityPolicy.Reason.EYES_CLOSED, 2_500));
    }

    private static void assertUnavailableEyesDoNotStop(FrameQualityPolicy.Sample sample) {
        FrameQualityPolicy.Reason reason = FrameQualityPolicy.problem(sample);
        assertNotEquals(FrameQualityPolicy.Reason.NONE, reason);
        PassiveUnlockFailurePolicy policy = new PassiveUnlockFailurePolicy();
        for (long at = 1_000; at <= 3_000; at += 250) {
            assertFalse(policy.shouldStop(reason, at));
        }
    }

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
