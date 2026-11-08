package com.yusd.pixel2dface;

import java.nio.ByteBuffer;

/** Checked, Android-independent view of the Y plane. Absolute reads preserve its cursor. */
final class LumaPlane {
    private ByteBuffer buffer;
    private int baseOffset;
    private int width;
    private int height;
    private int rowStride;
    private int pixelStride;

    void bind(ByteBuffer source, int width, int height, int rowStride, int pixelStride) {
        unbind();
        if (source == null || width < 2 || height < 2 || rowStride <= 0 || pixelStride <= 0) {
            throw new IllegalArgumentException("Invalid Y plane");
        }
        long rowBytes = (long) (width - 1) * pixelStride + 1L;
        long lastOffset = (long) source.position() + (long) (height - 1) * rowStride
                + (long) (width - 1) * pixelStride;
        if (rowBytes > rowStride || lastOffset >= source.limit()) {
            throw new IllegalArgumentException("Y plane strides exceed buffer limits");
        }
        this.buffer = source;
        this.baseOffset = source.position();
        this.width = width;
        this.height = height;
        this.rowStride = rowStride;
        this.pixelStride = pixelStride;
    }

    void unbind() { buffer = null; }

    int sample(float x, float y) {
        if (buffer == null || !Float.isFinite(x) || !Float.isFinite(y)) {
            throw new IllegalArgumentException("Invalid Y plane sample");
        }
        float safeX = Math.max(0f, Math.min(width - 1f, x));
        float safeY = Math.max(0f, Math.min(height - 1f, y));
        int x0 = (int) safeX;
        int y0 = (int) safeY;
        int x1 = Math.min(width - 1, x0 + 1);
        int y1 = Math.min(height - 1, y0 + 1);
        float fx = safeX - x0;
        float fy = safeY - y0;
        int p00 = buffer.get(baseOffset + y0 * rowStride + x0 * pixelStride) & 0xff;
        int p10 = buffer.get(baseOffset + y0 * rowStride + x1 * pixelStride) & 0xff;
        int p01 = buffer.get(baseOffset + y1 * rowStride + x0 * pixelStride) & 0xff;
        int p11 = buffer.get(baseOffset + y1 * rowStride + x1 * pixelStride) & 0xff;
        float top = p00 + (p10 - p00) * fx;
        float bottom = p01 + (p11 - p01) * fx;
        return Math.max(0, Math.min(255, Math.round(top + (bottom - top) * fy)));
    }
}
