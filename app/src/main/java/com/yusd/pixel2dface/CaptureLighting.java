package com.yusd.pixel2dface;

import java.nio.ByteBuffer;

/** One lighting request per capture, based on real pixels before any RGB conversion. */
final class CaptureLighting {
    private final LumaPlane plane = new LumaPlane();
    private long firstFrameAt = -1;
    private int darkFrames;
    private boolean requested;

    boolean needsLight(ByteBuffer pixels, int width, int height, int rowStride,
            int pixelStride, long capturedAt) {
        if (requested) return false;
        if (firstFrameAt < 0) firstFrameAt = capturedAt;
        // Allow the camera's initial automatic exposure to settle before adding light.
        if (capturedAt - firstFrameAt < 350) return false;
        plane.bind(pixels, width, height, rowStride, pixelStride);
        int sum = 0, visible = 0;
        try {
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                int value = plane.sample(width * (.2f + .6f * (x + .5f) / 16f),
                        height * (.2f + .6f * (y + .5f) / 16f));
                sum += value;
                if (value >= 90) visible++;
            }
        } finally { plane.unbind(); }
        // A dark background around an already illuminated face does not need a flash.
        darkFrames = sum < 42 * 256 && visible < 26 ? darkFrames + 1 : 0;
        if (darkFrames < 2) return false;
        requested = true;
        return true;
    }
}
