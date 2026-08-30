package com.yusd.pixel2dface;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RecognitionStabilizerTest {
    @Test
    public void oneNoisyFrameDoesNotEraseValidVotes() {
        RecognitionStabilizer stabilizer = new RecognitionStabilizer();
        float threshold = 0.33f;
        stabilizer.add(0.27f, threshold, threshold);
        stabilizer.add(0.28f, threshold, threshold);
        stabilizer.reject(threshold);
        stabilizer.add(0.29f, threshold, threshold);
        RecognitionStabilizer.Result result = stabilizer.add(0.28f, threshold, threshold);

        assertTrue(result.confirmed);
        assertTrue(result.matches == 4);
    }

    @Test
    public void fewerThanFourMatchesNeverConfirms() {
        RecognitionStabilizer stabilizer = new RecognitionStabilizer();
        float threshold = 0.33f;
        stabilizer.add(0.27f, threshold, threshold);
        stabilizer.reject(threshold);
        stabilizer.add(0.28f, threshold, threshold);
        stabilizer.reject(threshold);
        RecognitionStabilizer.Result result = stabilizer.add(0.29f, threshold, threshold);

        assertFalse(result.confirmed);
    }

    @Test
    public void unstableAcceptedScoresDoNotConfirm() {
        RecognitionStabilizer stabilizer = new RecognitionStabilizer();
        float threshold = 0.35f;
        stabilizer.add(0.20f, threshold, threshold);
        stabilizer.add(0.34f, threshold, threshold);
        stabilizer.add(0.21f, threshold, threshold);
        stabilizer.add(0.33f, threshold, threshold);
        RecognitionStabilizer.Result result = stabilizer.add(0.22f, threshold, threshold);

        assertFalse(result.confirmed);
        assertFalse(result.stable);
    }

    @Test
    public void frameSpecificPenaltyCannotBeBypassedByBaseLimit() {
        RecognitionStabilizer stabilizer = new RecognitionStabilizer();
        float baseThreshold = 0.33f;
        float marginalFrameThreshold = 0.29f;
        for (int i = 0; i < 5; i++) {
            stabilizer.add(0.31f, marginalFrameThreshold, baseThreshold);
        }
        RecognitionStabilizer.Result result = stabilizer.add(
                0.28f, marginalFrameThreshold, baseThreshold);

        assertFalse(result.confirmed);
        assertTrue(result.matches == 1);
    }
}
