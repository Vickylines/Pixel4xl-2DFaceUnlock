package com.yusd.pixel2dface;

import static com.yusd.pixel2dface.UnlockFaceAnimationView.FAILURE;
import static com.yusd.pixel2dface.UnlockFaceAnimationView.SUCCESS;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;

/** Shared, density-independent artwork for the chooser and native lock screen. */
final class LockscreenStyleRenderer {
    static final long SUCCESS_MORPH_MS = 340L;
    static final long FAILURE_MORPH_MS = 300L;
    private static final int WHITE = 0xfff0f7ff;
    private static final int BLUE = 0xffa9d9ff;
    private static final int GREEN = 0xffa5edcf;
    private static final int WARM = 0xffffbaaa;
    private static final int INK = 0xff0b1320;
    private static final double TAU = Math.PI * 2;
    private static final float FOCUS_RING_RADIUS = 23.5f;
    private static final float FOCUS_STROKE = 2.15f;

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

    void draw(Canvas canvas, int state, float phase, float progress, boolean animated) {
        float faceMorph = smooth(0, .83f, progress);
        long duration = state == FAILURE ? FAILURE_MORPH_MS : SUCCESS_MORPH_MS;
        // Continue from the last scanning frame at its speed, then decelerate to rest.
        float travel = progress * (1 - .5f * progress);
        float motionPhase = animated
                ? phase + travel * duration / UnlockFaceAnimationView.SCAN_CYCLE_MS : 0;
        float scanningLight = 1 - smooth(.12f, .92f, progress);
        int outcome = state == FAILURE ? WARM : GREEN;
        float resultColor = smooth(.04f, .78f, progress);
        int color = blend(WHITE, outcome, resultColor);
        int accent = blend(BLUE, outcome, resultColor);
        int saved = canvas.save();
        if (state == FAILURE && animated) {
            float shake = 1.4f * (float) (Math.sin(progress * TAU) * Math.sin(progress * Math.PI));
            canvas.translate(shake, 0);
        }
        drawFrame(canvas, state, motionPhase, progress, scanningLight, color, accent);
        drawFace(canvas, state, motionPhase, faceMorph, color);
        canvas.restoreToCount(saved);
    }

    private void drawFrame(Canvas canvas, int state, float phase, float progress,
            float scanningLight, int color, int accent) {
        float roundness = smooth(.22f, .88f, progress);
        if (roundness == 1) {
            // Draw one closed contour once the arcs meet to avoid overlapping end caps.
            stroke.rewind();
            stroke.addCircle(0, 0, FOCUS_RING_RADIUS, Path.Direction.CW);
            paintLine(canvas, stroke, color, FOCUS_STROKE, 1);
            return;
        }

        for (int quarter = 0; quarter < 4; quarter++) {
            boolean firstPair = quarter % 2 == 0;
            float catchLight = .5f + .5f * (float) Math.cos((phase - quarter * .25f) * TAU);
            float settle = firstPair ? smooth(.03f, .52f, progress) : smooth(.14f, .64f, progress);
            float inset = mix(.80f * catchLight, state == FAILURE ? -.85f : 1.55f, settle);
            float half = mix(24 - inset, FOCUS_RING_RADIUS, roundness);
            float radius = mix(10, half, roundness);
            float tangent = radius - half;
            // The opening shrinks with the straight sections as the corner becomes a quarter circle.
            float tip = tangent * (10f / 14f);
            float farEdge = tangent + radius;
            corner.rewind();
            corner.moveTo(tip, -half);
            corner.lineTo(tangent, -half);
            corner.arcTo(-half, -half, farEdge, farEdge, -90, -90, false);
            corner.lineTo(-half, tip);

            int saved = canvas.save();
            canvas.rotate(quarter * 90);
            float reflection = scanningLight * (1 - roundness);
            int edge = blend(color, accent, .34f * reflection * catchLight);
            paintLine(canvas, corner, edge, FOCUS_STROKE, .90f + .10f * settle);
            paintHighlight(canvas, corner, cornerLight, 1, reflection * (.20f + .73f * catchLight));
            canvas.restoreToCount(saved);
        }
    }

    private void drawFace(Canvas canvas, int state, float phase, float resolved, int color) {
        float success = state == SUCCESS ? resolved : 0;
        float failure = state == FAILURE ? resolved : 0;
        float details = 1 - smooth(0, .68f, success);
        float blink = smooth(.30f, .34f, phase) * (1 - smooth(.34f, .39f, phase));
        float eyeHalfHeight = mix(1.65f, .50f, blink * (1 - resolved)) * (1 - success);
        float eyeY = -6.05f + .60f * success;
        stroke.rewind();
        stroke.moveTo(-7.2f, eyeY - eyeHalfHeight);
        stroke.lineTo(-7.2f, eyeY + eyeHalfHeight);
        stroke.moveTo(7.2f, eyeY - eyeHalfHeight);
        stroke.lineTo(7.2f, eyeY + eyeHalfHeight);
        paintLine(canvas, stroke, color, 2.3f, details);

        stroke.rewind();
        stroke.moveTo(.7f, -5.8f);
        stroke.lineTo(.7f, 1.5f);
        stroke.quadTo(.7f, 3.6f, -1.5f, 3.6f);
        stroke.lineTo(-2.7f, 3.6f);
        paintLine(canvas, stroke, color, 2.0f, details);

        // The smile itself becomes the check, with matching two-cubic topology.
        // This stays connected instead of cross-fading a separate success symbol.
        stroke.rewind();
        float mouthEdge = 8 + 1.8f * failure;
        float mouthShoulder = 10.5f - 2.3f * failure;
        float mouthCenter = 11.5f - 4.2f * failure;
        stroke.moveTo(mix(-8, -10, success), mix(mouthEdge, .5f, success));
        stroke.cubicTo(
                mix(-6.2f, -8.5f, success), mix(mouthShoulder, 2, success),
                mix(-3.2f, -6.8f, success), mix(mouthCenter, 4, success),
                mix(0, -4.8f, success), mix(mouthCenter, 6, success));
        stroke.cubicTo(
                mix(3.2f, -1, success), mix(mouthCenter, 2.2f, success),
                mix(6.2f, 5, success), mix(mouthShoulder, -3.8f, success),
                mix(8, 11, success), mix(mouthEdge, -9.2f, success));
        paintLine(canvas, stroke, color, mix(2.1f, 2.7f, success), 1);
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
