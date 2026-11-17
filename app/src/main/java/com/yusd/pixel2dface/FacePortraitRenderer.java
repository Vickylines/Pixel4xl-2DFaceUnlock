package com.yusd.pixel2dface;

import static com.yusd.pixel2dface.LockscreenStyleRenderer.mix;
import static com.yusd.pixel2dface.LockscreenStyleRenderer.smooth;
import static com.yusd.pixel2dface.LockscreenStyleRenderer.stroke;
import static com.yusd.pixel2dface.UnlockFaceAnimationView.FAILURE;
import static com.yusd.pixel2dface.UnlockFaceAnimationView.SUCCESS;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/** Front-facing line art with matching contour and result paths. */
final class FacePortraitRenderer {
    private static final float QUARTER_CIRCLE = .55228475f;
    private static final float EYE_Y = -6.2f;

    // The face and circle share four cubic segments, starting at the crown.
    private static final float[] PORTRAIT_RIM = {
            0, -16.4f,
            8.6f, -16.4f, 14.5f, -10.5f, 14.2f, -1.1f,
            13.8f, 7.9f, 8.2f, 15.8f, 0, 16.9f,
            -8.2f, 15.8f, -13.8f, 7.9f, -14.2f, -1.1f,
            -14.5f, -10.5f, -8.6f, -16.4f, 0, -16.4f
    };
    private static final float[] RING = {
            0, -1,
            QUARTER_CIRCLE, -1, 1, -QUARTER_CIRCLE, 1, 0,
            1, QUARTER_CIRCLE, QUARTER_CIRCLE, 1, 0, 1,
            -QUARTER_CIRCLE, 1, -1, QUARTER_CIRCLE, -1, 0,
            -1, -QUARTER_CIRCLE, -QUARTER_CIRCLE, -1, 0, -1
    };
    private static final float[] SMILE = {
            -7.8f, 7.3f,
            -5.9f, 9.8f, -2.8f, 11, 0, 11.1f,
            2.8f, 11, 5.9f, 9.8f, 7.8f, 7.3f
    };
    private static final float[] CHECK = {
            -10, .5f,
            -8.4f, 2, -6.6f, 4.1f, -4.7f, 6,
            -.8f, 2.2f, 5.2f, -3.8f, 10.9f, -9.2f
    };
    private static final float[] FROWN = {
            -7.6f, 9.7f,
            -5.3f, 8, -2.7f, 7.2f, 0, 7.2f,
            2.7f, 7.2f, 5.3f, 8, 7.6f, 9.7f
    };

    private final Paint line = LockscreenStyleRenderer.strokePaint();
    private final Path rim = new Path();
    private final Path feature = new Path();
    private final Path nose = new Path();

    FacePortraitRenderer() {
        nose.moveTo(.4f, -5.9f);
        nose.cubicTo(.45f, -3.7f, .9f, .1f, .7f, 2.7f);
        nose.cubicTo(.55f, 4.1f, -1.6f, 4, -2.65f, 3.8f);
    }

    void draw(Canvas canvas, boolean focus, int state, float phase, float progress, int color) {
        float rounding = smooth(.08f, .94f, progress);
        float radius = focus ? 23.5f : 22f;
        if (rounding == 1) {
            rim.rewind();
            rim.addCircle(0, 0, radius, Path.Direction.CW);
        } else {
            morph(rim, PORTRAIT_RIM, RING, rounding, radius);
            rim.close();
        }
        stroke(canvas, line, rim, color, mix(1.06f, focus ? 2.15f : 1.85f, rounding),
                mix(.50f, 1, rounding));

        float details = state == SUCCESS ? 1 - smooth(.10f, .65f, progress) : 1;
        if (details > 0) {
            float blink = smooth(.33f, .37f, phase) * (1 - smooth(.37f, .41f, phase));
            float halfHeight = mix(1.9f, .55f, blink * (1 - smooth(0, .72f, progress)));
            feature.rewind();
            feature.moveTo(-6.7f, EYE_Y - halfHeight);
            feature.lineTo(-6.7f, EYE_Y + halfHeight);
            feature.moveTo(6.7f, EYE_Y - halfHeight);
            feature.lineTo(6.7f, EYE_Y + halfHeight);
            stroke(canvas, line, feature, color, 2.28f, details);
            stroke(canvas, line, nose, color, 1.83f, details);
        }

        float mark = smooth(.10f, .90f, progress);
        float[] resultMouth = state == SUCCESS ? CHECK : state == FAILURE ? FROWN : SMILE;
        morph(feature, SMILE, resultMouth, mark, 1);
        stroke(canvas, line, feature, color, state == SUCCESS ? mix(2, 2.55f, mark) : 2, 1);
    }

    private static void morph(Path path, float[] from, float[] to, float amount, float scale) {
        path.rewind();
        path.moveTo(mix(from[0], to[0] * scale, amount), mix(from[1], to[1] * scale, amount));
        for (int i = 2; i < from.length; i += 6) {
            path.cubicTo(mix(from[i], to[i] * scale, amount),
                    mix(from[i + 1], to[i + 1] * scale, amount),
                    mix(from[i + 2], to[i + 2] * scale, amount),
                    mix(from[i + 3], to[i + 3] * scale, amount),
                    mix(from[i + 4], to[i + 4] * scale, amount),
                    mix(from[i + 5], to[i + 5] * scale, amount));
        }
    }
}
