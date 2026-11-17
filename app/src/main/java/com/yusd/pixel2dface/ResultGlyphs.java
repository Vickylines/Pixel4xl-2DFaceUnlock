package com.yusd.pixel2dface;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/** Check and cross strokes shared by Face Ring and Focus. */
final class ResultGlyphs {
    static final float CHECK_WIDTH = 2.8f;
    static final float CROSS_WIDTH = 2.7f;
    private static final float CORNER_PROGRESS = .34f;

    private ResultGlyphs() { }

    static void traceCheck(Path path, float cx, float cy, float scale, float progress) {
        float startX = cx - 8.1f * scale;
        float cornerX = cx - 1.9f * scale;
        float cornerY = cy + 6f * scale;
        float tipX = cx + 9.8f * scale;
        float tipY = cy - 7.7f * scale;

        path.reset();
        path.moveTo(startX, cy);
        if (progress < CORNER_PROGRESS) {
            float local = progress / CORNER_PROGRESS;
            path.lineTo(startX + (cornerX - startX) * local, cy + (cornerY - cy) * local);
        } else {
            path.lineTo(cornerX, cornerY);
            float local = (progress - CORNER_PROGRESS) / .66f;
            path.lineTo(cornerX + (tipX - cornerX) * local, cornerY + (tipY - cornerY) * local);
        }
    }

    static void drawCross(Canvas canvas, Paint paint, float cx, float cy, float scale,
            float progress) {
        float extent = 7.2f * scale * progress;
        canvas.drawLine(cx - extent, cy - extent, cx + extent, cy + extent, paint);
        canvas.drawLine(cx + extent, cy - extent, cx - extent, cy + extent, paint);
    }
}
