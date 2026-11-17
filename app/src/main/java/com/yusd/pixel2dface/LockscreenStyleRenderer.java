package com.yusd.pixel2dface;

import static com.yusd.pixel2dface.UnlockFaceAnimationView.FAILURE;
import static com.yusd.pixel2dface.UnlockFaceAnimationView.SUCCESS;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.Shader;
import android.graphics.SweepGradient;

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
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path jaw = new Path();
    private final Path forehead = new Path();
    private final Path corner = new Path();
    private final Path stroke = new Path();
    private final Path trimmedJaw = new Path();
    private final Path tile = new Path();
    private final Path tileInset = new Path();
    private final PathMeasure jawMeasure;
    private final Matrix lightPosition = new Matrix();
    private final LinearGradient surface = new LinearGradient(-21, -27, 19, 28,
            new int[]{0xf0354358, 0xf01a2433, 0xf0111926},
            new float[]{0, .48f, 1}, Shader.TileMode.CLAMP);
    private final LinearGradient glassRim = new LinearGradient(-25, -24, 24, 25,
            new int[]{0xbadcf1ff, 0x44b2c7e0, 0x695b7696}, null, Shader.TileMode.CLAMP);
    private final LinearGradient light = new LinearGradient(0, -12, 0, 11,
            new int[]{0x009ac6ff, 0x2a9ac6ff, 0x78e5f6ff, 0x38abd6ff, 0x009ac6ff},
            new float[]{0, .40f, .57f, .66f, 1}, Shader.TileMode.CLAMP);
    private final SweepGradient orbitLight = new SweepGradient(0, 0,
            new int[]{0x00a9d9ff, 0x30a9d9ff, 0xc0a9d9ff, 0xfff0f7ff, 0x00a9d9ff,
                    0x00a9d9ff, 0x369eccf6, 0xaac3e5ff, 0x00a9d9ff, 0x00a9d9ff},
            new float[]{0, .08f, .20f, .246f, .275f, .53f, .62f, .695f, .72f, 1});
    private final SweepGradient contourLight = new SweepGradient(0, 0,
            new int[]{0x00a9d9ff, 0x54a9d9ff, 0xffe8f8ff, 0x00a9d9ff, 0x00a9d9ff},
            new float[]{0, .105f, .197f, .27f, 1});
    private final LinearGradient cornerLight = new LinearGradient(-24, -24, -12, -12,
            new int[]{0xfff0f7ff, 0xcbc6e7ff, 0x00a9d9ff},
            new float[]{0, .26f, 1}, Shader.TileMode.CLAMP);

    LockscreenStyleRenderer() {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        tile.addRoundRect(-26, -26, 26, 26, 17, 17, Path.Direction.CW);
        tileInset.addRoundRect(-24.4f, -24.4f, 24.4f, 24.4f, 15.4f, 15.4f, Path.Direction.CW);

        // A rounded face silhouette reads at lock-screen size without a filled mask.
        jaw.moveTo(-23, -7.2f);
        jaw.lineTo(-23, 4.2f);
        jaw.cubicTo(-23, 18, -12, 27.8f, 0, 27.8f);
        jaw.cubicTo(12, 27.8f, 23, 18, 23, 4.2f);
        jaw.lineTo(23, -7.2f);
        jawMeasure = new PathMeasure(jaw, false);
        forehead.moveTo(-19.8f, -17.2f);
        forehead.cubicTo(-13, -29.5f, 13, -29.5f, 19.8f, -17.2f);
    }

    void draw(Canvas canvas, int style, int state, float phase, float progress) {
        float faceMorph = smooth(0, .83f, progress);
        long duration = state == FAILURE ? FAILURE_MORPH_MS : SUCCESS_MORPH_MS;
        // Continue from the last scanning frame at its speed, then decelerate to rest.
        float travel = progress * (1 - .5f * progress);
        float motionPhase = phase + travel * duration / UnlockFaceAnimationView.SCAN_CYCLE_MS;
        float scanningLight = 1 - smooth(.12f, .92f, progress);
        int outcome = state == FAILURE ? WARM : GREEN;
        float resultColor = smooth(.04f, .78f, progress);
        int color = blend(WHITE, outcome, resultColor);
        int accent = blend(BLUE, outcome, resultColor);
        int saved = canvas.save();
        if (state == FAILURE) {
            float shake = 1.4f * (float) (Math.sin(progress * TAU) * Math.sin(progress * Math.PI));
            canvas.translate(shake, 0);
        }
        switch (style) {
            case AnimationStyle.GLOW:
                drawCurtain(canvas, motionPhase, progress, scanningLight, accent);
                break;
            case AnimationStyle.ORBIT:
                drawOrbit(canvas, state, motionPhase, progress, scanningLight, accent);
                break;
            case AnimationStyle.MINIMAL:
                drawOutline(canvas, state, motionPhase, progress, scanningLight, color);
                break;
            case AnimationStyle.DOTS:
                drawFocus(canvas, state, motionPhase, progress, scanningLight, color, accent);
                break;
            default:
                throw new IllegalArgumentException("Unknown lock-screen style: " + style);
        }
        drawFace(canvas, state, motionPhase, faceMorph, color);
        canvas.restoreToCount(saved);
    }

    private void drawCurtain(Canvas canvas, float phase, float progress, float scanningLight,
            int accent) {
        fill.setShader(surface);
        fill.setAlpha(255);
        canvas.drawPath(tile, fill);
        fill.setShader(null);
        fill.setColor(tint(accent, .10f * smooth(.23f, .85f, progress)));
        canvas.drawPath(tile, fill);

        paintLine(canvas, tile, accent, .9f, .34f + .24f * progress);
        paintHighlight(canvas, tile, glassRim, 1.0f, .82f);
        setLine(WHITE, .56f, .13f);
        canvas.drawPath(tileInset, line);
        if (scanningLight > 0) {
            // One light hits the surface and its rim at the same point.
            float sweep = -.5f * (float) Math.cos(phase * TAU);
            lightPosition.setRotate(-16);
            lightPosition.postTranslate(0, 61 * sweep);
            light.setLocalMatrix(lightPosition);
            fill.setShader(light);
            fill.setAlpha(alpha(.76f * scanningLight));
            canvas.drawPath(tile, fill);
            fill.setShader(null);
            paintHighlight(canvas, tile, light, 1.8f, scanningLight);
        }
    }

    private void drawOrbit(Canvas canvas, int state, float phase, float progress,
            float scanningLight, int accent) {
        float joined = smooth(.13f, .88f, progress);
        float radius = mix(27, state == FAILURE ? 26 : 25, joined);
        stroke.rewind();
        stroke.addCircle(0, 0, radius - 2.55f, Path.Direction.CW);
        setLine(accent, .56f, .15f * scanningLight);
        canvas.drawPath(stroke, line);

        stroke.rewind();
        stroke.addCircle(0, 0, radius, Path.Direction.CW);
        float rimOpacity = mix(.31f, state == FAILURE ? .57f : .90f, joined);
        paintLine(canvas, stroke, accent, mix(1.45f, 1.95f, joined), rimOpacity);
        if (scanningLight > 0) {
            lightPosition.setRotate(phase * 360 - 144);
            orbitLight.setLocalMatrix(lightPosition);
            paintHighlight(canvas, stroke, orbitLight, 4.5f, .19f * scanningLight);
            paintHighlight(canvas, stroke, orbitLight, 1.85f, scanningLight);
        }
    }

    private void drawOutline(Canvas canvas, int state, float phase, float progress,
            float scanningLight, int color) {
        float gather = state == SUCCESS ? smooth(.02f, .83f, progress) : 0;
        float opacity = 1 - smooth(.22f, 1, gather);
        float browOpacity = 1 - smooth(0, .62f, gather);
        paintLine(canvas, forehead, color, 1.75f, .83f * browOpacity);

        Path outline = jaw;
        if (gather > 0) {
            float length = jawMeasure.getLength();
            float trim = .35f * gather * length;
            trimmedJaw.rewind();
            jawMeasure.getSegment(trim, length - trim, trimmedJaw, true);
            outline = trimmedJaw;
        }
        int saved = canvas.save();
        canvas.translate(0, -4.5f * gather);
        paintLine(canvas, outline, color, 1.9f, .90f * opacity);
        if (scanningLight > 0) {
            lightPosition.setRotate(phase * 360 - 155);
            contourLight.setLocalMatrix(lightPosition);
            paintHighlight(canvas, outline, contourLight, 3.25f, .26f * scanningLight * opacity);
            paintHighlight(canvas, outline, contourLight, 1.8f, .87f * scanningLight * opacity);
        }
        canvas.restoreToCount(saved);
        if (scanningLight > 0) {
            paintHighlight(canvas, forehead, contourLight, 1.7f, .85f * scanningLight * browOpacity);
        }
    }

    private void drawFocus(Canvas canvas, int state, float phase, float progress,
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
