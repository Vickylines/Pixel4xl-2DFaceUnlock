package com.yusd.pixel2dface;

import static com.yusd.pixel2dface.FocusMotion.clamp;
import static com.yusd.pixel2dface.FocusMotion.ease;
import static com.yusd.pixel2dface.FocusRibbon.blend;
import static com.yusd.pixel2dface.FocusRibbon.mix;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/** Silver face, rotating luminous arcs, and the green confirmation inside the expanding island. */
final class IslandGlyph {
    private static final float RADIUS = 21.6f;
    private static final int GREEN = 0xff38f768;
    private static final int SILVER = 0xffb8bbc1;
    private static final int STEPS = 56;
    private static final int MERIDIANS = 6;

    private final float[] cosine = new float[STEPS + 1];
    private final float[] sine = new float[STEPS + 1];
    private final float[] axisX = new float[MERIDIANS];
    private final float[] axisZ = new float[MERIDIANS];
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path line = new Path();
    private final Path far = new Path();
    private final Path near = new Path();
    private final Path farArcs = new Path();
    private final Path nearArcs = new Path();
    private Path previousSide;
    private float turnCos, turnSin, lastX, lastY, lastDepth;

    IslandGlyph() {
        for (int i = 0; i <= STEPS; i++) {
            double angle = 2 * Math.PI * i / STEPS;
            cosine[i] = (float) Math.cos(angle);
            sine[i] = (float) Math.sin(angle);
        }
        for (int i = 0; i < MERIDIANS; i++) {
            double angle = Math.PI * i / MERIDIANS + .27;
            axisX[i] = (float) Math.cos(angle);
            axisZ[i] = (float) Math.sin(angle);
        }
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        glass.setShader(new RadialGradient(0, 0, RADIUS,
                new int[]{0x0528d348, 0x1024dc48, 0x302bc94b, 0x0025c646},
                new float[]{0, .65f, .91f, 1}, Shader.TileMode.CLAMP));
    }

    void draw(Canvas canvas, float phase, boolean success, float elapsed, float duration, float collapse) {
        float turn = IslandMotion.turn(phase, success ? elapsed : 0);
        float restored = success ? ease(0, IslandMotion.RESTORE_MS, elapsed) : 0;
        float fold = FocusMotion.fold(turn);
        float reset = IslandMotion.reset(phase) * (1 - restored);
        float writingAge = success ? IslandMotion.writingAge(elapsed, duration) : 0;
        float featuresGone = ease(0, 125, writingAge);
        float round = mix(ease(.69f, .996f, turn) * (1 - reset), 1, featuresGone);
        int color = blend(SILVER, GREEN, Math.max(round, ease(.02f, .18f, turn) * (1 - reset)));
        drawFace(canvas, round, fold, featuresGone, writingAge, color, collapse);
        if (fold > 0) drawGlobe(canvas, turn, fold);
    }

    private void drawFace(Canvas canvas, float round, float fold, float featuresGone,
            float writingAge, int color, float collapse) {
        float opacity = 1 - ease(.035f, .72f, fold);
        if (opacity <= 0) return;
        float rimWidth = mix(2.15f, 2.55f, round);
        stroke(color, mix(rimWidth, 3.65f, collapse), opacity);
        if (round >= 1) {
            canvas.drawCircle(0, 0, RADIUS, ink);
        } else {
            float radius = mix(8.3f, RADIUS, round);
            float gap = 8 * (1 - round);
            line.rewind();
            line.moveTo(-gap, -RADIUS);
            line.lineTo(-RADIUS + radius, -RADIUS);
            line.arcTo(-RADIUS, -RADIUS, -RADIUS + 2 * radius, -RADIUS + 2 * radius, -90, -90, false);
            line.lineTo(-RADIUS, -gap);
            int saved = canvas.save();
            for (int quarter = 0; quarter < 4; quarter++) {
                canvas.drawPath(line, ink);
                canvas.rotate(90);
            }
            canvas.restoreToCount(saved);
        }

        // The contour can already be visible while the last arc is still crossing the face.
        float detail = 1 - ease(.015f, .28f, fold);
        if (detail <= 0) return;
        if (featuresGone < 1) {
            stroke(color, 2.1f, detail * (1 - featuresGone));
            canvas.drawLine(-7, -7, -7, -3.5f, ink);
            canvas.drawLine(7, -7, 7, -3.5f, ink);
            line.rewind();
            line.moveTo(.8f, -5.4f);
            line.lineTo(.8f, 1.3f);
            line.quadTo(.8f, 3.7f, -2.2f, 3.7f);
            canvas.drawPath(line, ink);
        }
        float gather = ease(0, 175, writingAge);
        float upstroke = ease(110, IslandMotion.WRITE_END_MS, writingAge);
        float markWidth = mix(2.1f, 2.9f, gather);
        stroke(color, mix(markWidth, 4.6f, collapse), detail);
        ResultGlyphs.traceSmileIntoCheck(line, 1.18f, gather, upstroke);
        canvas.drawPath(line, ink);
    }

    private void drawGlobe(Canvas canvas, float turn, float strength) {
        double angle = turn * 2 * Math.PI;
        turnCos = (float) Math.cos(angle);
        turnSin = (float) Math.sin(angle);
        float mesh = ease(.23f, .60f, turn) * (1 - ease(.70f, .96f, turn));
        float arcLight = ease(.065f, .94f, strength) * (1 - .46f * mesh);
        near.rewind();
        far.rewind();
        nearArcs.rewind();
        farArcs.rewind();
        glass.setAlpha(Math.round(255 * mesh * strength));
        canvas.drawCircle(0, 0, RADIUS, glass);

        // Two open bands wind on different planes. Each crosses the other on its way behind the face.
        for (int band = 0; band < 2; band++) {
            float tilt = band == 0 ? .68f : -.68f;
            float x = axisX[band * 3], z = axisZ[band * 3];
            for (int i = 0; i <= STEPS - 7; i++) {
                int sample = (i + band * STEPS / 2) % STEPS;
                float u = cosine[sample], v = sine[sample];
                project(x * u - z * tilt * v, .7332f * v, z * u + x * tilt * v,
                        i == 0, nearArcs, farArcs);
            }
        }
        if (mesh > 0) {
            for (int m = 0; m < MERIDIANS; m++) {
                for (int i = 0; i <= STEPS; i++) {
                    project(cosine[i] * axisX[m], sine[i], cosine[i] * axisZ[m],
                            i == 0, near, far);
                }
            }
            for (int band = -2; band <= 2; band++) {
                float h = band * .32f;
                float r = (float) Math.sqrt(1 - h * h);
                for (int i = 0; i <= STEPS; i++) {
                    project(r * cosine[i], h, r * sine[i], i == 0, near, far);
                }
            }
            drawStroke(canvas, far, 0xff42985a, .63f, .43f * mesh * strength);
        }
        drawStroke(canvas, farArcs, 0xff23ae4f, 1.65f, .64f * arcLight);
        drawStroke(canvas, near, 0xff56ed78, .80f, .76f * mesh * strength);
        stroke(GREEN, mix(.82f, 1.65f, mesh), .81f * mesh * strength);
        canvas.drawCircle(0, 0, RADIUS, ink);
        // Keep the feather local to the lit edges, so the center and the black island stay clear.
        drawStroke(canvas, nearArcs, GREEN, 4.7f, .10f * arcLight);
        drawStroke(canvas, nearArcs, GREEN, 2.75f, .74f * arcLight);
        drawStroke(canvas, nearArcs, 0xffabffae, 1.28f, .97f * arcLight);
    }

    private void project(float x, float y, float z, boolean first, Path front, Path back) {
        float px = x * turnCos + z * turnSin;
        float pz = z * turnCos - x * turnSin;
        float py = y * .95f - pz * .312f;
        float depth = y * .312f + pz * .95f;
        float drawX = RADIUS * (px * .978f - py * .208f);
        float drawY = RADIUS * (px * .208f + py * .978f);
        Path side = depth < 0 ? back : front;
        if (first) {
            side.moveTo(drawX, drawY);
        } else {
            if (side != previousSide) {
                float crossing = lastDepth / (lastDepth - depth);
                float edgeX = mix(lastX, drawX, crossing), edgeY = mix(lastY, drawY, crossing);
                previousSide.lineTo(edgeX, edgeY);
                side.moveTo(edgeX, edgeY);
            }
            side.lineTo(drawX, drawY);
        }
        previousSide = side;
        lastX = drawX;
        lastY = drawY;
        lastDepth = depth;
    }

    private void drawStroke(Canvas canvas, Path path, int color, float width, float opacity) {
        if (opacity <= 0) return;
        stroke(color, width, opacity);
        canvas.drawPath(path, ink);
    }

    private void stroke(int color, float width, float opacity) {
        ink.setColor(color);
        ink.setStrokeWidth(width);
        ink.setAlpha(Math.round(255 * clamp(opacity)));
    }
}
