package com.yusd.pixel2dface;

import static org.junit.Assert.*;
import org.junit.Test;

public final class FaceAlignmentTest {
    @Test public void recoversScaleRotationAndTranslation() {
        float[] points = FaceAlignment.CANONICAL.clone();
        float a = 1.8f, b = .35f;
        for (int i = 0; i < 10; i += 2) {
            float x = points[i], y = points[i + 1];
            points[i] = a * x - b * y + 50;
            points[i + 1] = b * x + a * y + 20;
        }
        FaceAlignment fitted = FaceAlignment.fit(points);
        assertEquals(a, fitted.a, .00001f); assertEquals(b, fitted.b, .00001f);
        assertEquals(50, fitted.tx, .0001f); assertEquals(20, fitted.ty, .0001f);
        assertEquals(50, fitted.x(0, 0), .0001f);
    }
    @Test public void badLandmarksCannotGenerateAPlausibleAlignedFace() {
        assertThrows(IllegalArgumentException.class, () -> FaceAlignment.fit(new float[10]));
        float[] points = FaceAlignment.CANONICAL.clone(); points[0] = Float.NaN;
        assertThrows(IllegalArgumentException.class, () -> FaceAlignment.fit(points));
        float[] flipped = FaceAlignment.CANONICAL.clone();
        for (int i = 0; i < 10; i += 2) flipped[i] = 112 - flipped[i];
        assertThrows(IllegalArgumentException.class, () -> FaceAlignment.fit(flipped));
    }
    @Test public void mapsAllRotationsBackToNonSquareSensorCoordinates() {
        assertEquals(12, AlignedRgbSampler.rawX(12, 25, 480, 0), 0);
        assertEquals(25, AlignedRgbSampler.rawY(12, 25, 360, 0), 0);
        assertEquals(25, AlignedRgbSampler.rawX(12, 25, 480, 90), 0);
        assertEquals(347, AlignedRgbSampler.rawY(12, 25, 360, 90), 0);
        assertEquals(467, AlignedRgbSampler.rawX(12, 25, 480, 180), 0);
        assertEquals(334, AlignedRgbSampler.rawY(12, 25, 360, 180), 0);
        assertEquals(454, AlignedRgbSampler.rawX(12, 25, 480, 270), 0);
        assertEquals(12, AlignedRgbSampler.rawY(12, 25, 360, 270), 0);
    }
}
