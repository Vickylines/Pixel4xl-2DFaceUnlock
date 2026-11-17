package com.yusd.pixel2dface;

import static com.yusd.pixel2dface.UnlockFaceAnimationView.FAILURE;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;

/** Shared artwork for the gallery and the native keyguard. */
final class LockscreenStyleRenderer {
    static final long SUCCESS_MORPH_MS = 400L;
    static final long FAILURE_MORPH_MS = 340L;
    private static final int PEARL = 0xfff1f8ff;
    private static final int GREEN = 0xffa5edcf;
    private static final int WARM = 0xffffbaaa;
    private static final int INK = 0xff0b1320;

    private final Paint line = strokePaint();
    private final Path outlineFrame = new Path();
    private final Path focusFrame = new Path();
    private final FacePortraitRenderer portrait = new FacePortraitRenderer();

    LockscreenStyleRenderer() {
        outlineFrame.moveTo(-24.5f, -7.5f);
        outlineFrame.lineTo(-24.5f, 5);
        outlineFrame.cubicTo(-24.5f, 19.3f, -12.6f, 29.5f, 0, 29.5f);
        outlineFrame.cubicTo(12.6f, 29.5f, 24.5f, 19.3f, 24.5f, 5);
        outlineFrame.lineTo(24.5f, -7.5f);
        outlineFrame.moveTo(-21, -18);
        outlineFrame.cubicTo(-13.8f, -31.8f, 13.8f, -31.8f, 21, -18);

        Path corner = new Path();
        corner.moveTo(-10, -24);
        corner.lineTo(-13, -24);
        corner.arcTo(-24, -24, -2, -2, -90, -90, false);
        corner.lineTo(-24, -10);
        Matrix quarterTurn = new Matrix();
        for (int quarter = 0; quarter < 4; quarter++) {
            quarterTurn.setRotate(quarter * 90);
            focusFrame.addPath(corner, quarterTurn);
        }
    }

    void draw(Canvas canvas, int style, int state, float phase, float progress, boolean animated) {
        boolean focus = style == AnimationStyle.FOCUS;
        float frameOpacity = 1 - smooth(.08f, .64f, progress);
        if (frameOpacity > 0) {
            stroke(canvas, line, focus ? focusFrame : outlineFrame, PEARL,
                    focus ? 1.9f : 1.65f, .59f * frameOpacity);
        }

        int outcome = state == FAILURE ? WARM : GREEN;
        int color = blend(PEARL, outcome, smooth(.09f, .82f, progress));
        portrait.draw(canvas, focus, state, animated ? phase : 0, progress, color);
    }

    static Paint strokePaint() {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        return paint;
    }

    static void stroke(Canvas canvas, Paint paint, Path path, int color, float width,
            float opacity) {
        paint.setColor(withAlpha(INK, .68f * opacity));
        paint.setStrokeWidth(width + 1.1f);
        canvas.drawPath(path, paint);
        paint.setColor(withAlpha(color, opacity));
        paint.setStrokeWidth(width);
        canvas.drawPath(path, paint);
    }

    static float smooth(float start, float end, float value) {
        float t = Math.max(0, Math.min(1, (value - start) / (end - start)));
        return t * t * (3 - 2 * t);
    }

    static float mix(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    private static int withAlpha(int color, float opacity) {
        return (color & 0x00ffffff) | (Math.round(255 * opacity) << 24);
    }

    private static int blend(int from, int to, float amount) {
        return Color.rgb(Math.round(mix(Color.red(from), Color.red(to), amount)),
                Math.round(mix(Color.green(from), Color.green(to), amount)),
                Math.round(mix(Color.blue(from), Color.blue(to), amount)));
    }
}
