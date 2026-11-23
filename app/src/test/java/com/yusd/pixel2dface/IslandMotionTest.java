package com.yusd.pixel2dface;

import static org.junit.Assert.*;
import org.junit.Test;

public final class IslandMotionTest {
    @Test public void scanSettlesBeforeTurningAndCompletesOneRevolution() {
        assertEquals(0f, IslandMotion.turn(0, IslandMotion.TURN_START_MS), 0);
        assertEquals(1f, IslandMotion.turn(0, IslandMotion.TURN_END_MS), 0);
        float previous = 0;
        for (int t = 1; t <= IslandMotion.CYCLE_MS; t++) {
            float angle = IslandMotion.turn(0, t);
            assertTrue(angle >= previous && angle <= 1);
            assertTrue("Angular velocity exceeded 520 degrees per second", (angle - previous) * 360_000 < 520);
            previous = angle;
        }
    }

    @Test public void aVisibleOrbitKeepsItsClockAndFlowsIntoTheFirstStroke() {
        for (int at = 560; at < IslandMotion.TURN_END_MS; at += 37) {
            float phase = at / (float) IslandMotion.CYCLE_MS;
            float captured = IslandMotion.confirmationPhase(phase, at, false);
            assertEquals(phase, captured, 0);
            for (int dt = 0; at + dt <= IslandMotion.TURN_END_MS; dt += 11) {
                assertEquals(IslandMotion.turn(0, at + dt), IslandMotion.turn(captured, dt), .00001f);
            }
            long duration = IslandMotion.resultDuration(captured);
            assertTrue(IslandMotion.writingAge(IslandMotion.TURN_END_MS - at, duration) >= 0);
            assertEquals(1, IslandMotion.turn(captured, duration), 0);
        }
    }

    @Test public void anEarlyFirstMatchBringsTheOrbitForwardAndStillCompletesIt() {
        for (int at : new int[]{0, 140, 275, 400, 425}) {
            float phase = at / (float) IslandMotion.CYCLE_MS;
            float captured = IslandMotion.confirmationPhase(phase, at, false);
            float takeoff = Math.max(0, 275 - at);
            assertEquals(IslandMotion.turn(phase, 0), IslandMotion.turn(captured, 0), 0);
            assertEquals(0, IslandMotion.turn(captured, takeoff), .000001f);
            assertTrue(IslandMotion.turn(captured, takeoff + 550) > .50f);
            assertEquals(1, IslandMotion.turn(captured, takeoff + 1070), .000001f);
        }
    }

    @Test public void aSettledSmileWritesImmediatelyAndARestoredSilverFaceNeedsOnlyItsColorChange() {
        float landed = IslandMotion.TURN_END_MS / (float) IslandMotion.CYCLE_MS;
        assertEquals(0, IslandMotion.writingAge(0, IslandMotion.resultDuration(landed)), 0);
        for (float phase : new float[]{0, .06f, 1}) {
            float captured = IslandMotion.confirmationPhase(phase, 2400, true);
            assertEquals(1, IslandMotion.turn(captured, 0), 0);
            assertEquals(-60, IslandMotion.writingAge(0, IslandMotion.resultDuration(captured)), 0);
        }
    }

    @Test public void theShellMovesMostOfItsDistanceBeforeTheTail() {
        assertEquals(.50f, IslandMotion.collapse(150, IslandMotion.CLOSE_MS), .035f);
        assertEquals(.87f, IslandMotion.collapse(267, IslandMotion.CLOSE_MS), .025f);
        assertEquals(.98f, IslandMotion.collapse(367, IslandMotion.CLOSE_MS), .015f);
    }

    @Test public void fullCheckHasItsOwnHoldBeforeTheCenteredExit() {
        for (int i = 0; i <= 100; i++) {
            float phase = i / 100f;
            long duration = IslandMotion.resultDuration(phase);
            float checkDone = -IslandMotion.writingAge(0, duration) + IslandMotion.WRITE_END_MS;
            assertEquals(0, IslandMotion.collapse(checkDone + IslandMotion.CHECK_HOLD_MS, duration), 0);
            float previous = 0;
            for (int t = 0; t <= IslandMotion.CLOSE_MS; t++) {
                float closed = IslandMotion.collapse(duration - IslandMotion.CLOSE_MS + t, duration);
                assertTrue(closed + .000001f >= previous && closed <= 1.000001f);
                previous = closed;
            }
            assertEquals(1, previous, 0);
        }
    }
}
