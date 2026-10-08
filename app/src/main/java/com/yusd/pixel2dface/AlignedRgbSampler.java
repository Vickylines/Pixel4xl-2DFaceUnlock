package com.yusd.pixel2dface;

import java.nio.ByteBuffer;

/** Samples only the aligned face from strided YUV420. No full-frame Bitmap, JPEG, or retained plane. */
final class AlignedRgbSampler {
    static final int SIZE = 112;
    private final LumaPlane yPlane = new LumaPlane(), uPlane = new LumaPlane(), vPlane = new LumaPlane();
    private final int[] luminance = new int[SIZE * SIZE];
    float brightness, contrast, sharpness;

    void sample(ByteBuffer y, int yRow, int yPixel, ByteBuffer u, int uRow, int uPixel,
            ByteBuffer v, int vRow, int vPixel, int width, int height, int rotation,
            FaceAlignment alignment, ByteBuffer rgb) {
        brightness = contrast = sharpness = Float.NaN;
        if (width < 4 || height < 4 || alignment == null || rgb == null
                || rgb.capacity() < SIZE * SIZE * 3 * Float.BYTES
                || (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270)) {
            throw new IllegalArgumentException("Invalid RGB frame");
        }
        int uprightWidth = rotation == 90 || rotation == 270 ? height : width;
        int uprightHeight = rotation == 90 || rotation == 270 ? width : height;
        for (int cy : new int[]{0, SIZE - 1}) for (int cx : new int[]{0, SIZE - 1}) {
            float ux = alignment.x(cx, cy), uy = alignment.y(cx, cy);
            if (ux < 0 || uy < 0 || ux > uprightWidth - 1 || uy > uprightHeight - 1) {
                throw new IllegalArgumentException("Aligned face leaves the frame");
            }
        }
        try {
            yPlane.bind(y, width, height, yRow, yPixel);
            uPlane.bind(u, (width + 1) / 2, (height + 1) / 2, uRow, uPixel);
            vPlane.bind(v, (width + 1) / 2, (height + 1) / 2, vRow, vPixel);
            rgb.clear();
            long sum = 0, squares = 0, gradient = 0;
            for (int row = 0; row < SIZE; row++) for (int col = 0; col < SIZE; col++) {
                float ux = alignment.x(col, row), uy = alignment.y(col, row);
                float rx = rawX(ux, uy, width, rotation), ry = rawY(ux, uy, height, rotation);
                int luma = yPlane.sample(rx, ry);
                float cb = uPlane.sample(rx * .5f, ry * .5f) - 128f;
                float cr = vPlane.sample(rx * .5f, ry * .5f) - 128f;
                float luminanceRgb = 1.164383f * (luma - 16f);
                rgb.putFloat(normalize(luminanceRgb + 1.596027f * cr));
                rgb.putFloat(normalize(luminanceRgb - .391762f * cb - .812968f * cr));
                rgb.putFloat(normalize(luminanceRgb + 2.017232f * cb));
                int index = row * SIZE + col;
                luminance[index] = luma;
                sum += luma; squares += (long) luma * luma;
                if (col > 0) gradient += Math.abs(luma - luminance[index - 1]);
                if (row > 0) gradient += Math.abs(luma - luminance[index - SIZE]);
            }
            brightness = sum / (float) luminance.length;
            contrast = (float) Math.sqrt(Math.max(0d, squares / (double) luminance.length
                    - (double) brightness * brightness));
            sharpness = gradient / (float) (2 * SIZE * (SIZE - 1));
            rgb.flip();
        } finally { yPlane.unbind(); uPlane.unbind(); vPlane.unbind(); }
    }

    static float rawX(float x, float y, int width, int rotation) {
        return rotation == 90 ? y : rotation == 180 ? width - 1f - x
                : rotation == 270 ? width - 1f - y : x;
    }
    static float rawY(float x, float y, int height, int rotation) {
        return rotation == 90 ? height - 1f - x : rotation == 180 ? height - 1f - y
                : rotation == 270 ? x : y;
    }
    private static float normalize(float channel) {
        return (Math.max(0, Math.min(255, channel)) - 127.5f) / 128f;
    }
}
