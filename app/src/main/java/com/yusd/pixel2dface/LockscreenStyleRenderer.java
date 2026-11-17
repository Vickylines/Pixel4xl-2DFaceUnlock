package com.yusd.pixel2dface;

import static com.yusd.pixel2dface.UnlockFaceAnimationView.FAILURE;
import static com.yusd.pixel2dface.UnlockFaceAnimationView.SUCCESS;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
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
    private static final float OUTLINE_RING_RADIUS = 22f;
    private static final float OUTLINE_RING_STROKE = 1.85f;
    private static final float QUARTER_CIRCLE_CONTROL = .55228475f;
    private static final float FOCUS_RING_RADIUS = 23.5f;
    private static final float FOCUS_STROKE = 2.15f;
    private static final float CORNER_BISECTOR = .70710678f;
    private static final float CONTOUR_LIGHT_PEAK = .028f;
    private static final RectF REFLECTION_BOUNDS = new RectF(-10, -6.25f, 10, 6.25f);

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reflectionPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path outline = new Path();
    private final Path focusOutline = new Path();
    private final Path[] focusCorners = {new Path(), new Path(), new Path(), new Path()};
    private final Path stroke = new Path();
    private final Matrix lightPosition = new Matrix();
    private final PathMeasure jawScan = new PathMeasure();
    private final PathMeasure browScan = new PathMeasure();
    private final float[] lightPoint = new float[2];
    private final float[] lightTangent = new float[2];
    private final float[] cornerElbow = new float[4];
    private final float[] cornerShine = new float[4];
    private final SweepGradient contourLight = new SweepGradient(0, 0,
            new int[]{0x00a9d9ff, 0xffe8f8ff, 0x54a9d9ff, 0x00a9d9ff, 0x00a9d9ff},
            new float[]{0, CONTOUR_LIGHT_PEAK, .11f, .23f, 1});
    private final LinearGradient cornerLight = new LinearGradient(-24, -24, -12, -12,
            new int[]{0xfff0f7ff, 0xcbc6e7ff, 0x00a9d9ff},
            new float[]{0, .26f, 1}, Shader.TileMode.CLAMP);
    private float outlineRoundness = -1;
    private float outlineGap;

    LockscreenStyleRenderer() {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
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
            case AnimationStyle.MINIMAL:
                drawOutline(canvas, motionPhase, progress, scanningLight, color);
                break;
            case AnimationStyle.FOCUS:
                drawFocus(canvas, state, motionPhase, progress, scanningLight, color, accent);
                break;
            default:
                throw new IllegalArgumentException("Unknown lock-screen style: " + style);
        }
        drawFace(canvas, state, motionPhase, faceMorph, color);
        canvas.restoreToCount(saved);
    }

    private void drawOutline(Canvas canvas, float phase, float progress, float scanningLight,
            int color) {
        float rounding = smooth(.08f, .9f, progress);
        if (rounding == 1) {
            drawResultRing(canvas, OUTLINE_RING_RADIUS, color, OUTLINE_RING_STROKE);
            return;
        }
        shapeOutline(rounding);
        paintLine(canvas, outline, color, mix(1.9f, OUTLINE_RING_STROKE, rounding),
                mix(.90f, 1, rounding));
        if (scanningLight > 0) {
            drawOutlineReflection(canvas, phase, scanningLight * (1 - rounding));
        }
    }

    private void drawOutlineReflection(Canvas canvas, float phase, float opacity) {
        float jawLength = jawScan.getLength();
        float browLength = browScan.getLength();
        float distance = ((phase + .73f) % 1) * (jawLength + browLength + 2 * outlineGap);
        PathMeasure section = jawScan;
        if (distance > jawLength) {
            distance -= jawLength + outlineGap;
            section = browScan;
        }
        float length = section.getLength();
        if (distance < 0 || distance > length) return;
        section.getPosTan(distance, lightPoint, lightTangent);
        float strength = opacity * smooth(0, 4, distance) * (1 - smooth(length - 4, length, distance));
        float angle = (float) Math.toDegrees(Math.atan2(lightPoint[1], lightPoint[0]));
        float tangent = (float) Math.toDegrees(Math.atan2(lightTangent[1], lightTangent[0]));
        drawSoftReflection(canvas, lightPoint[0], lightPoint[1], tangent, .82f * strength);
        lightPosition.setRotate(angle - 360 * CONTOUR_LIGHT_PEAK);
        contourLight.setLocalMatrix(lightPosition);
        paintHighlight(canvas, outline, contourLight, 1.65f, strength);
    }

    private void shapeOutline(float rounding) {
        if (rounding == outlineRoundness) return;
        outlineRoundness = rounding;
        float radius = OUTLINE_RING_RADIUS;
        float tangent = radius * QUARTER_CIRCLE_CONTROL;
        float cheek = mix(24.5f, radius, rounding);
        float tipY = mix(-7.5f, 0, rounding);
        float cheekY = mix(5, 0, rounding);
        float chin = mix(29.5f, radius, rounding);
        float jawControlX = mix(12.6f, tangent, rounding);
        float jawControlY = mix(19.3f, tangent, rounding);
        outline.rewind();
        outline.moveTo(-cheek, tipY);
        outline.lineTo(-cheek, cheekY);
        outline.cubicTo(-cheek, jawControlY, -jawControlX, chin, 0, chin);
        outline.cubicTo(jawControlX, chin, cheek, jawControlY, cheek, cheekY);
        outline.lineTo(cheek, tipY);

        // Matching quarter-curve control points let the two contours meet with a shared tangent.
        float temple = mix(21, radius, rounding);
        float templeY = mix(-18, 0, rounding);
        float crown = mix(-28.35f, -radius, rounding);
        float templeControlX = mix(17.4f, radius, rounding);
        float templeControlY = mix(-24.9f, -tangent, rounding);
        float crownControlX = mix(8.7f, tangent, rounding);
        // Draw the brow right to left so light can continue from the end of the jaw.
        outline.moveTo(temple, templeY);
        outline.cubicTo(templeControlX, templeControlY, crownControlX, crown, 0, crown);
        outline.cubicTo(-crownControlX, crown, -templeControlX, templeControlY, -temple, templeY);
        jawScan.setPath(outline, false);
        browScan.setPath(outline, false);
        browScan.nextContour();
        outlineGap = (float) Math.hypot(cheek - temple, tipY - templeY);
    }

    private void drawFocus(Canvas canvas, int state, float phase, float progress,
            float scanningLight, int color, int accent) {
        float roundness = smooth(.22f, .88f, progress);
        if (roundness == 1) {
            // Draw one closed contour once the arcs meet to avoid overlapping end caps.
            drawResultRing(canvas, FOCUS_RING_RADIUS, color, FOCUS_STROKE);
            return;
        }

        float reflection = scanningLight * (1 - roundness);
        focusOutline.rewind();
        for (int quarter = 0; quarter < focusCorners.length; quarter++) {
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
            Path corner = focusCorners[quarter];
            corner.rewind();
            corner.moveTo(tip, -half);
            corner.lineTo(tangent, -half);
            corner.arcTo(-half, -half, farEdge, farEdge, -90, -90, false);
            corner.lineTo(-half, tip);

            lightPosition.setRotate(quarter * 90);
            focusOutline.addPath(corner, lightPosition);
            cornerElbow[quarter] = tangent - radius * CORNER_BISECTOR;
            cornerShine[quarter] = catchLight;
        }

        int saved = canvas.save();
        canvas.rotate(8 * (float) Math.sin(phase * TAU) * reflection);
        int edge = blend(color, accent, .14f * reflection);
        paintLine(canvas, focusOutline, edge, FOCUS_STROKE, mix(.92f, 1, roundness));
        if (reflection > 0) {
            for (int quarter = 0; quarter < focusCorners.length; quarter++) {
                int cornerSave = canvas.save();
                canvas.rotate(quarter * 90);
                float shine = cornerShine[quarter];
                float elbow = cornerElbow[quarter];
                drawSoftReflection(canvas, elbow, elbow, -45, .76f * reflection * smooth(.5f, 1, shine));
                paintHighlight(canvas, focusCorners[quarter], cornerLight, 1, reflection * (.20f + .73f * shine));
                canvas.restoreToCount(cornerSave);
            }
        }
        canvas.restoreToCount(saved);
    }

    private void drawSoftReflection(Canvas canvas, float x, float y, float angle, float opacity) {
        if (opacity <= 0) return;
        int saved = canvas.save();
        canvas.translate(x, y);
        canvas.rotate(angle);
        reflectionPaint.setAlpha(alpha(opacity));
        canvas.drawBitmap(ReflectionTexture.BITMAP, null, REFLECTION_BOUNDS, reflectionPaint);
        canvas.restoreToCount(saved);
    }

    private static final class ReflectionTexture {
        // Cache the Gaussian mask in software so previews and hardware overlays share it.
        static final Bitmap BITMAP = create();

        private static Bitmap create() {
            Bitmap bitmap = Bitmap.createBitmap(64, 40, Bitmap.Config.ARGB_8888);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(BLUE);
            paint.setMaskFilter(new BlurMaskFilter(7, BlurMaskFilter.Blur.NORMAL));
            new Canvas(bitmap).drawRoundRect(20, 17, 44, 23, 3, 3, paint);
            return bitmap;
        }
    }

    private void drawResultRing(Canvas canvas, float radius, int color, float width) {
        stroke.rewind();
        stroke.addCircle(0, 0, radius, Path.Direction.CW);
        paintLine(canvas, stroke, color, width, 1);
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
