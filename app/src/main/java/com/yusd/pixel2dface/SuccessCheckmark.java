package com.yusd.pixel2dface;

import android.graphics.Path;

/** The success stroke originally used by the Face Ring style. */
final class SuccessCheckmark {
    static final float STROKE_WIDTH = 2.8f;
    private static final float CORNER_PROGRESS = .34f;

    private SuccessCheckmark() { }

    static void trace(Path path, float cx, float cy, float scale, float progress) {
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
}
