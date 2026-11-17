package com.yusd.pixel2dface;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;

/** The four-corner scan for the Focus style. */
final class LockscreenStyleRenderer {
    private static final int WHITE = 0xfff0f7ff;
    private static final int BLUE = 0xffa9d9ff;
    private static final int INK = 0xff0b1320;
    private static final double TAU = Math.PI * 2;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path corner = new Path();
    private final Path stroke = new Path();
    private final LinearGradient cornerLight = new LinearGradient(-24, -24, -12, -12,
            new int[]{0xfff0f7ff, 0xcbc6e7ff, 0x00a9d9ff},
            new float[]{0, .26f, 1}, Shader.TileMode.CLAMP);

    LockscreenStyleRenderer() {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
    }

    void draw(Canvas canvas, float phase, float frameOpacity, float faceOpacity) {
        for (int quarter = 0; quarter < 4; quarter++) {
            float catchLight = .5f + .5f * (float) Math.cos((phase - quarter * .25f) * TAU);
            float half = 24 - .80f * catchLight;
            float tangent = 10 - half;
            float tip = tangent * (10f / 14f);
            float farEdge = tangent + 10;
            corner.rewind();
            corner.moveTo(tip, -half);
            corner.lineTo(tangent, -half);
            corner.arcTo(-half, -half, farEdge, farEdge, -90, -90, false);
            corner.lineTo(-half, tip);

            int saved = canvas.save();
            canvas.rotate(quarter * 90);
            paintLine(canvas, corner, blend(WHITE, BLUE, .34f * catchLight),
                    2.15f, .90f * frameOpacity);
            line.setShader(cornerLight);
            line.setAlpha(Math.round(255 * frameOpacity * (.20f + .73f * catchLight)));
            line.setStrokeWidth(1);
            canvas.drawPath(corner, line);
            line.setShader(null);
            canvas.restoreToCount(saved);
        }

        if (faceOpacity > 0) drawFace(canvas, phase, faceOpacity);
    }

    private void drawFace(Canvas canvas, float phase, float opacity) {
        float blink = smooth(.30f, .34f, phase) * (1 - smooth(.34f, .39f, phase));
        float eyeHalfHeight = mix(1.65f, .50f, blink);
        float eyeY = -6.05f;
        stroke.rewind();
        stroke.moveTo(-7.2f, eyeY - eyeHalfHeight);
        stroke.lineTo(-7.2f, eyeY + eyeHalfHeight);
        stroke.moveTo(7.2f, eyeY - eyeHalfHeight);
        stroke.lineTo(7.2f, eyeY + eyeHalfHeight);
        paintLine(canvas, stroke, WHITE, 2.3f, opacity);

        stroke.rewind();
        stroke.moveTo(.7f, -5.8f);
        stroke.lineTo(.7f, 1.5f);
        stroke.quadTo(.7f, 3.6f, -1.5f, 3.6f);
        stroke.lineTo(-2.7f, 3.6f);
        paintLine(canvas, stroke, WHITE, 2f, opacity);

        stroke.rewind();
        stroke.moveTo(-8, 8);
        stroke.cubicTo(-6.2f, 10.5f, -3.2f, 11.5f, 0, 11.5f);
        stroke.cubicTo(3.2f, 11.5f, 6.2f, 10.5f, 8, 8);
        paintLine(canvas, stroke, WHITE, 2.1f, opacity);
    }

    private void paintLine(Canvas canvas, Path path, int color, float width, float opacity) {
        // A narrow shadow keeps unfilled styles readable against light wallpaper.
        setLine(INK, width + 1.35f, .65f * opacity);
        canvas.drawPath(path, line);
        setLine(color, width, opacity);
        canvas.drawPath(path, line);
    }

    private void setLine(int color, float width, float opacity) {
        line.setColor((color & 0x00ffffff) | (Math.round(255 * opacity) << 24));
        line.setStrokeWidth(width);
    }

    private static float smooth(float start, float end, float value) {
        float t = Math.max(0, Math.min(1, (value - start) / (end - start)));
        return t * t * (3 - 2 * t);
    }

    private static float mix(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    private static int blend(int from, int to, float amount) {
        return Color.rgb(Math.round(mix(Color.red(from), Color.red(to), amount)),
                Math.round(mix(Color.green(from), Color.green(to), amount)),
                Math.round(mix(Color.blue(from), Color.blue(to), amount)));
    }
}
