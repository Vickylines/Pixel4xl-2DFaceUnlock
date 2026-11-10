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
        assertEquals(90, orientation.rotationFor(90));
    }

    @Test public void cannotRotateAwayFromCrowdsOrOrdinaryRejectedFaces() {
        DetectionOrientation orientation = new DetectionOrientation();
        orientation.rotationFor(0);
        for (float roll : new float[]{0, 25, 35, 45, -45, Float.NaN}) {
            orientation.observe(1, roll);
            assertEquals(0, orientation.rotationFor(0));
        }
        orientation.observe(2, 90);
        assertEquals(0, orientation.rotationFor(0));
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
