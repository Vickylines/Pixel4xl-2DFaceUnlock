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
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.animation.PathInterpolator;

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
    private static final PathInterpolator SETTLE = new PathInterpolator(.22f, 1, .36f, 1);

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path face = new Path();
    private final Path corners = new Path();
    private final Path stroke = new Path();
    private final Path segment = new Path();
    private final Path tile = new Path();
    private final PathMeasure perimeter;
    private final RectF bounds = new RectF();
    private final Matrix lightPosition = new Matrix();
    private final LinearGradient surface = new LinearGradient(-26, -26, 26, 26,
            new int[]{0xf0222d3e, 0xf0101824}, null, Shader.TileMode.CLAMP);
    private final LinearGradient light = new LinearGradient(0, -10, 0, 10,
            new int[]{0x00a9d9ff, 0x38c4e7ff, 0x00a9d9ff}, null, Shader.TileMode.CLAMP);

    LockscreenStyleRenderer() {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        tile.addRoundRect(-26, -26, 26, 26, 17, 17, Path.Direction.CW);
        perimeter = new PathMeasure(tile, true);

        // A rounded face silhouette reads at lock-screen size without a filled mask.
        face.moveTo(-17, -6);
        face.lineTo(-17, 3);
        face.cubicTo(-17, 15, -9, 22, 0, 22);
        face.cubicTo(9, 22, 17, 15, 17, 3);
        face.lineTo(17, -6);
        face.moveTo(-15, -13);
        face.cubicTo(-10, -23, 10, -23, 15, -13);

        corners.moveTo(-10, -24);
        corners.lineTo(-14, -24);
        corners.cubicTo(-21, -24, -24, -21, -24, -14);
        corners.lineTo(-24, -10);
        corners.moveTo(10, -24);
        corners.lineTo(14, -24);
        corners.cubicTo(21, -24, 24, -21, 24, -14);
        corners.lineTo(24, -10);
        corners.moveTo(-24, 10);
        corners.lineTo(-24, 14);
        corners.cubicTo(-24, 21, -21, 24, -14, 24);
        corners.lineTo(-10, 24);
        corners.moveTo(24, 10);
        corners.lineTo(24, 14);
        corners.cubicTo(24, 21, 21, 24, 14, 24);
        corners.lineTo(10, 24);
    }

    void draw(Canvas canvas, int style, int state, float phase, float progress) {
        float resolved = ease(progress);
        int color = blend(WHITE, state == FAILURE ? WARM : GREEN, resolved);
        int saved = canvas.save();
        if (state == FAILURE) {
            // One brief lateral response, ending with a readable, still face.
            float shake = (float) Math.sin(progress * TAU) * (1 - progress) * 1.7f;
            canvas.translate(shake, 0);
        }
        switch (style) {
            case AnimationStyle.GLOW:
                curtain(canvas, state, phase, resolved, color);
                break;
            case AnimationStyle.ORBIT:
                orbit(canvas, state, phase, resolved, color);
                break;
            case AnimationStyle.MINIMAL:
                outline(canvas, state, phase, resolved, color);
                break;
            case AnimationStyle.DOTS:
                focus(canvas, state, phase, resolved, color);
                break;
            default:
                throw new IllegalArgumentException("Unknown lock-screen style: " + style);
        }
        canvas.restoreToCount(saved);
    }

    private void curtain(Canvas canvas, int state, float phase, float resolved, int color) {
        fill.setShader(surface);
        fill.setAlpha(255);
        canvas.drawPath(tile, fill);
        fill.setShader(null);
        paintLine(canvas, tile, blend(BLUE, color, resolved), .9f, .35f);

        float sweep = (float) Math.sin(phase * TAU);
        int saved = canvas.save();
        canvas.clipPath(tile);
        lightPosition.setTranslate(0, 24 * sweep * (1 - resolved));
        light.setLocalMatrix(lightPosition);
        fill.setShader(light);
        fill.setAlpha(alpha(1 - resolved));
        canvas.drawRect(-26, -26, 26, 26, fill);
        fill.setShader(null);
        canvas.restoreToCount(saved);

        // The edge highlight follows the same rounded outline on every frame.
        perimeterSegment(canvas, (phase + .10f * resolved) % 1, .19f,
                BLUE, 1.5f, .85f * (1 - resolved));
        glyph(canvas, state, phase, resolved, color);
    }

    private void orbit(Canvas canvas, int state, float phase, float resolved, int color) {
        float radius = mix(27, 25, resolved);
        bounds.set(-radius, -radius, radius, radius);
        line.setColor(tint(INK, .50f));
        line.setStrokeWidth(3.8f);
        canvas.drawOval(bounds, line);
        line.setColor(tint(color, .24f + .35f * resolved));
        line.setStrokeWidth(1.5f);
        canvas.drawOval(bounds, line);

        float angle = phase * 360 - 90 + 36 * resolved;
        float arc = mix(64, 180, resolved);
        line.setColor(tint(blend(BLUE, color, resolved), .95f));
        line.setStrokeWidth(2.0f);
        canvas.drawArc(bounds, angle, arc, false, line);
        canvas.drawArc(bounds, angle + 180, arc, false, line);
        glyph(canvas, state, phase, resolved, color);
    }

    private void outline(Canvas canvas, int state, float phase, float resolved, int color) {
        float opacity = state == SUCCESS ? 1 - resolved : 1;
        float breath = .83f + .17f * (float) Math.cos(phase * TAU);
        paintLine(canvas, face, color, 1.9f, mix(breath, 1, resolved) * opacity);
        glyph(canvas, state, phase, resolved, color);
    }

    private void focus(Canvas canvas, int state, float phase, float resolved, int color) {
        float breath = .5f - .5f * (float) Math.cos(phase * TAU);
        float scale = mix(1 + .035f * breath, .91f, resolved);
        int saved = canvas.save();
        canvas.scale(scale, scale);
        paintLine(canvas, corners, color, 2.15f, 1);
        canvas.restoreToCount(saved);
        glyph(canvas, state, phase, resolved, color);
    }

    private void glyph(Canvas canvas, int state, float phase, float resolved, int color) {
        float success = state == SUCCESS ? resolved : 0;
        float failure = state == FAILURE ? resolved : 0;
        float details = 1 - smooth(0, .68f, success);
        // Keep the face stable while the surrounding scan moves.
        float blink = smooth(.73f, .77f, phase) * (1 - smooth(.77f, .81f, phase));
        float eyeHeight = mix(3.3f, 1.0f, blink) * (1 - success);
        stroke.rewind();
        stroke.moveTo(-7.2f, -7.7f);
        stroke.lineTo(-7.2f, -7.7f + eyeHeight);
        stroke.moveTo(7.2f, -7.7f);
        stroke.lineTo(7.2f, -7.7f + eyeHeight);
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
        stroke.moveTo(mix(-8.0f, -10, success), mix(8.0f, .5f, success));
        stroke.cubicTo(
                mix(-6.2f, -8.5f, success), mix(10.5f - failure * 2.5f, 2.0f, success),
                mix(-3.2f, -6.8f, success), mix(11.5f - failure * 3.5f, 4.0f, success),
                mix(0, -4.8f, success), mix(11.5f - failure * 3.5f, 6.0f, success));
        stroke.cubicTo(
                mix(3.2f, -1.0f, success), mix(11.5f - failure * 3.5f, 2.2f, success),
                mix(6.2f, 5.0f, success), mix(10.5f - failure * 2.5f, -3.8f, success),
                mix(8.0f, 11, success), mix(8.0f, -9.2f, success));
        paintLine(canvas, stroke, color, mix(2.1f, 2.7f, success), 1);
    }

    private void perimeterSegment(Canvas canvas, float start, float length, int color,
            float width, float opacity) {
        segment.rewind();
        float distance = perimeter.getLength();
        float end = start + length;
        perimeter.getSegment(start * distance, Math.min(1, end) * distance, segment, true);
        if (end > 1) perimeter.getSegment(0, (end - 1) * distance, segment, true);
        paintLine(canvas, segment, color, width, opacity);
    }

    private void paintLine(Canvas canvas, Path path, int color, float width, float opacity) {
        // A narrow shadow keeps unfilled styles readable against light wallpaper.
        line.setColor(tint(INK, .48f * opacity));
        line.setStrokeWidth(width + 1.25f);
        canvas.drawPath(path, line);
        line.setColor(tint(color, opacity));
        line.setStrokeWidth(width);
        canvas.drawPath(path, line);
    }

    private static float ease(float value) {
        return SETTLE.getInterpolation(value);
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
