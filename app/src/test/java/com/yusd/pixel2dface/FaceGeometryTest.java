package com.yusd.pixel2dface;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class FaceGeometryTest {
    @Test
    public void translationDoesNotChangeGeometry() {
        float[] first = geometry(0f, 0f);
        float[] translated = geometry(70f, 120f);
        assertArrayEquals(first, translated, 0.0001f);
    }

    @Test
    public void rejectsDegenerateEyePair() {
        assertNull(FaceGeometry.create(100f, 140f,
                20f, 30f, 21f, 30f, 50f, 60f,
                35f, 85f, 65f, 85f, 50f, 95f));
    }

    private static float[] geometry(float offsetX, float offsetY) {
        return FaceGeometry.create(100f, 140f,
                offsetX + 25f, offsetY + 35f, offsetX + 75f, offsetY + 35f,
                offsetX + 50f, offsetY + 65f,
                offsetX + 35f, offsetY + 90f, offsetX + 65f, offsetY + 90f,
                offsetX + 50f, offsetY + 103f);
    }
}
