package com.yusd.pixel2dface;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;

/** Samples only the aligned face from strided YUV420. No full-frame Bitmap, JPEG, or retained plane. */
final class AlignedRgbSampler {
    static final int SIZE = 112;
    static final int RGB_BYTES = SIZE * SIZE * 3 * Float.BYTES;
    private final PlaneCrop yPlane = new PlaneCrop(), uPlane = new PlaneCrop(), vPlane = new PlaneCrop();
    private final int[] previousLuma = new int[SIZE];
    private final float[] rgbRow = new float[SIZE * 3];
    float brightness, contrast, sharpness;

    boolean sample(ByteBuffer y, int yRow, int yPixel, ByteBuffer u, int uRow, int uPixel,
            ByteBuffer v, int vRow, int vPixel, int width, int height, int rotation,
            FaceAlignment alignment, ByteBuffer rgb) {
        brightness = contrast = sharpness = Float.NaN;
        if (rgb != null) rgb.position(0).limit(0);
        if (width < 4 || height < 4 || alignment == null || rgb == null
                || rgb.capacity() < RGB_BYTES
                || (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270)) {
            throw new IllegalArgumentException("Invalid RGB frame");
        }
        int uprightWidth = rotation == 90 || rotation == 270 ? height : width;
        int uprightHeight = rotation == 90 || rotation == 270 ? width : height;
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (int cy = 0; cy < SIZE; cy += SIZE - 1) for (int cx = 0; cx < SIZE; cx += SIZE - 1) {
            float ux = alignment.x(cx, cy), uy = alignment.y(cx, cy);
            if (ux < 0 || uy < 0 || ux > uprightWidth - 1 || uy > uprightHeight - 1) {
                // Moving partly out of view is an ordinary rejected frame, not a camera error.
                return false;
            }
            float rawX = rawX(ux, uy, width, rotation), rawY = rawY(ux, uy, height, rotation);
            minX = Math.min(minX, rawX); maxX = Math.max(maxX, rawX);
            minY = Math.min(minY, rawY); maxY = Math.max(maxY, rawY);
        }
        int chromaWidth = (width + 1) / 2, chromaHeight = (height + 1) / 2;
        try {
            yPlane.prepare(y, width, height, yRow, yPixel, minX, minY, maxX, maxY);
            uPlane.prepare(u, chromaWidth, chromaHeight, uRow, uPixel,
                    minX * .5f, minY * .5f, maxX * .5f, maxY * .5f);
            vPlane.prepare(v, chromaWidth, chromaHeight, vRow, vPixel,
                    minX * .5f, minY * .5f, maxX * .5f, maxY * .5f);
            writeRgb(width, height, rotation, alignment, rgb);
            return true;
        } finally {
            yPlane.release(); uPlane.release(); vPlane.release();
        }
    }

    private void writeRgb(int width, int height, int rotation, FaceAlignment alignment, ByteBuffer rgb) {
        // An independent cursor keeps the caller's tensor empty until all rows are complete.
        ByteBuffer writable = rgb.duplicate().order(rgb.order());
        writable.limit(RGB_BYTES);
        FloatBuffer output = writable.asFloatBuffer();
        long sum = 0, squares = 0, gradient = 0;
        for (int row = 0; row < SIZE; row++) {
            int leftLuma = 0;
            for (int col = 0; col < SIZE; col++) {
                float ux = alignment.x(col, row), uy = alignment.y(col, row);
                float rx = rawX(ux, uy, width, rotation), ry = rawY(ux, uy, height, rotation);
                int luma = yPlane.sample(rx, ry);
                float cb = uPlane.sample(rx * .5f, ry * .5f) - 128f;
                float cr = vPlane.sample(rx * .5f, ry * .5f) - 128f;
                float luminanceRgb = 1.164383f * (luma - 16f);
                rgbRow[col * 3] = normalize(luminanceRgb + 1.596027f * cr);
                rgbRow[col * 3 + 1] = normalize(luminanceRgb - .391762f * cb - .812968f * cr);
                rgbRow[col * 3 + 2] = normalize(luminanceRgb + 2.017232f * cb);
                sum += luma; squares += (long) luma * luma;
                if (col > 0) gradient += Math.abs(luma - leftLuma);
                if (row > 0) gradient += Math.abs(luma - previousLuma[col]);
                previousLuma[col] = luma;
                leftLuma = luma;
            }
            output.put(rgbRow);
        }
        brightness = sum / (float) (SIZE * SIZE);
        contrast = (float) Math.sqrt(Math.max(0d, squares / (double) (SIZE * SIZE)
                - (double) brightness * brightness));
        sharpness = gradient / (float) (2 * SIZE * (SIZE - 1));
        rgb.limit(RGB_BYTES);
    }

    void clear() {
        yPlane.clear(); uPlane.clear(); vPlane.clear();
        Arrays.fill(rgbRow, 0f);
        Arrays.fill(previousLuma, 0);
        brightness = contrast = sharpness = Float.NaN;
    }

    /** Bulk-read only the rows and columns needed for this affine crop and its bilinear neighbors. */
    private static final class PlaneCrop {
        // Covers a full 480 x 360 luma plane. Higher resolutions keep the same memory bound.
        private static final int MAX_CACHED_BYTES = 256 * 1024;
        private final LumaPlane direct = new LumaPlane();
        private boolean useDirect;
        private byte[] bytes = new byte[0];
        private int left, top, right, bottom, rowStride, pixelStride;

        void prepare(ByteBuffer source, int width, int height, int sourceStride, int pixelStride,
                float minX, float minY, float maxX, float maxY) {
            LumaPlane.checkLayout(source, width, height, sourceStride, pixelStride);
            left = (int) minX;
            top = (int) minY;
            right = Math.min(width - 1, (int) maxX + 1);
            bottom = Math.min(height - 1, (int) maxY + 1);
            this.pixelStride = pixelStride;
            rowStride = (right - left) * pixelStride + 1;
            int required = (bottom - top + 1) * rowStride;
            if (required > MAX_CACHED_BYTES) {
                direct.bind(source, width, height, sourceStride, pixelStride);
                useDirect = true;
                return;
            }
            if (bytes.length < required) {
                clear();
                int capacity = Math.max(required, bytes.length + bytes.length / 2);
                bytes = new byte[Math.min(MAX_CACHED_BYTES, capacity)];
            }
            ByteBuffer reader = source.duplicate();
            int offset = source.position() + top * sourceStride + left * pixelStride;
            for (int dest = 0; dest < required; dest += rowStride) {
                reader.position(offset);
                reader.get(bytes, dest, rowStride);
                offset += sourceStride;
            }
        }

        int sample(float x, float y) {
            if (useDirect) return direct.sample(x, y);
            int x0 = (int) x, y0 = (int) y;
            int index = (y0 - top) * rowStride + (x0 - left) * pixelStride;
            // At an even-sized image edge the last chroma coordinate may be n + 0.5.
            // Both neighbors then read the last sample, as in the clamped plane sampler.
            int dx = x0 < right ? pixelStride : 0;
            int dy = y0 < bottom ? rowStride : 0;
            return LumaPlane.interpolate(bytes[index] & 255, bytes[index + dx] & 255,
                    bytes[index + dy] & 255, bytes[index + dy + dx] & 255, x - x0, y - y0);
        }

        void release() { direct.unbind(); useDirect = false; }
        void clear() { Arrays.fill(bytes, (byte) 0); }
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
