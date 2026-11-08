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

    @Test public void reversedEyeLabelsDoNotChangeGeometry() {
        assertArrayEquals(geometry(0f, 0f), FaceGeometry.create(100f, 140f,
                75f, 35f, 25f, 35f, 50f, 65f,
                35f, 90f, 65f, 90f, 50f, 103f), 0.0001f);
    }

    @Test public void invalidFrameOrLandmarkNumbersFailClosed() {
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertNull(FaceGeometry.create(invalid, 140f, 25f, 35f, 75f, 35f,
                    50f, 65f, 35f, 90f, 65f, 90f, 50f, 103f));
            assertNull(FaceGeometry.create(100f, 140f, 25f, 35f, 75f, 35f,
                    invalid, 65f, 35f, 90f, 65f, 90f, 50f, 103f));
        }
    }

    @Test public void validScaleChangesPreserveTemplateCoordinateSystem() {
        assertArrayEquals(geometry(0f, 0f), FaceGeometry.create(300f, 420f,
                75f, 105f, 225f, 105f, 150f, 195f,
                105f, 270f, 195f, 270f, 150f, 309f), 0.0001f);
    }

    private static float[] geometry(float offsetX, float offsetY) {
        return FaceGeometry.create(100f, 140f,
                offsetX + 25f, offsetY + 35f, offsetX + 75f, offsetY + 35f,
                offsetX + 50f, offsetY + 65f,
                offsetX + 35f, offsetY + 90f, offsetX + 65f, offsetY + 90f,
                offsetX + 50f, offsetY + 103f);
    }
}
