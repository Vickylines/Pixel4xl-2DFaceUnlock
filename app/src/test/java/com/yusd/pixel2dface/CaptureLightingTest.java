package com.yusd.pixel2dface;

import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import org.junit.Test;

public final class CaptureLightingTest {
    @Test public void waitsForExposureAndTwoDarkFramesThenRequestsOnce() {
        CaptureLighting lighting = new CaptureLighting();
        ByteBuffer pixels = pixels(20);
        assertFalse(lighting.needsLight(pixels, 64, 64, 64, 1, 1000));
        assertFalse(lighting.needsLight(pixels, 64, 64, 64, 1, 1349));
        assertFalse(lighting.needsLight(pixels, 64, 64, 64, 1, 1350));
        assertTrue(lighting.needsLight(pixels, 64, 64, 64, 1, 1450));
        assertFalse(lighting.needsLight(pixels, 64, 64, 64, 1, 1550));
        assertEquals(0, pixels.position());
    }

    @Test public void brightFramesBreakDarknessAndVisibleFaceDoesNotNeedLight() {
        CaptureLighting lighting = new CaptureLighting();
        ByteBuffer dark = pixels(16), bright = pixels(120);
        lighting.needsLight(dark, 64, 64, 64, 1, 1000);
        assertFalse(lighting.needsLight(dark, 64, 64, 64, 1, 1400));
        assertFalse(lighting.needsLight(bright, 64, 64, 64, 1, 1500));
        assertFalse(lighting.needsLight(dark, 64, 64, 64, 1, 1600));
        for (int y = 24; y < 40; y++) for (int x = 24; x < 40; x++) {
            dark.put(y * 64 + x, (byte) 120);
        }
        assertFalse(lighting.needsLight(dark, 64, 64, 64, 1, 1700));
        assertFalse(lighting.needsLight(dark, 64, 64, 64, 1, 1800));
    }

    @Test public void lightSignalCannotOutliveOrConsumeTheAuthenticationCapability() {
        UnlockResultGuard guard = new UnlockResultGuard();
        guard.start("capture", 1000, 7, 0);
        assertTrue(guard.isAwaitingResult("capture", 1500, 7));
        assertFalse(guard.isAwaitingResult("old", 1500, 7));
        assertFalse(guard.isAwaitingResult("capture", 1500, 6));
        assertFalse(guard.isAwaitingResult("capture", 19001, 7));
        assertTrue(guard.consume("capture", 1600, 7));
        assertFalse(guard.isAwaitingResult("capture", 1601, 7));
        assertTrue(guard.mayDeliver("capture", 1601, 7, 0));
    }

    private static ByteBuffer pixels(int value) {
        byte[] pixels = new byte[64 * 64];
        java.util.Arrays.fill(pixels, (byte) value);
        return ByteBuffer.wrap(pixels);
    }
}
