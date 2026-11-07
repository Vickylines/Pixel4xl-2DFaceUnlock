package com.yusd.pixel2dface;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/** Four distinct, allocation-free native drawings. Coordinates are density-independent. */
final class LockscreenStyleRenderer {
    private static final int WHITE = Color.rgb(239, 247, 250);
    private static final int MINT = Color.rgb(144, 238, 199);
    private static final int AMBER = Color.rgb(255, 188, 132);
    private static final int ICE = Color.rgb(151, 222, 255);
    private static final int GOLD = Color.rgb(236, 211, 166);
    private static final int DARK = Color.rgb(11, 17, 25);
    private static final double TAU = Math.PI * 2;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint light = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF bounds = new RectF();
    private final LinearGradient glass = new LinearGradient(-25, -34, 25, 34,
            new int[]{0xff263846, 0xff101a25, 0xff070c13}, null, Shader.TileMode.CLAMP);
    private final LinearGradient aurora = new LinearGradient(-28, -24, 28, 30,
            new int[]{0xff7699f2, 0xffa4def6, 0xff99edda}, null, Shader.TileMode.CLAMP);
    private final LinearGradient edge = new LinearGradient(-30, -30, 30, 30,
            new int[]{0xbee4f7ff, 0x146f9faa, 0x548bc9d6}, null, Shader.TileMode.CLAMP);
    private final LinearGradient panel = new LinearGradient(0, -23, 0, 23,
            new int[]{0xff25313a, 0xff0c141c}, null, Shader.TileMode.CLAMP);

    LockscreenStyleRenderer() {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
    }

    void draw(Canvas canvas, int style, int state, float phase, float progress) {
        switch (style) {
            case AnimationStyle.GLOW: curtain(canvas, state, phase, progress); break;
            case AnimationStyle.ORBIT: orbit(canvas, state, phase, progress); break;
            case AnimationStyle.MINIMAL: outline(canvas, state, phase, progress); break;
            case AnimationStyle.DOTS: matrix(canvas, state, phase, progress); break;
            default: throw new IllegalArgumentException("Unknown accent style: " + style);
        }
    }

    // A curved sheet of light, with narrow translucent folds moving across smoked glass.
    private void curtain(Canvas canvas, int state, float phase, float progress) {
        float settle = smooth(progress);
        float breathe = (float) Math.sin(phase * TAU);
        float halfWidth = 26 + breathe * .55f * (1 - settle);
        bounds.set(-halfWidth, -32, halfWidth, 32);
        fill.setShader(glass);
        canvas.drawRoundRect(bounds, 21, 21, fill);
        fill.setShader(null);
        int clipped = canvas.save();
        path.reset();
        path.addRoundRect(bounds, 21, 21, Path.Direction.CW);
        canvas.clipPath(path);
        light.setShader(aurora);
        light.setStyle(Paint.Style.STROKE);
        light.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 3; i++) {
            float x = -18 + i * 17;
            float bend = 6 * (float) Math.sin(phase * TAU + i * .8f) * (1 - settle);
            float open = state == 1 ? settle * (i == 0 ? -18 : 18) : 0;
            float fade = state == 0 ? 1 : 1 - .78f * settle;
            path.reset();
            path.moveTo(x + open, -38);
            path.cubicTo(x + 17 + bend + open, -12, x - 12 + bend + open, 9, x + open + 9, 38);
            for (int layer = 4; layer >= 1; layer--) {
                light.setStrokeWidth(layer * layer * .75f);
                light.setAlpha(Math.round((12 + (4 - layer) * 8) * fade));
                canvas.drawPath(path, light);
            }
        }
        light.setShader(null);
        light.setStyle(Paint.Style.FILL);
        light.setAlpha(255);
        canvas.restoreToCount(clipped);
        line.setShader(edge);
        line.setStrokeWidth(.85f);
        line.setAlpha(255);
        canvas.drawRoundRect(bounds, 21, 21, line);
        line.setShader(null);
        int color = stateColor(WHITE, state, progress);
        // The lock is the anchor: light parts around it as the shackle opens.
        lock(canvas, 0, 0, 1, state == 1 ? smooth(progress) : 0, color);
        stroke(canvas, -8, 25, 8, 25, .8f, alpha(color, 45));
    }

    // A single inclined orbit. The far half is dimmer, the foreground trail catches the light.
    private void orbit(Canvas canvas, int state, float phase, float progress) {
        float settle = smooth(progress);
        int accent = stateColor(GOLD, state, progress);
        int saved = canvas.save();
        canvas.rotate(-24 + 8 * settle);
        bounds.set(-39, -22, 39, 22);
        fill.setColor(DARK);
        canvas.drawOval(bounds, fill);
        line.setStrokeWidth(.7f);
        line.setColor(alpha(GOLD, 56));
        canvas.drawOval(bounds, line);
        bounds.set(-35, -17, 35, 17);
        line.setColor(alpha(accent, 76));
        canvas.drawArc(bounds, 180, 180, false, line);
        line.setColor(alpha(accent, 145));
        canvas.drawArc(bounds, 0, 180, false, line);
        // Segments follow the captured phase continuously into a short settling orbit.
        float angle = phase * 360 + 55 * settle;
        float orbitAlpha = 1 - .78f * settle;
        for (int i = 17; i >= 0; i--) {
            float a = angle - i * 3.5f;
            double radians = Math.toRadians(a);
            float depth = .7f + .3f * (float) Math.sin(radians);
            line.setStrokeWidth(1.1f + .5f * depth);
            line.setColor(alpha(accent, Math.round((170 - i * 8) * orbitAlpha * depth)));
            canvas.drawArc(bounds, a - 3, 3.2f, false, line);
        }
        double a = Math.toRadians(angle);
        float x = 35 * (float) Math.cos(a), y = 17 * (float) Math.sin(a);
        float radius = (1.7f + .4f * (float) Math.sin(a)) * (1 - .7f * settle);
        disk(canvas, x, y, radius + 2.2f, alpha(accent, Math.round(17 * orbitAlpha)));
        disk(canvas, x, y, radius, alpha(WHITE, Math.round(255 * orbitAlpha)));
        canvas.restoreToCount(saved);
        float seed = 1 - smooth(progress / .65f);
        star(canvas, 0, 0, 6.5f + .7f * (float) Math.sin(phase * TAU), alpha(WHITE, Math.round(245 * seed)));
        disk(canvas, -20, -13, .75f, alpha(GOLD, 132));
        disk(canvas, 24, 8, .65f, alpha(GOLD, 110));
        if (state == 1) check(canvas, 0, 0, smooth((progress - .18f) / .75f), MINT, 1.9f);
        else if (state == 2) {
            float p = smooth((progress - .15f) / .7f);
            stroke(canvas, -6 * p, 0, 6 * p, 0, 2, AMBER);
        }
    }

    // Architectural corners, a single travelling hairline and a quiet mechanical lock.
    private void outline(Canvas canvas, int state, float phase, float progress) {
        float settle = smooth(progress);
        int accent = stateColor(WHITE, state, progress);
        bounds.set(-33, -28, 33, 28);
        fill.setColor(0xee0b1017);
        canvas.drawRoundRect(bounds, 14, 14, fill);
        float gap = state == 1 ? 3 * settle : 0;
        line.setColor(alpha(accent, 215));
        line.setStrokeWidth(1.25f);
        for (int i = 0; i < 4; i++) {
            int corner = canvas.save();
            canvas.scale(i % 2 == 0 ? 1 : -1, i < 2 ? 1 : -1);
            path.reset();
            path.moveTo(-14 - gap, -23 - gap);
            path.lineTo(-22 - gap, -23 - gap);
            path.quadTo(-28 - gap, -23 - gap, -28 - gap, -17 - gap);
            path.lineTo(-28 - gap, -10 - gap);
            canvas.drawPath(path, line);
            canvas.restoreToCount(corner);
        }
        float sweep = .5f - .5f * (float) Math.cos(phase * TAU);
        float scanY = -17 + 34 * sweep;
        float fade = state == 0 ? 1 : 1 - smooth(progress / .45f);
        stroke(canvas, -15, scanY, 15, scanY, 2.7f, alpha(ICE, Math.round(13 * fade)));
        stroke(canvas, -13, scanY, 13, scanY, .7f, alpha(ICE, Math.round(155 * fade)));
        float details = 1 - smooth(progress / .55f);
        stroke(canvas, -7, -5, -4, -5, 1.2f, alpha(WHITE, Math.round(170 * details)));
        stroke(canvas, 4, -5, 7, -5, 1.2f, alpha(WHITE, Math.round(170 * details)));
        stroke(canvas, 0, -1, 0, 4, .9f, alpha(WHITE, Math.round(100 * details)));
        if (state == 1) check(canvas, 0, 0, smooth((progress - .15f) / .7f), MINT, 1.6f);
        else if (state == 2) stroke(canvas, -6 * settle, 0, 6 * settle, 0, 1.6f, AMBER);
    }

    // A 9 x 5 phosphor matrix. Light moves across fixed points; outcomes settle into pixels.
    private void matrix(Canvas canvas, int state, float phase, float progress) {
        bounds.set(-47, -24, 47, 24);
        fill.setShader(panel);
        canvas.drawRoundRect(bounds, 12, 12, fill);
        fill.setShader(null);
        line.setShader(edge);
        line.setStrokeWidth(.75f);
        line.setAlpha(150);
        canvas.drawRoundRect(bounds, 12, 12, line);
        line.setShader(null);
        line.setAlpha(255);
        for (int row = 0; row < 5; row++) {
            for (int col = 0; col < 9; col++) {
                float x = (col - 4) * 7.5f;
                float y = (row - 2) * 6.6f;
                double wave = phase * TAU - col * .5 + row * .48;
                float signal = (float) Math.pow(.5 + .5 * Math.cos(wave), 5);
                float active = state == 1 ? (checkPixel(row, col) ? 1 : 0)
                        : state == 2 ? (crossPixel(row, col) ? 1 : 0) : signal;
                float p = smooth((progress - col * .025f) / .72f);
                float level = signal + (active - signal) * p;
                int accent = stateColor(ICE, state, p);
                disk(canvas, x, y, 1.65f, alpha(WHITE, 18));
                disk(canvas, x, y, 2.65f, alpha(accent, Math.round(13 * level)));
                disk(canvas, x, y, 1.25f + .4f * level, alpha(accent, Math.round(20 + 235 * level)));
                disk(canvas, x - .25f, y - .3f, .48f, alpha(WHITE, Math.round(100 * level)));
            }
        }
        stroke(canvas, -32, -21.3f, 32, -21.3f, .55f, alpha(WHITE, 30));
    }

    private static boolean checkPixel(int row, int col) {
        return (row == 1 && col == 7) || (row == 2 && (col == 2 || col == 6))
                || (row == 3 && (col == 3 || col == 5)) || (row == 4 && col == 4);
    }
    private static boolean crossPixel(int row, int col) {
        return col == row + 2 || col == 6 - row;
    }

    private void lock(Canvas canvas, float x, float y, float scale, float open, int color) {
        int saved = canvas.save();
        canvas.translate(x, y);
        canvas.scale(scale, scale);
        line.setStrokeWidth(1.65f);
        line.setColor(color);
        float lift = 3 * open;
        path.reset();
        path.moveTo(6.2f, -2);
        path.lineTo(6.2f, -7.5f - lift);
        path.cubicTo(6.2f, -16 - lift, -6.2f, -16 - lift, -6.2f, -7.5f - lift);
        path.lineTo(-6.2f, -2 - 7 * open);
        canvas.drawPath(path, line);
        bounds.set(-10, -2, 10, 13);
        canvas.drawRoundRect(bounds, 4, 4, line);
        disk(canvas, 0, 4.3f, 1.2f, color);
        stroke(canvas, 0, 5, 0, 7.8f, 1.15f, color);
        canvas.restoreToCount(saved);
    }

    private void star(Canvas canvas, float x, float y, float radius, int color) {
        fill.setColor(color);
        path.reset();
        path.moveTo(x, y - radius);
        path.quadTo(x + 1.4f, y - 1.4f, x + radius, y);
        path.quadTo(x + 1.4f, y + 1.4f, x, y + radius);
        path.quadTo(x - 1.4f, y + 1.4f, x - radius, y);
        path.quadTo(x - 1.4f, y - 1.4f, x, y - radius);
        canvas.drawPath(path, fill);
    }

    private void check(Canvas canvas, float x, float y, float amount, int color, float width) {
        line.setColor(color);
        line.setStrokeWidth(width);
        path.reset();
        path.moveTo(x - 8, y);
        if (amount < .36f) path.lineTo(x - 8 + 6 * amount / .36f, y + 6 * amount / .36f);
        else {
            path.lineTo(x - 2, y + 6);
            float p = (amount - .36f) / .64f;
            path.lineTo(x - 2 + 12 * p, y + 6 - 14 * p);
        }
        canvas.drawPath(path, line);
    }

    private void disk(Canvas c, float x, float y, float r, int color) {
        fill.setColor(color);
        c.drawCircle(x, y, r, fill);
    }
    private void stroke(Canvas c, float x1, float y1, float x2, float y2, float w, int color) {
        line.setColor(color);
        line.setStrokeWidth(w);
        c.drawLine(x1, y1, x2, y2, line);
    }
    private static int stateColor(int initial, int state, float amount) {
        int target = state == 1 ? MINT : state == 2 ? AMBER : initial;
        float p = smooth(amount);
        return Color.rgb(Math.round(Color.red(initial) + (Color.red(target) - Color.red(initial)) * p),
                Math.round(Color.green(initial) + (Color.green(target) - Color.green(initial)) * p),
                Math.round(Color.blue(initial) + (Color.blue(target) - Color.blue(initial)) * p));
    }
    private static int alpha(int color, int a) { return (color & 0x00ffffff) | (a << 24); }
    private static float smooth(float value) {
        float p = Math.max(0, Math.min(1, value));
        return p * p * (3 - 2 * p);
    }
}
