package com.yusd.pixel2dface;

import static org.junit.Assert.*;
import org.junit.Test;

public final class FrameQualityPolicyTest {
    @Test public void allOriginalHardBoundariesAreInclusive() {
        FrameQualityPolicy.Sample sample = sample(28f, 22f, 25f, 0.18f, 0.34f, 0.36f,
                12f, 6f, 1.5f);
        assertEquals(FrameQualityPolicy.Reason.NONE, FrameQualityPolicy.problem(sample));
        assertEquals(FrameQualityPolicy.Reason.NONE,
                FrameQualityPolicy.imageProblem(sample(0f, 0f, 0f, 0.3f, 0f, 0f, 248f, 6f, 1.5f)));
        assertEquals(0.24f, FrameQualityPolicy.frameThreshold(0.28f, sample), 0.00001f);
        assertFalse(FrameQualityPolicy.highQuality(sample));
    }

    @Test public void normalMeasurementsHaveNoPenalty() {
        FrameQualityPolicy.Sample sample = sample(14f, 12f, 12f, 0.24f, 0.25f, 0.27f,
                30f, 12f, 3.2f);
        assertEquals(0f, FrameQualityPolicy.posePenalty(sample), 0f);
        assertEquals(0f, FrameQualityPolicy.qualityPenalty(sample), 0f);
        assertEquals(0f, FrameQualityPolicy.framingPenalty(sample), 0f);
        assertTrue(FrameQualityPolicy.highQuality(sample));
    }

    @Test public void extremeValidInputsPreserveEachPenaltyCap() {
        FrameQualityPolicy.Sample sample = sample(28f, 22f, 25f, 0.18f, 0.34f, 0.36f,
                12f, 6f, 1.5f);
        assertEquals(0.028f, FrameQualityPolicy.posePenalty(sample), 0.00001f);
        assertEquals(0.026f, FrameQualityPolicy.qualityPenalty(sample), 0.00001f);
        assertEquals(0.014f, FrameQualityPolicy.framingPenalty(sample), 0.00001f);
    }

    @Test public void nonFiniteMeasurementsFailClosedInsteadOfSkippingComparisons() {
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertEquals(FrameQualityPolicy.Reason.INVALID_SIGNALS, FrameQualityPolicy.problem(
                    sample(invalid, 0f, 0f, 0.3f, 0f, 0f, 100f, 30f, 8f)));
            assertEquals(FrameQualityPolicy.Reason.INVALID_SIGNALS, FrameQualityPolicy.problem(
                    sample(0f, 0f, 0f, 0.3f, invalid, 0f, 100f, 30f, 8f)));
            assertEquals(FrameQualityPolicy.Reason.INVALID_SIGNALS, FrameQualityPolicy.problem(
                    sample(0f, 0f, 0f, 0.3f, 0f, 0f, invalid, 30f, 8f)));
            assertEquals(FrameQualityPolicy.Reason.INVALID_SIGNALS, FrameQualityPolicy.problem(
                    sample(0f, 0f, 0f, 0.3f, 0f, 0f, 100f, invalid, 8f)));
            assertEquals(FrameQualityPolicy.Reason.INVALID_SIGNALS, FrameQualityPolicy.problem(
                    sample(0f, 0f, 0f, 0.3f, 0f, 0f, 100f, 30f, invalid)));
        }
    }

    @Test public void facePrecheckDoesNotNeedImageStatistics() {
        FrameQualityPolicy.Sample sample = sample(0f, 0f, 0f, 0.3f, 0f, 0f,
                Float.NaN, Float.NaN, Float.NaN);
        assertEquals(FrameQualityPolicy.Reason.NONE, FrameQualityPolicy.faceProblem(sample));
        assertEquals(FrameQualityPolicy.Reason.INVALID_SIGNALS, FrameQualityPolicy.problem(sample));
    }

    @Test public void failedMetricsReturnSpecificInstructions() {
        assertEquals(FrameQualityPolicy.Reason.TOO_SMALL, FrameQualityPolicy.problem(
                sample(0f, 0f, 0f, 0.179f, 0f, 0f, 100f, 30f, 8f)));
        assertEquals(FrameQualityPolicy.Reason.OFF_CENTER, FrameQualityPolicy.problem(
                sample(0f, 0f, 0f, 0.3f, 0.341f, 0f, 100f, 30f, 8f)));
        assertEquals(FrameQualityPolicy.Reason.POSE, FrameQualityPolicy.problem(
                sample(28.01f, 0f, 0f, 0.3f, 0f, 0f, 100f, 30f, 8f)));
        assertEquals(FrameQualityPolicy.Reason.TOO_DARK, FrameQualityPolicy.problem(
                sample(0f, 0f, 0f, 0.3f, 0f, 0f, 11.99f, 30f, 8f)));
        assertEquals(FrameQualityPolicy.Reason.TOO_BRIGHT, FrameQualityPolicy.problem(
                sample(0f, 0f, 0f, 0.3f, 0f, 0f, 248.01f, 30f, 8f)));
        assertEquals(FrameQualityPolicy.Reason.LOW_CONTRAST, FrameQualityPolicy.problem(
                sample(0f, 0f, 0f, 0.3f, 0f, 0f, 100f, 5.99f, 8f)));
        assertEquals(FrameQualityPolicy.Reason.BLURRED, FrameQualityPolicy.problem(
                sample(0f, 0f, 0f, 0.3f, 0f, 0f, 100f, 30f, 1.49f)));
    }

    private static FrameQualityPolicy.Sample sample(float yaw, float pitch, float roll,
            float width, float offsetX, float offsetY, float brightness, float contrast,
            float sharpness) {
        return new FrameQualityPolicy.Sample(1, 0.99f, 0.99f, 0.18f, 0.18f,
                yaw, pitch, roll, width, offsetX, offsetY, brightness, contrast, sharpness);
    }
}
