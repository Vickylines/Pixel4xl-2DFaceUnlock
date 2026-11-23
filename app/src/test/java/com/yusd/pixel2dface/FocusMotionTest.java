package com.yusd.pixel2dface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FocusMotionTest {
    @Test
    public void scanCompletesOneForwardRevolution() {
        assertEquals(0, FocusMotion.scanTurn(0), 0);
        float angle = 0;
        for (int i = 1; i <= 1000; i++) {
            float next = FocusMotion.scanTurn(i / 1000f);
            assertTrue(next >= angle);
            angle = next;
        }
        assertTrue(FocusMotion.scanTurn(.45f) > .5f);
        assertEquals(1, FocusMotion.scanTurn(.70f), 0);
        assertEquals(0, FocusMotion.fold(0), 0);
        assertEquals(1, FocusMotion.fold(.5f), 0);
        assertEquals(0, FocusMotion.fold(1), 0);
        assertEquals(1, angle, 0);
    }

    @Test
    public void resultKeepsTurningForwardFromTheExactScanAngle() {
        for (int i = 0; i <= 999; i++) {
            float phase = i / 1000f;
            long turnMs = FocusMotion.remainingTurnMs(phase);
            float angle = FocusMotion.resultTurn(phase, 0, turnMs);
            assertEquals(FocusMotion.scanTurn(phase), angle, 0);
            for (int step = 1; step <= 60; step++) {
                float next = FocusMotion.resultTurn(phase, step / 60f, turnMs);
                assertTrue(next + .000001f >= angle);
                angle = next;
            }
            assertEquals(1, angle, 0);
            assertEquals(FocusMotion.CHECK_FINISH_MS + 50L, FocusMotion.resultDuration(phase, false) - turnMs);
            assertEquals(FocusMotion.FAILURE_DURATION_MS, FocusMotion.resultDuration(phase, true));
        }
    }

    @Test public void closedLockShakesLeftThenRightBeforeItMovesBesideTheHint() {
        float start = FocusMotion.SHAKE_START_MS;
        assertEquals(0, FocusMotion.lockOffset(start), 0);
        assertEquals(-7, FocusMotion.lockOffset(start + 68), .001f);
        assertEquals(4.8f, FocusMotion.lockOffset(start + 152), .001f);
        assertEquals(0, FocusMotion.lockOffset(start + 256), 0);
        assertEquals(0, FocusMotion.hintTravel(start + 256), 0);
        assertEquals(.56f, FocusMotion.lockScale(FocusMotion.FAILURE_DURATION_MS), .001f);
        float previous = 0;
        for (int ms = 0; ms <= FocusMotion.FAILURE_DURATION_MS; ms++) {
            float next = FocusMotion.hintTravel(ms);
            assertTrue(next >= previous - .000001f && next <= 1.000001f);
            previous = next;
        }
        assertEquals(1, previous, .000001f);
    }

    @Test public void resultAtAnyAngleFollowsTheUninterruptedScan() {
        for (int i = 0; i < 100; i++) {
            float phase = i / 100f;
            long remaining = FocusMotion.remainingTurnMs(phase);
            for (long elapsed = 0; elapsed < remaining; elapsed += 8) {
                float expected = FocusMotion.scanTurn(phase + elapsed / (float) FocusMotion.CYCLE_MS);
                assertEquals(expected, FocusMotion.resultTurn(phase, elapsed / (float) remaining, remaining), .00001f);
            }
        }
    }
}
