package com.yusd.pixel2dface;

import static com.yusd.pixel2dface.FocusMotion.clamp;
import static com.yusd.pixel2dface.FocusMotion.ease;
import static com.yusd.pixel2dface.FocusRibbon.mix;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.view.animation.PathInterpolator;

/** A single expanding ink contour, from the lip of the smile to the tip of the check. */
final class FocusCheckmark {
    private static final int SEGMENTS = 64;
    private static final float WRITE_START_MS = 34;
    private final PathInterpolator travel;
    private final Path finished = new Path(), moving = new Path();
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG), ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] checkX = samples(), checkY = samples(), checkRadius = samples();
    private final float[] checkEdgeX = samples(), checkEdgeY = samples();
    private final float[] lipX = samples(), lipDepth = samples();
    private final float[] x = samples(), y = samples(), radius = samples();
    private final float[] edgeX = samples(), edgeY = samples();

    FocusCheckmark(int keylineColor) {
        border.setColor(keylineColor);
        border.setStyle(Paint.Style.FILL_AND_STROKE);
        border.setStrokeWidth(1.35f);
        border.setStrokeJoin(Paint.Join.ROUND);
        Path spine = new Path();
        spine.moveTo(-10.7f, -.10f);
        spine.cubicTo(-9.15f, 1.20f, -6.40f, 4.64f, -4.38f, 6.50f);
        spine.cubicTo(-3.39f, 7.42f, -2.16f, 7.39f, -1.23f, 6.36f);
        spine.cubicTo(1.72f, 3.18f, 7.33f, -4.12f, 12.38f, -9.10f);
        PathMeasure measure = new PathMeasure(spine, false);
        float length = measure.getLength();
        float[] point = new float[2], tangent = new float[2];
        for (int i = 0; i <= SEGMENTS; i++) {
            float u = i / (float) SEGMENTS;
            measure.getPosTan(u * length, point, tangent);
            checkX[i] = point[0];
            checkY[i] = point[1];
            // Carry the weight up the long arm. Only the last fifth lifts to a rounded tip.
            checkRadius[i] = 1.36f + .61f * ease(0, .24f, u)
                    - .19f * ease(.38f, .62f, u) - .56f * ease(.82f, 1, u);
            checkEdgeX[i] = -tangent[1] * checkRadius[i];
            checkEdgeY[i] = tangent[0] * checkRadius[i];

            // These are the same two cubic halves as the resting smile, in lip coordinates.
            float t = 2 * Math.min(u, 1 - u), tail = 1 - t;
            float b1 = 3 * tail * tail * t, b2 = 3 * tail * t * t, b3 = t * t * t;
            lipX[i] = (u < .5f ? -1 : 1) * (1 - .225f * b1 - .60f * b2 - b3);
            lipDepth[i] = (5f / 7f) * b1 + b2 + b3;
        }
        trace(finished, checkX, checkY, checkEdgeX, checkEdgeY, 0, SEGMENTS);
        // Progress is distance along the spine. Carry speed through the heel, lift
        // faster on the long diagonal, then let the pen pull away at the terminal.
        Path timing = new Path();
        timing.moveTo(0, 0);
        timing.cubicTo(.075f, 0, .12f, .143f, .255f, .21f);
        timing.cubicTo(.305f, .235f, .385f, .256f, .435f, .32f);
        timing.cubicTo(.53f, .441f, .661f, .75f, .80f, .888f);
        timing.cubicTo(.865f, .952f, .935f, 1, 1, 1);
        travel = new PathInterpolator(timing);
    }

    void draw(Canvas canvas, int color, float opacity, float cheekX, float cheekY, float lowY,
            float gather, float landedMs) {
        ink.setColor(color);
        ink.setAlpha(Math.round(255 * opacity));
        border.setAlpha(Math.round(255 * .65f * opacity));
        float write = clamp((landedMs - WRITE_START_MS) / (FocusMotion.CHECK_FINISH_MS - WRITE_START_MS));
        float visible = mix(.085f, 1, travel.getInterpolation(write));
        if (gather < 1) {
            gatherSmile(cheekX, cheekY, lowY, gather, visible);
            trace(moving, x, y, edgeX, edgeY, 0, SEGMENTS);
            paintInk(canvas, moving);
            return;
        }
        if (visible == 1) {
            paintInk(canvas, finished);
            return;
        }
        float head = visible * SEGMENTS;
        int last = (int) Math.ceil(head);
        copyToTip(checkX, x, last, head);
        copyToTip(checkY, y, last, head);
        copyToTip(checkEdgeX, edgeX, last, head);
        copyToTip(checkEdgeY, edgeY, last, head);
        // HWUI can change edge coverage when a growing path's bounds cross a raster
        // threshold. Draw dry ink from one immutable outline and trim only the pen tip.
        // X increases along the entire spine; 3.5 dp clears its widest radius and keyline.
        float cut = x[last] - 3.5f;
        int first = last;
        while (first > 0 && x[first] > cut - 3.5f) first--;
        trace(moving, x, y, edgeX, edgeY, first, last);
        int saved = canvas.save();
        canvas.clipRect(-32, -32, cut, 32);
        paintInk(canvas, finished);
        canvas.restoreToCount(saved);
        saved = canvas.save();
        canvas.clipOutRect(-32, -32, cut, 32);
        paintInk(canvas, moving);
        canvas.restoreToCount(saved);
    }

    private void paintInk(Canvas canvas, Path shape) {
        canvas.drawPath(shape, border);
        canvas.drawPath(shape, ink);
    }

    private static void trace(Path path, float[] x, float[] y, float[] edgeX, float[] edgeY,
            int first, int last) {
        path.rewind();
        path.moveTo(x[first] + edgeX[first], y[first] + edgeY[first]);
        for (int i = first + 1; i <= last; i++) {
            path.lineTo(x[i] + edgeX[i], y[i] + edgeY[i]);
        }
        roundCap(path, x[last], y[last], edgeX[last], edgeY[last]);
        for (int i = last - 1; i >= first; i--) {
            path.lineTo(x[i] - edgeX[i], y[i] - edgeY[i]);
        }
        roundCap(path, x[first], y[first], -edgeX[first], -edgeY[first]);
        path.close();
    }

    private static void copyToTip(float[] source, float[] output, int last, float head) {
        System.arraycopy(source, 0, output, 0, last);
        int a = (int) head;
        output[last] = mix(source[a], source[Math.min(SEGMENTS, a + 1)], head - a);
    }

    private void gatherSmile(float cheekX, float cheekY, float lowY, float gather, float visible) {
        for (int i = 0; i <= SEGMENTS; i++) {
            float distance = i * visible;
            int a = (int) distance, b = Math.min(a + 1, SEGMENTS);
            float f = distance - a;
            x[i] = mix(cheekX * lipX[i], mix(checkX[a], checkX[b], f), gather);
            y[i] = mix(mix(cheekY, lowY, lipDepth[i]), mix(checkY[a], checkY[b], f), gather);
            radius[i] = mix(1.05f, mix(checkRadius[a], checkRadius[b], f), gather);
        }
        for (int i = 0; i <= SEGMENTS; i++) {
            int before = Math.max(0, i - 1), after = Math.min(SEGMENTS, i + 1);
            float dx = x[after] - x[before], dy = y[after] - y[before];
            float scale = radius[i] / (float) Math.sqrt(dx * dx + dy * dy);
            edgeX[i] = -dy * scale;
            edgeY[i] = dx * scale;
        }
    }

    private static void roundCap(Path path, float x, float y, float nx, float ny) {
        float tx = (4f / 3f) * ny, ty = -(4f / 3f) * nx;
        path.cubicTo(x + nx + tx, y + ny + ty, x - nx + tx, y - ny + ty, x - nx, y - ny);
    }

    private static float[] samples() {
        return new float[SEGMENTS + 1];
    }
}
