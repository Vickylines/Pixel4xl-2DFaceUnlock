package com.yusd.pixel2dface;

import static org.junit.Assert.*;
import org.junit.Test;

public final class DetectionOrientationTest {
    @Test public void searchesAllDirectionsAndKeepsTheDetectedFaceDirection() {
        DetectionOrientation orientation = new DetectionOrientation();
        for (int expected : new int[]{270, 0, 90, 180}) {
            assertEquals(expected, orientation.rotationFor(270));
            orientation.observe(0, 0);
        }
        assertEquals(270, orientation.rotationFor(270));
        orientation.observe(1, 80);
        assertEquals(0, orientation.rotationFor(270));
        for (int i = 0; i < 6; i++) {
            orientation.observe(1, 12);
            assertEquals(0, orientation.rotationFor(270));
        }
        assertEquals(180, orientation.rotationFor(90));
    }

    @Test public void cannotRotateAwayFromCrowdsOrOrdinaryRejectedFaces() {
        DetectionOrientation orientation = new DetectionOrientation();
        orientation.rotationFor(0);
        for (float roll : new float[]{0, 25, 35, 45, -45, Float.NaN, Float.POSITIVE_INFINITY, 181}) {
            orientation.observe(1, roll);
            assertEquals(0, orientation.rotationFor(0));
        }
        orientation.observe(2, 90);
        assertEquals(0, orientation.rotationFor(0));
    }

    @Test public void correctsEitherSideAndUpsideDownOnTheNextFrame() {
        for (int gravity : new int[]{0, 90, 180, 270}) {
            for (float roll : new float[]{-180, -137, -90, -46, 46, 90, 137, 180}) {
                DetectionOrientation direction = new DetectionOrientation();
                assertEquals(gravity, direction.rotationFor(gravity));
                direction.observe(1, roll);
                int correction = Math.abs(roll) > 135 ? 180 : roll > 0 ? 90 : -90;
                int expected = (gravity + correction + 360) % 360;
                assertEquals(expected, direction.rotationFor(gravity));
                direction.observe(1, -4);
                assertEquals(expected, direction.rotationFor(gravity));
            }
        }
    }

    @Test public void contradictoryRollsStillSearchEachDirectionBeforeRepeating() {
        DetectionOrientation direction = new DetectionOrientation();
        int seen = 0;
        for (float roll : new float[]{90, -90, 180, 90}) {
            int bit = 1 << (direction.rotationFor(0) / 90);
            assertEquals(0, seen & bit);
            seen |= bit;
            direction.observe(1, roll);
        }
        assertEquals(15, seen);
    }

    @Test public void missingAmbiguousOrUnalignedFacesCannotCarryAnOffsetAcrossRotation() {
        for (int count : new int[]{0, 1, 2}) {
            DetectionOrientation direction = new DetectionOrientation();
            direction.rotationFor(0);
            direction.observe(0, 0);
            assertEquals(90, direction.rotationFor(0));
            direction.observe(1, 4);
            direction.observe(count, count == 1 ? 40 : 0);
            assertEquals(90, direction.rotationFor(90));
        }
    }

    @Test public void oneMissKeepsTheFoundDirectionButTwoMissesResumeSearch() {
        DetectionOrientation orientation = new DetectionOrientation();
        orientation.rotationFor(270);
        orientation.observe(0, 0);
        assertEquals(0, orientation.rotationFor(270));
        orientation.observe(1, 10);
        orientation.observe(0, 0);
        assertEquals(0, orientation.rotationFor(270));
        orientation.observe(1, 10);
        orientation.observe(0, 0);
        assertEquals(0, orientation.rotationFor(270));
        orientation.observe(0, 0);
        assertEquals(90, orientation.rotationFor(270));
        orientation.observe(0, 0);
        assertEquals(180, orientation.rotationFor(270));
    }
}
