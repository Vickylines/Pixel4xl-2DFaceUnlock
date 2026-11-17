package com.yusd.pixel2dface;

import static com.yusd.pixel2dface.LockscreenStyleRenderer.alpha;
import static com.yusd.pixel2dface.LockscreenStyleRenderer.mix;
import static com.yusd.pixel2dface.LockscreenStyleRenderer.smooth;
import static com.yusd.pixel2dface.LockscreenStyleRenderer.stroke;
import static com.yusd.pixel2dface.UnlockFaceAnimationView.FAILURE;
import static com.yusd.pixel2dface.UnlockFaceAnimationView.SUCCESS;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** A shallow portrait with perspective and a continuous contour-to-ring transition. */
final class FacePortraitRenderer {
    private static final float TAU = (float) (2 * Math.PI);
    private static final float QUARTER_CIRCLE = .55228475f;

    // XYZ control points, beginning at the crown and proceeding clockwise.
    private static final float[] PORTRAIT_RIM = {
            0, -16, 2,
            8.4f, -16, 2,   14.5f, -10.5f, 0,   14.2f, -1.1f, 0,
            13.8f, 7.9f, 0,   8.1f, 15.6f, 1,   0, 16.5f, 2.2f,
            -8.1f, 15.6f, 1,   -13.8f, 7.9f, 0,   -14.2f, -1.1f, 0,
            -14.5f, -10.5f, 0,   -8.4f, -16, 2,   0, -16, 2
    };
    private static final float[] RING = {
            0, -1, 0,
            QUARTER_CIRCLE, -1, 0,   1, -QUARTER_CIRCLE, 0,   1, 0, 0,
            1, QUARTER_CIRCLE, 0,   QUARTER_CIRCLE, 1, 0,   0, 1, 0,
            -QUARTER_CIRCLE, 1, 0,   -1, QUARTER_CIRCLE, 0,   -1, 0, 0,
            -1, -QUARTER_CIRCLE, 0,   -QUARTER_CIRCLE, -1, 0,   0, -1, 0
    };
    private static final float[] NOSE = {
            .35f, -5.7f, 3.5f,
            .42f, -3.5f, 4.5f,   .8f, .1f, 8.4f,   .6f, 2.4f, 9,
            .5f, 3.7f, 8.6f,   -1.5f, 3.7f, 6.2f,   -2.5f, 3.6f, 4.6f
    };
    private static final float[] SMILE = {
            -7.6f, 7.1f, 2.6f,
            -5.7f, 9.4f, 3.5f,   -2.7f, 10.5f, 4.4f,   0, 10.5f, 4.7f,
            2.7f, 10.5f, 4.4f,   5.7f, 9.4f, 3.5f,   7.6f, 7.1f, 2.6f
    };
    private static final float[] CHECK = {
            -10, .5f, 0,
            -8.4f, 2, 0,   -6.6f, 4.1f, 0,   -4.7f, 6, 0,
            -.8f, 2.2f, 0,   5.2f, -3.8f, 0,   10.9f, -9.2f, 0
    };
    private static final float[] FROWN = {
            -7.6f, 9.7f, 0,
            -5.3f, 8, 0,   -2.7f, 7.2f, 0,   0, 7.2f, 0,
            2.7f, 7.2f, 0,   5.3f, 8, 0,   7.6f, 9.7f, 0
    };

    private static final RectF TEXTURE_AREA = new RectF(-24, -24, 24, 24);
    private final Paint line = LockscreenStyleRenderer.strokePaint();
    private final Paint glaze = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path rim = new Path();
    private final Path feature = new Path();
    private final Projection camera = new Projection();
    private final float[] projected = new float[PORTRAIT_RIM.length / 3 * 2];

    void draw(Canvas canvas, boolean focus, int state, float phase, float progress, int color,
            boolean animated) {
        float angle = phase * TAU;
        float pose = 1 - smooth(.02f, .72f, progress);
        float yaw = (focus ? .76f : .63f) * (float) Math.sin(angle) * pose;
        float pitch = (focus ? -.145f : -.23f) * (float) Math.sin(2 * angle) * pose;
        float roll = .048f * (float) Math.sin(angle) * pose;
        float yawSpeed = (float) Math.cos(angle);
        float turnSpeed = animated ? yawSpeed * yawSpeed * pose : 0;
        float relief = 1 - smooth(.04f, .88f, progress);
        camera.setRotation(yaw, pitch, roll);

        float rounding = smooth(.08f, .94f, progress);
        float radius = focus ? 23.5f : 22f;
        if (rounding == 1) {
            rim.rewind();
            rim.addCircle(0, 0, radius, Path.Direction.CW);
        } else {
            trace(rim, PORTRAIT_RIM, RING, rounding, radius, relief);
            rim.close();
        }

        float glassOpacity = 1 - smooth(.10f, .77f, progress);
        float far = smooth(.12f, .72f, Math.abs(yaw));
        if (glassOpacity > 0) {
            drawGlass(canvas, yaw, pitch, turnSpeed, relief, glassOpacity, far);
        }
        if (state == SUCCESS) {
            float sheen = smooth(.47f, .78f, progress) * (1 - smooth(.80f, .99f, progress));
            drawTexture(canvas, Glass.RING_SHEEN, 0, 0, radius / 22f, radius / 22f, .54f * sheen);
        }
        stroke(canvas, line, rim, color, mix(1.06f, focus ? 2.15f : 1.85f, rounding),
                mix(.50f - .13f * far, 1, rounding));

        float details = state == SUCCESS ? 1 - smooth(.10f, .65f, progress) : 1;
        if (details > 0) {
            float cycle = phase % 1;
            float blink = smooth(.33f, .37f, cycle) * (1 - smooth(.37f, .41f, cycle)) * pose;
            drawEye(canvas, -6.4f, yaw, relief, blink, details, color);
            drawEye(canvas, 6.4f, yaw, relief, blink, details, color);
            trace(feature, NOSE, NOSE, 0, 1, relief);
            stroke(canvas, line, feature, color, 1.83f, details);
        }

        float mark = smooth(.10f, .90f, progress);
        float[] resultMouth = state == SUCCESS ? CHECK : state == FAILURE ? FROWN : SMILE;
        trace(feature, SMILE, resultMouth, mark, 1, relief);
        stroke(canvas, line, feature, color, state == SUCCESS ? mix(2, 2.55f, mark) : 2, 1);
    }

    private void drawGlass(Canvas canvas, float yaw, float pitch, float speed, float relief,
            float opacity, float far) {
        int saved = canvas.save();
        canvas.concat(camera.plane(1.4f * relief));
        drawTexture(canvas, Glass.SHADOW, 0, 1.6f, 1, 1, .56f * opacity);
        drawTexture(canvas, Glass.HAZE, 2.7f * yaw, -1.3f, 1 + .28f * speed, 1,
                (.44f + .27f * speed) * opacity);
        drawTexture(canvas, Glass.RIM_LIGHT, -5.4f + 4.8f * yaw, -6.2f + 5 * pitch, 1, 1,
                (.28f + .35f * speed) * opacity);
        drawTexture(canvas, Glass.FAR_RIM, 0, 0, yaw < 0 ? -1 : 1, 1, .59f * far * opacity);
        canvas.restoreToCount(saved);
    }

    private void drawEye(Canvas canvas, float x, float yaw, float relief, float blink,
            float opacity, int color) {
        float y = -5.9f;
        float half = mix(1.8f, .53f, blink);
        float z = 4.1f * relief;
        float far = smooth(0, .76f, Math.signum(x) * yaw);
        camera.map(x, y, z, projected, 0);
        drawTexture(canvas, Glass.SOFT_EYE, projected[0], projected[1], 1 + .6f * far, 1,
                (.08f + .72f * far) * opacity);

        camera.map(x, y - half, z, projected, 0);
        camera.map(x, y + half, z, projected, 2);
        feature.rewind();
        feature.moveTo(projected[0], projected[1]);
        feature.lineTo(projected[2], projected[3]);
        stroke(canvas, line, feature, color, 2.28f - .27f * far, opacity * (1 - .44f * far));
    }

    private void trace(Path path, float[] from, float[] to, float amount, float size, float relief) {
        int points = from.length / 3;
        for (int point = 0; point < points; point++) {
            int index = 3 * point;
            camera.map(mix(from[index], to[index] * size, amount),
                    mix(from[index + 1], to[index + 1] * size, amount),
                    mix(from[index + 2], to[index + 2], amount) * relief, projected, 2 * point);
        }
        path.rewind();
        path.moveTo(projected[0], projected[1]);
        for (int index = 2; index < points * 2; index += 6) {
            path.cubicTo(projected[index], projected[index + 1], projected[index + 2],
                    projected[index + 3], projected[index + 4], projected[index + 5]);
        }
    }

    private void drawTexture(Canvas canvas, Bitmap image, float x, float y, float widthScale,
            float heightScale, float opacity) {
        if (opacity <= 0) return;
        int saved = canvas.save();
        canvas.translate(x, y);
        canvas.scale(widthScale, heightScale);
        glaze.setAlpha(alpha(opacity));
        canvas.drawBitmap(image, null, TEXTURE_AREA, glaze);
        canvas.restoreToCount(saved);
    }

    private static final class Projection {
        private static final float FOCAL_LENGTH = 86;
        private static final float[] PLANE_CORNERS = {-24, -24, 24, -24, 24, 24, -24, 24};
        private final float[] projectedCorners = new float[8];
        private final Matrix planeMatrix = new Matrix();
        private float cosYaw, sinYaw, cosPitch, sinPitch, cosRoll, sinRoll;

        void setRotation(float yaw, float pitch, float roll) {
            cosYaw = (float) Math.cos(yaw);
            sinYaw = (float) Math.sin(yaw);
            cosPitch = (float) Math.cos(pitch);
            sinPitch = (float) Math.sin(pitch);
            cosRoll = (float) Math.cos(roll);
            sinRoll = (float) Math.sin(roll);
        }

        void map(float x, float y, float z, float[] target, int offset) {
            float turnedX = x * cosYaw + z * sinYaw;
            float turnedZ = z * cosYaw - x * sinYaw;
            float tiltedY = y * cosPitch - turnedZ * sinPitch;
            float depth = y * sinPitch + turnedZ * cosPitch;
            float scale = FOCAL_LENGTH / (FOCAL_LENGTH - depth);
            target[offset] = (turnedX * cosRoll - tiltedY * sinRoll) * scale;
            target[offset + 1] = (turnedX * sinRoll + tiltedY * cosRoll) * scale;
        }

        Matrix plane(float z) {
            for (int offset = 0; offset < PLANE_CORNERS.length; offset += 2) {
                map(PLANE_CORNERS[offset], PLANE_CORNERS[offset + 1], z, projectedCorners, offset);
            }
            planeMatrix.setPolyToPoly(PLANE_CORNERS, 0, projectedCorners, 0, 4);
            return planeMatrix;
        }
    }

    private static final class Glass {
        private static final int SIZE = 144;
        private static final float DENSITY = SIZE / 48f;
        // These Gaussian masks are baked once. Rendering a frame only positions and blends them.
        static final Bitmap SHADOW = oval(0xff172330, 13, 14.9f, 3.4f);
        static final Bitmap HAZE = oval(0xff87acca, 7.9f, 9.7f, 5.8f);
        static final Bitmap RIM_LIGHT = oval(0xffe8f2fb, 3.9f, 5.1f, 3.1f);
        static final Bitmap SOFT_EYE = oval(0xffd3ebff, 1.25f, 2.6f, 1.45f);
        static final Bitmap FAR_RIM = farRim();
        static final Bitmap RING_SHEEN = ring();

        private static Bitmap oval(int color, float rx, float ry, float blur) {
            Bitmap bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
            float center = SIZE / 2f;
            new Canvas(bitmap).drawOval(center - rx * DENSITY, center - ry * DENSITY,
                    center + rx * DENSITY, center + ry * DENSITY, blurredPaint(color, blur));
            return bitmap;
        }

        private static Bitmap ring() {
            Bitmap bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
            Paint paint = blurredPaint(0xffa5edcf, 1.65f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.55f * DENSITY);
            new Canvas(bitmap).drawCircle(SIZE / 2f, SIZE / 2f, 21.6f * DENSITY, paint);
            return bitmap;
        }

        private static Bitmap farRim() {
            Bitmap bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
            Paint paint = blurredPaint(0xffb3d5ee, 1.85f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.6f * DENSITY);
            paint.setStrokeCap(Paint.Cap.ROUND);
            Path edge = new Path();
            edge.moveTo(pixel(PORTRAIT_RIM[0]), pixel(PORTRAIT_RIM[1]));
            for (int index = 3; index < 21; index += 9) {
                edge.cubicTo(pixel(PORTRAIT_RIM[index]), pixel(PORTRAIT_RIM[index + 1]),
                        pixel(PORTRAIT_RIM[index + 3]), pixel(PORTRAIT_RIM[index + 4]),
                        pixel(PORTRAIT_RIM[index + 6]), pixel(PORTRAIT_RIM[index + 7]));
            }
            new Canvas(bitmap).drawPath(edge, paint);
            return bitmap;
        }

        private static float pixel(float dp) {
            return SIZE / 2f + dp * DENSITY;
        }

        private static Paint blurredPaint(int color, float radius) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(color);
            paint.setMaskFilter(new BlurMaskFilter(radius * DENSITY, BlurMaskFilter.Blur.NORMAL));
            return paint;
        }
    }
}
