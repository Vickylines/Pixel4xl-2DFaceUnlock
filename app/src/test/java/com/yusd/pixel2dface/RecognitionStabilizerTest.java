package com.yusd.pixel2dface;

import static org.junit.Assert.*;
import org.junit.Test;

public final class RecognitionStabilizerTest {
    private final RecognitionStabilizer votes = new RecognitionStabilizer();

    private RecognitionStabilizer.Result add(float score, boolean strong, long now) {
        return votes.add(score, 0.28f, 0.28f, true, strong, now);
    }

    @Test public void ordinaryVotesNeedFiveObservations() {
        for (int i = 0; i < 4; i++) assertFalse(add(0.26f, false, i * 60L).confirmed);
        assertTrue(add(0.26f, false, 240L).confirmed);
    }

    @Test public void oneTextureMissAllowsFourFreshMatches() {
        add(0.26f, false, 0L);
        add(0.26f, false, 60L);
        add(0.40f, false, 120L);
        add(0.26f, false, 180L);
        assertTrue(add(0.26f, false, 240L).confirmed);
    }

    @Test public void singleMatchingFrameAfterMissCannotReuseOldVotes() {
        add(0.26f, false, 0L);
        add(0.26f, false, 60L);
        add(0.26f, false, 120L);
        add(0.40f, false, 180L);
        assertFalse(add(0.26f, false, 240L).confirmed);
        assertTrue(add(0.26f, false, 300L).confirmed);
    }

    @Test public void hardRejectionErasesHistory() {
        for (int i = 0; i < 4; i++) add(0.26f, false, i * 60L);
        votes.clear(); // Eyes closed / face missing / invalid geometry or quality.
        assertEquals(1, add(0.26f, false, 240L).matches);
        assertFalse(add(0.26f, false, 300L).confirmed);
    }

    @Test public void longFrameGapErasesHistory() {
        for (int i = 0; i < 4; i++) add(0.26f, false, i * 60L);
        RecognitionStabilizer.Result result = add(0.26f, false, 531L);
        assertFalse(result.confirmed);
        assertEquals(1, result.matches);
    }

    @Test public void slowFramesExpireOutOfWindow() {
        for (int i = 0; i < 8; i++) {
            RecognitionStabilizer.Result result = add(0.26f, false, i * 300L);
            assertFalse(result.confirmed);
            assertTrue(result.observations <= 4);
        }
    }

    @Test public void strongPathRequiresFourConsecutiveFrames() {
        for (int i = 0; i < 3; i++) assertFalse(add(0.20f, true, i * 33L).confirmed);
        RecognitionStabilizer.Result result = add(0.20f, true, 99L);
        assertTrue(result.confirmed);
        assertTrue(result.fastPath);
    }

    @Test public void strongFlagCannotBypassIdentityRejection() {
        for (int i = 0; i < 8; i++) {
            assertFalse(votes.add(0.20f, 0.28f, 0.28f, false, true, i * 60L).confirmed);
        }
    }

    @Test public void strongPathCannotMixInNormalFrames() {
        add(0.20f, true, 0L);
        add(0.20f, false, 60L);
        add(0.20f, true, 120L);
        assertFalse(add(0.20f, true, 180L).confirmed);
    }

    @Test public void burstCannotReplaceTimeEvidence() {
        for (int i = 0; i < 6; i++) assertFalse(add(0.20f, true, i * 10L).confirmed);
    }

    @Test public void duplicateAndBackwardsTimesInvalidateVotes() {
        add(0.20f, true, 100L);
        assertEquals(0, add(0.20f, true, 100L).matches);
        add(0.20f, true, 100L);
        assertEquals(0, add(0.20f, true, 99L).matches);
        assertEquals(0, add(0.20f, true, -1L).matches);
    }

    @Test public void unstableScoresNeverConfirm() {
        for (int i = 0; i < 8; i++) {
            assertFalse(add(i % 2 == 0 ? 0.20f : 0.27f, true, i * 60L).confirmed);
        }
    }

    @Test public void framePenaltyCannotBeBypassedByBaseThreshold() {
        for (int i = 0; i < 8; i++) {
            assertFalse(votes.add(0.27f, 0.25f, 0.28f, true, true, i * 60L).confirmed);
        }
    }

    @Test public void marginalMeanCannotConfirm() {
        for (int i = 0; i < 8; i++) assertFalse(add(0.278f, false, i * 60L).confirmed);
    }

    @Test public void invalidScoresAndThresholdsFailClosed() {
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -0.01f}) {
            votes.clear();
            for (int i = 0; i < 6; i++) assertFalse(add(invalid, true, i * 60L).confirmed);
        }
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f}) {
            votes.clear();
            for (int i = 0; i < 6; i++) {
                assertFalse(votes.add(0.20f, invalid, invalid, true, true, i * 60L).confirmed);
            }
        }
    }

    @Test public void clearRetainsReplayWatermarkUntilAnExplicitNewSession() {
        add(0.20f, true, 100L);
        votes.clear();
        assertEquals(0, add(0.20f, true, 100L).observations);
        assertEquals(0, add(0.20f, true, 100L).observations);
        votes.reset();
        assertEquals(1, add(0.20f, true, 100L).observations);
    }

    @Test public void changingBaseThresholdCannotReusePreviousProfileVotes() {
        for (int i = 0; i < 3; i++) add(0.20f, true, i * 33L);
        RecognitionStabilizer.Result changed = votes.add(0.20f, 0.27f, 0.27f,
                true, true, 99L);
        assertEquals(1, changed.matches);
        assertFalse(changed.confirmed);
    }

    @Test public void malformedScoreHardRejectsInsteadOfOccupyingOneOrdinaryMiss() {
        for (int i = 0; i < 3; i++) add(0.20f, false, i * 60L);
        assertEquals(0, add(Float.NaN, false, 180L).observations);
        assertEquals(1, add(0.20f, false, 240L).matches);
    }

    @Test public void ringWrapCannotReviveExpiredEvidence() {
        for (int i = 0; i < 100; i++) {
            RecognitionStabilizer.Result result = add(i % 3 == 0 ? 0.40f : 0.26f,
                    false, i * 60L);
            assertTrue(result.observations <= RecognitionStabilizer.WINDOW_SIZE);
            if (i >= 5) assertEquals(4, result.matches);
            assertEquals(i >= 5 && i % 3 == 2, result.confirmed);
        }
    }
}
