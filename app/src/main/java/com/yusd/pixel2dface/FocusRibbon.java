package com.yusd.pixel2dface;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Four individual corner strokes. Each keeps its two ends as it opens into a curved sheet of light. */
final class FocusRibbon {
    static final float RIM_RADIUS = 23.2f;
    static final float RIM_WIDTH = 1.9f;
    private static final float UNWRAP = .936f;
    private static final int CORNERS = 4;
    private static final int STEPS = 48;
    private static final int CAP_STEPS = 3;
    private static final int STRIDE = STEPS + 1 + 2 * CAP_STEPS;
    private static final int POINTS = CORNERS * STRIDE;
    private static final int DEPTH_SLICES = 4;
    private static final int BUCKETS = DEPTH_SLICES * CORNERS;
    private static final float RADIUS = 22.6f;
    private static final double TAU = 2 * Math.PI;

    // The sheet spans the positions a strand recently occupied. Narrow peaks in its
    // transmission leave fine, curved traces that converge at the real stroke.
    private static final float[] SHUTTER = {0, .075f, .15f, .18f, .21f, .31f, .35f,
            .38f, .49f, .53f, .56f, .70f, .74f, .77f, .89f, 1};
    private static final float[] TRANSMISSION = {.50f, .41f, .31f, .52f, .33f, .25f, .43f,
            .24f, .16f, .32f, .18f, .095f, .20f, .09f, .045f, 0};
    // A sharp emissive centre, a softer green shoulder, and a thin local dark edge for light wallpaper.
    private static final float[] PROFILE = {-3.6f, -2.30f, -1.52f, -.86f, -.34f, .18f, .65f, 1.17f, 2.05f, 3.5f};
    private static final float[] COVER = {0, .064f, .21f, .80f, 1, 1, .94f, .46f, .09f, 0};
    private static final float[] SHEEN = {0, 0, 0, .03f, .19f, .66f, .31f, .06f, 0, 0};
    private static final int ROWS = SHUTTER.length + PROFILE.length;

    private final float[] restingX = new float[POINTS], restingY = new float[POINTS];
    private final float[] rimX = new float[POINTS], rimY = new float[POINTS];
    private final float[] shellX = new float[POINTS], shellY = new float[POINTS], shellZ = new float[POINTS];
    private final float[] taper = new float[POINTS], glint = new float[POINTS];
    private final float[] capWidth = new float[POINTS], capLength = new float[POINTS];
    private final float[][] x = new float[3][POINTS], y = new float[3][POINTS];
    private final float[] z = new float[POINTS];
    private final float[][] vertices = new float[CORNERS][STRIDE * ROWS * 2];
    private final int[][] colors = new int[CORNERS][STRIDE * ROWS];
    private final short[][] veilIndices = new short[BUCKETS][(STRIDE - 1) * (SHUTTER.length - 1) * 6];
    private final short[][] coreIndices = new short[BUCKETS][(STRIDE - 1) * (PROFILE.length - 1) * 6];
    private final short[] veilStrip = new short[(STRIDE - 1) * (SHUTTER.length - 1) * 6];
    private final short[] coreStrip = new short[(STRIDE - 1) * (PROFILE.length - 1) * 6];
    private final int[] counts = new int[BUCKETS];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path contour = new Path();
    private final Bitmap diffusion = Bitmap.createBitmap(124, 124, Bitmap.Config.ARGB_8888);
    private final Canvas diffusionCanvas = new Canvas(diffusion);
    private final RectF diffusionBounds = new RectF(-31, -31, 31, 31);
    private final Paint softStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint filtered = new Paint(Paint.FILTER_BITMAP_FLAG);

    FocusRibbon() {
        paint.setColor(Color.WHITE);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        diffusionCanvas.translate(62, 62);
        diffusionCanvas.scale(2, 2);
        softStroke.setStyle(Paint.Style.STROKE);
        softStroke.setStrokeCap(Paint.Cap.ROUND);
        softStroke.setStrokeJoin(Paint.Join.ROUND);
        softStroke.setMaskFilter(new BlurMaskFilter(1.45f, BlurMaskFilter.Blur.NORMAL));
        for (int q = 0; q < CORNERS; q++) {
            for (int i = 0; i < STRIDE; i++) preparePoint(q, i);
        }
        for (int s = 0; s < STRIDE - 1; s++) {
            strip(veilStrip, s * ROWS, SHUTTER.length, s);
            strip(coreStrip, s * ROWS + SHUTTER.length, PROFILE.length, s);
        }
    }

    static float cornerLight(int q) {
        return q == 0 ? 1 : q == 2 ? 0 : .5f;
    }

    static float cornerHalf(int q) {
        return 24 - .80f * cornerLight(q);
    }

    static float returningRim(float turn) {
        return 1 - FocusMotion.ease(UNWRAP, 1, turn);
    }

    private void preparePoint(int q, int i) {
        int index = q * STRIDE + i;
        int bodyIndex = Math.max(0, Math.min(STEPS, i - CAP_STEPS));
        float u = bodyIndex / (float) STEPS;
        double capAngle = (i - CAP_STEPS - bodyIndex) * (Math.PI / 2) / CAP_STEPS;
        capWidth[index] = (float) Math.cos(capAngle);
        capLength[index] = (float) Math.sin(capAngle);
        float half = cornerHalf(q);
        double radius = 10, tangent = half - radius, gap = tangent * (10.0 / 14);
        double straight = tangent - gap;
        double distance = u * (2 * straight + Math.PI * radius / 2);
        double restX, restY;
        if (distance < straight) {
            restX = -half;
            restY = -gap - distance;
        } else if (distance < straight + Math.PI * radius / 2) {
            double arc = Math.PI + (distance - straight) / radius;
            restX = -tangent + radius * Math.cos(arc);
            restY = -tangent + radius * Math.sin(arc);
        } else {
            restX = -tangent + distance - straight - Math.PI * radius / 2;
            restY = -half;
        }
        double quarter = q * Math.PI / 2;
        restingX[index] = (float) (restX * Math.cos(quarter) - restY * Math.sin(quarter));
        restingY[index] = (float) (restX * Math.sin(quarter) + restY * Math.cos(quarter));
        double rimAngle = quarter - 3 * Math.PI / 4 + (u - .5) * Math.PI / 2;
        rimX[index] = RIM_RADIUS * (float) Math.cos(rimAngle);
        rimY[index] = RIM_RADIUS * (float) Math.sin(rimAngle);

        // Opposite corners unwrap on the same meridian. The two meridians cross,
        // each at a different inclination. A shallow bend keeps their edges supple.
        boolean across = (q & 1) != 0;
        double a = quarter - 3 * Math.PI / 4 + (u - .5) * (across ? 3.24 : 3.37);
        double bend = (across ? -.14 : .18) * Math.sin(2 * a + (across ? 1.25 : -.48));
        double radial = RADIUS * (1 + .025 * Math.sin(3 * a + (across ? .75 : 0)));
        double px = radial * Math.cos(a) * Math.cos(bend);
        double py = radial * Math.sin(a) * Math.cos(bend);
        double pz = radial * Math.sin(bend);
        double tiltY = across ? .18 : -.91;
        double tiltX = across ? 1.12 : -.26;
        double tiltedX = px * Math.cos(tiltY) + pz * Math.sin(tiltY);
        double tiltedZ = pz * Math.cos(tiltY) - px * Math.sin(tiltY);
        shellX[index] = (float) tiltedX;
        shellY[index] = (float) (py * Math.cos(tiltX) - tiltedZ * Math.sin(tiltX));
        shellZ[index] = (float) (py * Math.sin(tiltX) + tiltedZ * Math.cos(tiltX));
        taper[index] = FocusMotion.ease(0, .16f, u) * (1 - FocusMotion.ease(.78f, 1, u));
        glint[index] = (float) (.5 + .5 * Math.cos(a - (across ? 1.7 : -.55)));
    }

    void draw(Canvas canvas, float turn, float fold, int restingColor, float reflection,
            float seal, float retainedRim) {
        float travel = FocusMotion.ease(.045f, .22f, turn) * (1 - FocusMotion.ease(.77f, .96f, turn));
        float exposure = .140f * fold * (.52f + .48f * travel);
        project(0, turn, seal, retainedRim);
        float optical = FocusMotion.ease(.008f, .42f, fold);
        if (optical < 1) drawInk(canvas, restingColor, 1 - optical,
                reflection * (1 - seal) * (1 - FocusMotion.ease(0, .21f, fold)), seal,
                turn >= .957f && (seal == 1 || retainedRim == 1));
        if (optical == 0) return;
        project(1, turn - exposure * .5f, seal, retainedRim);
        project(2, turn - exposure, seal, retainedRim);
        drawDiffusion(canvas, optical * fold * (.48f + .52f * travel));
        for (int q = 0; q < CORNERS; q++) {
            int start = q * STRIDE;
            for (int i = start; i < start + STRIDE; i++) {
                int body = Math.max(start + CAP_STEPS, Math.min(start + CAP_STEPS + STEPS, i));
                int before = Math.max(start + CAP_STEPS, body - 1);
                int after = Math.min(start + CAP_STEPS + STEPS, body + 1);
                float dx = x[0][after] - x[0][before];
                float dy = y[0][after] - y[0][before];
                float inverseLength = 1f / (float) Math.sqrt(dx * dx + dy * dy + .0001f);
                writeSection(i, dy * inverseLength, -dx * inverseLength, fold, restingColor,
                        (float) Math.sqrt(optical));
            }
        }
        composite(canvas);
    }

    private void drawDiffusion(Canvas canvas, float amount) {
        // Blur just the moving strokes into a small light layer. It follows their
        // last two poses and never softens the face or fills the empty background.
        diffusion.eraseColor(Color.TRANSPARENT);
        for (int sample = 2; sample >= 0; sample--) {
            softStroke.setColor(sample == 0 ? 0xff72f181 : 0xff47ce77);
            float lag = .28f * sample;
            softStroke.setAlpha(Math.round(64 * amount / (1 + sample)));
            softStroke.setStrokeWidth(sample == 0 ? 2.05f : 2.35f);
            contour.rewind();
            for (int q = 0; q < CORNERS; q++) {
                int first = q * STRIDE + CAP_STEPS + 3;
                contour.moveTo(mix(x[0][first], x[1][first], lag),
                        mix(y[0][first], y[1][first], lag));
                for (int i = first + 1; i <= first + STEPS - 6; i++) {
                    contour.lineTo(mix(x[0][i], x[1][i], lag), mix(y[0][i], y[1][i], lag));
                }
            }
            diffusionCanvas.drawPath(contour, softStroke);
        }
        canvas.drawBitmap(diffusion, null, diffusionBounds, filtered);
    }

    private void drawInk(Canvas canvas, int color, float opacity, float reflection,
            float seal, boolean closed) {
        // Follow the very same projected centreline as the translucent material.
        // This keeps the rounded tips attached while each corner softens into light.
        boolean joining = closed || seal > .55f || (seal > 0 && reflection < .01f);
        float width = mix(2.15f, RIM_WIDTH, seal);
        contour.rewind();
        for (int q = 0; q < CORNERS; q++) {
            int first = q * STRIDE + CAP_STEPS;
            if (q == 0 || !closed) contour.moveTo(x[0][first], y[0][first]);
            else contour.lineTo(x[0][first], y[0][first]);
            for (int i = first + 1; i <= first + STEPS; i++) contour.lineTo(x[0][i], y[0][i]);
            // At the join, paint the four shadows together so one cap cannot cut
            // a dark notch in the bright cap already laid down next to it.
            if (joining && q < CORNERS - 1) continue;
            if (closed) contour.close();
            ink.setColor(0xff0b1320);
            ink.setAlpha(Math.round(mix(130, 166, seal) * opacity));
            ink.setStrokeWidth(width + mix(1.20f, 1.35f, seal));
            canvas.drawPath(contour, ink);
            ink.setColor(blend(color, 0xffa9d9ff, .34f * cornerLight(q) * reflection));
            ink.setAlpha(Math.round(255 * opacity * (1 - .10f * reflection)));
            ink.setStrokeWidth(width);
            canvas.drawPath(contour, ink);
            contour.rewind();
        }
    }

    private void project(int sample, float turn, float seal, float retainedRim) {
        turn = FocusMotion.clamp(turn);
        float fold = FocusMotion.fold(turn);
        float rounded = FocusMotion.ease(0, .093f, turn) * returningRim(turn);
        // If the result arrived during the return, keep the closure already on screen.
        // Otherwise the four joints briefly reopen just before the circle lands.
        if (turn >= UNWRAP) rounded = Math.max(rounded, retainedRim);
        rounded = mix(rounded, 1, seal);
        float open = FocusMotion.ease(.024f, .177f, turn) * (1 - FocusMotion.ease(.775f, .957f, turn));
        double angle = TAU * turn;
        double yaw = angle + .14 * Math.sin(2 * angle) * fold;
        double pitch = (.28 * Math.sin(angle - .25) - .10) * fold;
        double roll = angle + .20 * Math.sin(angle);
        float cy = (float) Math.cos(yaw), sy = (float) Math.sin(yaw);
        float cp = (float) Math.cos(pitch), sp = (float) Math.sin(pitch);
        float cr = (float) Math.cos(roll), sr = (float) Math.sin(roll);
        for (int i = 0; i < POINTS; i++) {
            float lead = .58f * (glint[i] - .5f);
            float wind = open + lead * open * (1 - open);
            float px = mix(mix(restingX[i], rimX[i], rounded), shellX[i], wind);
            float py = mix(mix(restingY[i], rimY[i], rounded), shellY[i], wind);
            float pz = shellZ[i] * wind;
            float yawX = px * cy + pz * sy;
            float yawZ = pz * cy - px * sy;
            float viewY = py * cp - yawZ * sp;
            float viewZ = py * sp + yawZ * cp;
            float perspective = 1 / (1 - viewZ / 208f);
            x[sample][i] = (yawX * cr - viewY * sr) * perspective;
            y[sample][i] = (yawX * sr + viewY * cr) * perspective;
            if (sample == 0) z[i] = viewZ;
        }
    }

    private void writeSection(int i, float nx, float ny, float fold, int restingColor, float opacity) {
        float px = x[0][i], py = y[0][i];
        float near = FocusMotion.clamp(.50f + z[i] / (2 * RADIUS));
        float end = 1 - fold * (1 - taper[i]);
        float light = FocusMotion.clamp(.12f + .63f * near + .14f * glint[i] - .12f * py / RADIUS);
        int green = blend(0xff16763e, 0xff98ff96, light);
        int core = blend(restingColor, green, fold);
        float width = mix(1.08f, (.46f + .51f * near) * (.55f + .45f * end), fold);
        float strength = opacity * mix(1, (.54f + .46f * near) * (.11f + .89f * end), fold);

        // Quadratic interpolation through the two actual earlier poses follows the
        // arc of travel, including the reversal of its screen direction on the back.
        float vx = x[2][i] - px, vy = y[2][i] - py;
        float bowX = x[1][i] - (px + .5f * vx), bowY = y[1][i] - (py + .5f * vy);
        int corner = i / STRIDE;
        int start = (i % STRIDE) * ROWS;
        int silk = blend(0xff278659, 0xff89ef8e, .24f + .62f * near);
        float silkAlpha = opacity * fold * taper[i] * (.36f + .84f * near);
        float projectedSweep = vx * nx + vy * ny;
        float projectedBow = bowX * nx + bowY * ny;
        for (int row = 0; row < SHUTTER.length; row++) {
            float t = SHUTTER[row];
            float bend = 4 * t * (1 - t);
            float travel = projectedSweep + (4 - 8 * t) * projectedBow;
            float coverage = FocusMotion.ease(.24f, 2.4f, Math.abs(travel));
            put(corner, start + row, px + t * vx + bend * bowX, py + t * vy + bend * bowY,
                    silk, silkAlpha * TRANSMISSION[row] * coverage);
        }
        for (int row = 0; row < PROFILE.length; row++) {
            float offset = PROFILE[row] * width * capWidth[i];
            float tip = capLength[i] * width;
            int color = blend(core, 0xffd7ffbb, SHEEN[row] * (.33f + .52f * near) * fold);
            if (row < 2) color = blend(color, 0xff0b3427, .60f * fold);
            put(corner, start + SHUTTER.length + row, px + nx * offset - ny * tip, py + ny * offset + nx * tip,
                    color, COVER[row] * strength);
        }
    }

    private void put(int corner, int index, float px, float py, int color, float alpha) {
        vertices[corner][2 * index] = px;
        vertices[corner][2 * index + 1] = py;
        colors[corner][index] = (color & 0x00ffffff) | (Math.round(255 * alpha) << 24);
    }

    private void composite(Canvas canvas) {
        java.util.Arrays.fill(counts, 0);
        int veilSize = (SHUTTER.length - 1) * 6, coreSize = (PROFILE.length - 1) * 6;
        for (int q = 0; q < CORNERS; q++) {
            for (int s = 0; s < STRIDE - 1; s++) {
                int point = q * STRIDE + s;
                float depth = .5f + (z[point] + z[point + 1]) / (4 * RADIUS + 8);
                int slice = Math.max(0, Math.min(DEPTH_SLICES - 1, (int) (depth * DEPTH_SLICES)));
                int bucket = slice * CORNERS + q;
                int dest = counts[bucket]++;
                System.arraycopy(veilStrip, s * veilSize, veilIndices[bucket], dest * veilSize, veilSize);
                System.arraycopy(coreStrip, s * coreSize, coreIndices[bucket], dest * coreSize, coreSize);
            }
        }
        // Separate transparent meshes at crossings. A single drawVertices call can
        // self-overwrite its translucent triangles on Android's bitmap Canvas.
        for (int slice = 0; slice < DEPTH_SLICES; slice++) {
            drawSlice(canvas, slice, veilIndices, SHUTTER.length);
            drawSlice(canvas, slice, coreIndices, PROFILE.length);
        }
    }

    private void drawSlice(Canvas canvas, int slice, short[][] indices, int rows) {
        int perSegment = (rows - 1) * 6;
        for (int q = 0; q < CORNERS; q++) {
            int b = slice * CORNERS + q;
            int count = counts[b] * perSegment;
            if (count == 0) continue;
            canvas.drawVertices(Canvas.VertexMode.TRIANGLES, vertices[q].length, vertices[q], 0,
                    null, 0, colors[q], 0, indices[b], 0, count, paint);
        }
    }

    private static void strip(short[] indices, int base, int count, int dest) {
        int out = dest * (count - 1) * 6;
        for (int row = 0; row < count - 1; row++) {
            int a = base + row, b = a + ROWS;
            indices[out++] = (short) a;
            indices[out++] = (short) b;
            indices[out++] = (short) (a + 1);
            indices[out++] = (short) (a + 1);
            indices[out++] = (short) b;
            indices[out++] = (short) (b + 1);
        }
    }

    static int blend(int from, int to, float t) {
        return Color.rgb(Math.round(mix(Color.red(from), Color.red(to), t)),
                Math.round(mix(Color.green(from), Color.green(to), t)),
                Math.round(mix(Color.blue(from), Color.blue(to), t)));
    }

    static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
