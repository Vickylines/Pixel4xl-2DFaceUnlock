package com.yusd.pixel2dface;

import static com.yusd.pixel2dface.UnlockFaceAnimationView.FAILURE;
import static com.yusd.pixel2dface.UnlockFaceAnimationView.SEARCHING;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;

/** Shared, density-independent artwork for the chooser and native lock screen. */
final class LockscreenStyleRenderer {
    static final long SUCCESS_MORPH_MS = 360L;
    static final long FAILURE_MORPH_MS = 340L;
    private static final int WHITE = 0xfff0f7ff;
    private static final int BLUE = 0xffa9d9ff;
    private static final int GREEN = 0xffa5edcf;
    private static final int WARM = 0xffffbaaa;
    private static final int INK = 0xff0b1320;
    private static final double TAU = Math.PI * 2;
    private static final float FOCUS_RING_RADIUS = 23.5f;
    private static final float FOCUS_STROKE = 2.15f;
    private static final float RESULT_STROKE = 1.98f;
    private static final float CHECK_START = .10f;
    private static final float CROSS_START = .24f;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path corner = new Path();
    private final Path frame = new Path();
    private final Path stroke = new Path();
    private final Matrix quarterTurn = new Matrix();
    private final LinearGradient cornerLight = new LinearGradient(-24, -24, -12, -12,
            new int[]{0xfff0f7ff, 0xcbc6e7ff, 0x00a9d9ff},
            new float[]{0, .26f, 1}, Shader.TileMode.CLAMP);

    LockscreenStyleRenderer() {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
    }

    void draw(Canvas canvas, int state, float phase, float progress, boolean animated) {
        boolean failed = state == FAILURE;
        long duration = failed ? FAILURE_MORPH_MS : SUCCESS_MORPH_MS;
        // Continue from the last scanning frame at its speed, then decelerate to rest.
        float travel = progress * (1 - .5f * progress);
        float motionPhase = animated
                ? phase + travel * duration / UnlockFaceAnimationView.SCAN_CYCLE_MS : 0;
        // The mark uses the same progress as the gap so both finish together.
        float joining = smooth(.02f, .97f, progress);
        int outcome = failed ? WARM : GREEN;
        float resultColor = smooth(0, failed ? .86f : .92f, joining);
        int color = blend(WHITE, outcome, resultColor);
        int accent = blend(BLUE, outcome, resultColor);
        drawFrame(canvas, failed, motionPhase, progress, joining, color, accent);
        drawFace(canvas, motionPhase, joining, color);
        float start = failed ? CROSS_START : CHECK_START;
        if (state != SEARCHING && joining > start) {
            float mark = (joining - start) / (1 - start);
            if (failed) {
                setLine(INK, ResultGlyphs.CROSS_WIDTH + 1.35f, .65f);
                ResultGlyphs.drawCross(canvas, line, 0, 0, 1, mark);
                setLine(color, ResultGlyphs.CROSS_WIDTH, 1);
                ResultGlyphs.drawCross(canvas, line, 0, 0, 1, mark);
            } else {
                ResultGlyphs.traceCheck(stroke, 0, 0, 1, mark);
                paintLine(canvas, stroke, color, ResultGlyphs.CHECK_WIDTH, 1);
            }
        }
    }

    private void drawFrame(Canvas canvas, boolean failed, float phase, float progress,
            float joining, int color, int accent) {
        float rounding = smooth(.02f, .81f, progress);
        float width = mix(FOCUS_STROKE, RESULT_STROKE, joining);
        frame.rewind();
        if (joining == 1) {
            frame.addCircle(0, 0, FOCUS_RING_RADIUS, Path.Direction.CW);
            paintLine(canvas, frame, color, width, 1);
            return;
        }

        float align = smooth(.04f, .49f, progress);
        float reflection = (1 - smooth(.06f, .54f, progress)) * (1 - rounding);
        float give = smooth(0, .23f, progress) * (1 - smooth(.28f, .72f, progress));
        float spread = (failed ? 1.22f : -.90f) * give;
        for (int quarter = 0; quarter < 4; quarter++) {
            float catchLight = .5f + .5f * (float) Math.cos((phase - quarter * .25f) * TAU);
            float scanInset = .80f * catchLight * (1 - align);
            float half = mix(24 - scanInset, FOCUS_RING_RADIUS, rounding) + spread;
            float radius = mix(10, half, rounding);
            float gap = (half - 10) * (10f / 14f) * (1 - joining);
            shapeCorner(half, radius, gap);

            if (reflection > 0) {
                int saved = canvas.save();
                canvas.rotate(quarter * 90);
                int edge = blend(color, accent, .34f * reflection * catchLight);
                paintLine(canvas, corner, edge, width, mix(.90f, 1, align));
                paintHighlight(canvas, corner, cornerLight, 1,
                        reflection * (.20f + .73f * catchLight));
                canvas.restoreToCount(saved);
            } else {
                quarterTurn.setRotate(quarter * 90);
                frame.addPath(corner, quarterTurn);
            }
        }
        // Paint the aligned arcs together so one end's shadow cannot cross another end.
        if (reflection == 0) paintLine(canvas, frame, color, width, 1);
    }

    private void shapeCorner(float half, float radius, float gap) {
        float tangent = radius - half;
        float farEdge = tangent + radius;
        corner.rewind();
        if (gap <= -tangent) {
            corner.moveTo(-gap, -half);
            corner.lineTo(tangent, -half);
            corner.arcTo(-half, -half, farEdge, farEdge, -90, -90, false);
            corner.lineTo(-half, -gap);
        } else {
            // When the straight section ends, keep closing by trimming the arc itself.
            float trim = (float) Math.toDegrees((gap + tangent) / radius);
            corner.arcTo(-half, -half, farEdge, farEdge, -90 - trim, -90 + 2 * trim, true);
        }
    }

    private void drawFace(Canvas canvas, float phase, float joining, int color) {
        float eyes = smooth(.01f, .25f, joining);
        float nose = smooth(.025f, .32f, joining);
        float blink = smooth(.30f, .34f, phase) * (1 - smooth(.34f, .39f, phase));
        float open = mix(1.65f, .50f, blink * (1 - joining));
        float eyeHalfHeight = mix(open, .50f, eyes);
        float eyeY = -6.05f + .65f * eyes;
        stroke.rewind();
        stroke.moveTo(-7.2f, eyeY - eyeHalfHeight);
        stroke.lineTo(-7.2f, eyeY + eyeHalfHeight);
        stroke.moveTo(7.2f, eyeY - eyeHalfHeight);
        stroke.lineTo(7.2f, eyeY + eyeHalfHeight);
        paintLine(canvas, stroke, color, 2.3f, 1 - eyes);

        stroke.rewind();
        stroke.moveTo(.7f, -5.8f);
        stroke.lineTo(.7f, 1.5f);
        stroke.quadTo(.7f, 3.6f, -1.5f, 3.6f);
        stroke.lineTo(-2.7f, 3.6f);
        paintLine(canvas, stroke, color, 2.0f, 1 - nose);

        float mouthOpacity = 1 - smooth(.01f, .23f, joining);
        if (mouthOpacity > 0) {
            stroke.rewind();
            stroke.moveTo(-8, 8);
            stroke.cubicTo(-6.2f, 10.5f, -3.2f, 11.5f, 0, 11.5f);
            stroke.cubicTo(3.2f, 11.5f, 6.2f, 10.5f, 8, 8);
            paintLine(canvas, stroke, color, 2.1f, mouthOpacity);
        }
    }

    private void paintLine(Canvas canvas, Path path, int color, float width, float opacity) {
        // A narrow shadow keeps unfilled styles readable against light wallpaper.
        setLine(INK, width + 1.35f, .65f * opacity);
        canvas.drawPath(path, line);
        setLine(color, width, opacity);
        canvas.drawPath(path, line);
    }

    private void paintHighlight(Canvas canvas, Path path, Shader shader, float width, float opacity) {
        line.setShader(shader);
        line.setAlpha(alpha(opacity));
        line.setStrokeWidth(width);
        canvas.drawPath(path, line);
        line.setShader(null);
    }

    private void setLine(int color, float width, float opacity) {
        line.setColor(tint(color, opacity));
        line.setStrokeWidth(width);
    }

    private static float smooth(float start, float end, float value) {
        float t = Math.max(0, Math.min(1, (value - start) / (end - start)));
        return t * t * (3 - 2 * t);
    }

    private static float mix(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    private static int alpha(float opacity) {
        return Math.round(255 * opacity);
    }

    private static int tint(int color, float opacity) {
        return (color & 0x00ffffff) | (alpha(opacity) << 24);
    }

    private static int blend(int from, int to, float amount) {
        return Color.rgb(Math.round(mix(Color.red(from), Color.red(to), amount)),
                Math.round(mix(Color.green(from), Color.green(to), amount)),
                Math.round(mix(Color.blue(from), Color.blue(to), amount)));
    }
}
