package com.yusd.pixel2dface;

import static com.yusd.pixel2dface.FocusRibbon.mix;

import android.graphics.Path;

/** The two written strokes shared by the ring and island confirmations. */
final class ResultGlyphs {
    // Relative to the centre of the finished check, for both full and compact artwork.
    private static final float START_X = -8.95f, START_Y = .85f;
    private static final float KNEE_X = -2.75f, KNEE_Y = 6.85f;
    private static final float TIP_X = 8.95f, TIP_Y = -6.85f;
    private static final float CORNER_PROGRESS = .34f;

    private ResultGlyphs() { }

    static void traceCheck(Path path, float scale, float progress) {
        float startX = START_X * scale, startY = START_Y * scale;
        float cornerX = KNEE_X * scale, cornerY = KNEE_Y * scale;
        float tipX = TIP_X * scale, tipY = TIP_Y * scale;

        path.reset();
        path.moveTo(startX, startY);
        if (progress < CORNER_PROGRESS) {
            float local = progress / CORNER_PROGRESS;
            path.lineTo(mix(startX, cornerX, local), mix(startY, cornerY, local));
        } else {
            path.lineTo(cornerX, cornerY);
            float local = (progress - CORNER_PROGRESS) / .66f;
            path.lineTo(cornerX + (tipX - cornerX) * local, cornerY + (tipY - cornerY) * local);
        }
    }

    static void traceSmileIntoCheck(Path path, float scale, float gather, float upstroke) {
        float leftX = START_X * scale, leftY = START_Y * scale;
        float kneeX = KNEE_X * scale, kneeY = KNEE_Y * scale;
        float endX = mix(7.5f, kneeX, gather), endY = mix(7.2f, kneeY, gather);
        path.rewind();
        path.moveTo(mix(-7.5f, leftX, gather), mix(7.2f, leftY, gather));
        // Straighten the smile into the short arm. Its moving endpoint becomes the elbow.
        path.cubicTo(mix(-3.8f, mix(leftX, kneeX, 1f / 3), gather),
                mix(12.3f, mix(leftY, kneeY, 1f / 3), gather),
                mix(3.8f, mix(leftX, kneeX, 2f / 3), gather),
                mix(12.3f, mix(leftY, kneeY, 2f / 3), gather), endX, endY);
        if (upstroke > 0) path.lineTo(mix(endX, TIP_X * scale, upstroke), mix(endY, TIP_Y * scale, upstroke));
    }

}
