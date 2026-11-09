package com.yusd.pixel2dface;

import static org.junit.Assert.*;
import org.junit.Test;

public final class CaptureRotationTest {
    @Test public void physicalOrientationOverridesALockedDisplayInAllFourDirections() {
        for (int display = 0; display < 4; display++) {
            for (int degrees : new int[]{0, 90, 180, 270}) {
                CaptureRotation rotation = new CaptureRotation(display);
                rotation.update(degrees);
                assertEquals((4 - degrees / 90) % 4, rotation.surfaceRotation());
            }
        }
    }

    @Test public void boundaryJitterDoesNotRepeatedlyResetRecognition() {
        CaptureRotation rotation = new CaptureRotation(0);
        rotation.update(0);
        for (int angle : new int[]{44, 46, 54, 46, 44}) assertFalse(rotation.update(angle));
        assertTrue(rotation.update(55));
        assertEquals(3, rotation.surfaceRotation());
        for (int angle : new int[]{54, 46, 44, 36}) assertFalse(rotation.update(angle));
        assertTrue(rotation.update(35));
        assertEquals(0, rotation.surfaceRotation());
    }

    @Test public void wraparoundAndFlatDeviceKeepTheLastKnownOrientation() {
        CaptureRotation rotation = new CaptureRotation(0);
        rotation.update(359);
        for (int angle : new int[]{1, 0, 359, 306, -1}) assertFalse(rotation.update(angle));
        assertTrue(rotation.update(305));
        assertEquals(1, rotation.surfaceRotation());
        assertFalse(rotation.update(-1));
        assertEquals(1, rotation.surfaceRotation());
        assertTrue(rotation.update(180));
        assertEquals(2, rotation.surfaceRotation());
    }
}
