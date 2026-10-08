package com.yusd.pixel2dface;

import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public final class AlignedRgbSamplerTest {
    private final AlignedRgbSampler sampler = new AlignedRgbSampler();
    private final ByteBuffer output = ByteBuffer.allocateDirect(112 * 112 * 3 * 4).order(ByteOrder.nativeOrder());
    private static ByteBuffer plane(int size, int value) {
        ByteBuffer result = ByteBuffer.allocate(size + 3);
        while (result.hasRemaining()) result.put((byte) value);
        result.position(3); return result;
    }
    private void sample(int y, int u, int v, int rotation) {
        float[] points = FaceAlignment.CANONICAL.clone();
        for (int i = 0; i < points.length; i++) points[i] += 8;
        ByteBuffer yp = plane(128 * 128, y), up = plane(128 * 64, u), vp = plane(128 * 64, v);
        sampler.sample(yp, 128, 1, up, 128, 2, vp, 128, 2, 128, 128, rotation,
                FaceAlignment.fit(points), output);
        assertEquals(3, yp.position()); assertEquals(3, up.position()); assertEquals(3, vp.position());
    }
    @Test public void blackAndWhiteUseTheModelsRgbNormalizationForEveryRotation() {
        for (int rotation : new int[]{0, 90, 180, 270}) {
            sample(16, 128, 128, rotation);
            assertEquals(-127.5f / 128, output.getFloat(0), .00001f);
            assertEquals(output.getFloat(0), output.getFloat(4), 0);
            assertEquals(16, sampler.brightness, 0);
            assertEquals(0, sampler.contrast, 0);
            assertEquals(0, sampler.sharpness, 0);
            sample(235, 128, 128, rotation);
            assertEquals(127.5f / 128, output.getFloat(0), .00002f);
        }
    }
    @Test public void chromaIsInRgbOrderWithIndependentPixelStrides() {
        sample(81, 90, 240, 0);
        assertTrue(output.getFloat(0) > .95f);
        assertTrue(output.getFloat(4) < -.95f);
        assertTrue(output.getFloat(8) < -.95f);
    }
    @Test public void truncatedChromaPlaneFailsInsteadOfReusingPreviousImage() {
        sample(100, 128, 128, 0);
        assertThrows(IllegalArgumentException.class, () -> sampler.sample(plane(16384, 100), 128, 1,
                plane(1, 128), 64, 1, plane(4096, 128), 64, 1, 128, 128, 0,
                FaceAlignment.fit(FaceAlignment.CANONICAL), output));
        assertTrue(Float.isNaN(sampler.brightness));
    }
}
