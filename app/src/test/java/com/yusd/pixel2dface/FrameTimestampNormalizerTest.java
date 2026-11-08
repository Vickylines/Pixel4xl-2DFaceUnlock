package com.yusd.pixel2dface;

import org.junit.Test;
import static org.junit.Assert.*;

public final class FrameTimestampNormalizerTest {
    @Test public void cameraEpochDoesNotNeedToEqualSystemEpoch() {
        FrameTimestampNormalizer clock = new FrameTimestampNormalizer();
        assertEquals(1500L, clock.normalize(8_000_000_000L, 1500));
        assertEquals(1533L, clock.normalize(8_033_000_000L, 1537));
        assertEquals(1566L, clock.normalize(8_066_000_000L, 1600));
    }

    @Test public void duplicateAndRegressedSensorTimesCannotBecomeNewEvidence() {
        FrameTimestampNormalizer clock = new FrameTimestampNormalizer();
        clock.normalize(100_000_000L, 1500);
        assertEquals(-1, clock.normalize(100_000_000L, 2000));
        assertEquals(-1, clock.normalize(99_000_000L, 2000));
        assertEquals(1530, clock.normalize(130_000_000L, 2030));
    }

    @Test public void invalidAndOverflowingTimesAreRejected() {
        FrameTimestampNormalizer clock = new FrameTimestampNormalizer();
        assertEquals(-1, clock.normalize(-1, 100));
        assertEquals(-1, clock.normalize(100, -1));
        assertEquals(Long.MAX_VALUE, clock.normalize(0, Long.MAX_VALUE));
        assertEquals(-1, clock.normalize(1_000_000, Long.MAX_VALUE));
    }

    @Test public void startupQueueDelayCanFallWithoutMakingEveryLaterFrameFuture() {
        FrameTimestampNormalizer clock = new FrameTimestampNormalizer();
        assertEquals(1500L, clock.normalize(8_000_000_000L, 1500L));
        // A fixed first-arrival anchor would incorrectly return 1800 and keep rejecting
        // fast ML Kit callbacks after the first frame's delivery queue delay disappears.
        assertEquals(1550L, clock.normalize(8_300_000_000L, 1550L));
        assertEquals(1583L, clock.normalize(8_333_000_000L, 1590L));
        assertEquals(1616L, clock.normalize(8_366_000_000L, 1620L));
    }

    @Test public void laterQueueSpikesNeverMoveTheOffsetBackUp() {
        FrameTimestampNormalizer clock = new FrameTimestampNormalizer();
        assertEquals(1000L, clock.normalize(8_000_000_000L, 1000L));
        assertEquals(1033L, clock.normalize(8_033_000_000L, 1300L));
        assertEquals(1066L, clock.normalize(8_066_000_000L, 1350L));
        assertEquals(1099L, clock.normalize(8_099_000_000L, 1351L));
    }

    @Test public void fallingOffsetRemainsMonotonicAcrossRepeatedArrivalJitter() {
        FrameTimestampNormalizer clock = new FrameTimestampNormalizer();
        assertEquals(1100L, clock.normalize(8_000_000_000L, 1100L));
        assertEquals(1120L, clock.normalize(8_033_000_000L, 1120L));
        assertEquals(1134L, clock.normalize(8_066_000_000L, 1134L));
        assertEquals(1167L, clock.normalize(8_099_000_000L, 1200L));
        assertEquals(1200L, clock.normalize(8_132_000_000L, 1220L));
    }

    @Test public void subMillisecondAndEqualArrivalFramesCannotDuplicateEvidence() {
        FrameTimestampNormalizer clock = new FrameTimestampNormalizer();
        assertEquals(1000L, clock.normalize(8_000_000_000L, 1000L));
        assertEquals(-1L, clock.normalize(8_000_100_000L, 1000L));
        assertEquals(-1L, clock.normalize(8_001_000_000L, 1000L));
        assertEquals(-1L, clock.normalize(8_001_000_000L, 1001L)); // Sensor replay.
        assertEquals(1001L, clock.normalize(8_002_000_000L, 1001L));
    }

    @Test public void regressedArrivalCannotRecalibrateTheClockOrCreateEvidence() {
        FrameTimestampNormalizer clock = new FrameTimestampNormalizer();
        assertEquals(1500L, clock.normalize(8_000_000_000L, 1500L));
        assertEquals(-1L, clock.normalize(8_033_000_000L, 1499L));
        assertEquals(1566L, clock.normalize(8_066_000_000L, 1566L));
    }

    @Test public void calibrationPreventsAdditionOverflowAtTheLongBoundary() {
        FrameTimestampNormalizer clock = new FrameTimestampNormalizer();
        assertEquals(Long.MAX_VALUE - 20L, clock.normalize(0L, Long.MAX_VALUE - 20L));
        assertEquals(Long.MAX_VALUE - 10L,
                clock.normalize(30_000_000L, Long.MAX_VALUE - 10L));
        assertEquals(Long.MAX_VALUE, clock.normalize(40_000_000L, Long.MAX_VALUE));
        assertEquals(-1L, clock.normalize(50_000_000L, Long.MAX_VALUE));
    }

    @Test public void maximalSensorEpochAndNegativeCalibrationOffsetAreSafe() {
        FrameTimestampNormalizer maximal = new FrameTimestampNormalizer();
        assertEquals(0L, maximal.normalize(Long.MAX_VALUE - 33_000_000L, 0L));
        assertEquals(33L, maximal.normalize(Long.MAX_VALUE, 33L));
        FrameTimestampNormalizer negativeOffset = new FrameTimestampNormalizer();
        assertEquals(0L, negativeOffset.normalize(0L, 0L));
        assertEquals(1L, negativeOffset.normalize(1_000_000_000L, 1L));
        assertEquals(2L, negativeOffset.normalize(1_001_000_000L, 2L));
    }
}
