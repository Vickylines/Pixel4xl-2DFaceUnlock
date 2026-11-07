package com.yusd.pixel2dface;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.animation.PathInterpolator;

/** Face-first artwork for the four additional styles; all geometry is retained between frames. */
final class LockscreenStyleRenderer {
    private static final int WHITE = 0xfffff6e5, GOLD = 0xffefbb66;
    private static final int SILVER = 0xffe3ecf4, MINT = 0xffb7f0d4, CORAL = 0xfff0b29c;
    private static final double TAU = Math.PI * 2;
    private static final int POINTS = 48;
    private static final PathInterpolator SETTLE = new PathInterpolator(.23f, 1, .32f, 1);
    private static final float[] CONTOUR = {
            0,-25, 13,-25,20,-17,20,-4, 20,12,12,25,0,25,
            -12,25,-20,12,-20,-4, -20,-17,-13,-25,0,-25};
    private static final float[] SIGNATURE = {
            -12,0, -12,0,-12,0,-10,2, -8,4,-6,6,-4,8,
            -1,5,5,-2,9,-6, 10,-7,12,-9,14,-11};
    private static final float[] CROSS = {
            -10,-10, -9,-9,-7,-7,-5,-5, -3,-3,-1,-1,0,0,
            1,1,3,3,5,5, 7,7,9,9,10,10};

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ambient = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path head = new Path(), features = new Path(), mouth = new Path();
    private final Path path = new Path(), segment = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final RectF bounds = new RectF();
    private final Matrix lightPosition = new Matrix();
    private final float[] position = new float[2], morph = new float[CONTOUR.length];
    private final float[] dotX = new float[POINTS], dotY = new float[POINTS];
    private final LinearGradient glass = new LinearGradient(-20,-24,20,25,
            new int[]{0x12e3f3ff,0x36b1c9d2,0x04f0bb60}, null, Shader.TileMode.CLAMP);
    private final LinearGradient curtain = new LinearGradient(-12,0,12,0,
            new int[]{0x00efbb66,0x18efbb66,0x80ffe1a1,0x00efbb66},
            new float[]{0,.35f,.8f,1}, Shader.TileMode.CLAMP);
    private final LinearGradient silver = new LinearGradient(-22,-25,22,25,
            new int[]{0xfff6fbff,0xffb5c9d7,0xffffe6b4}, null, Shader.TileMode.CLAMP);

    LockscreenStyleRenderer() {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        ambient.setShader(new RadialGradient(0,0,43,
                new int[]{0x50101820,0x30101820,0x00101820},
                new float[]{0,.55f,1}, Shader.TileMode.CLAMP));
        contour(head,CONTOUR);
        features.moveTo(-8,-7); features.lineTo(-8,-3);
        features.moveTo(8,-7); features.lineTo(8,-3);
        features.moveTo(1,-6); features.lineTo(1,2);
        features.quadTo(1,4,-3,4);
        mouth.moveTo(-7,11); mouth.cubicTo(-3,15,4,15,8,10);
        // Stable facial landmarks, not a spinning particle sphere. Features stay legible at rest.
        sample(head,0,28,true);
        path.moveTo(-8,-7); path.lineTo(-8,-3); sample(path,28,3,false);
        path.reset(); path.moveTo(8,-7); path.lineTo(8,-3); sample(path,31,3,false);
        path.reset(); path.moveTo(1,-6); path.lineTo(1,2); path.quadTo(1,4,-3,4);
        sample(path,34,5,false);
        sample(mouth,39,9,false);
    }

    void draw(Canvas canvas, int style, int state, float phase, float progress) {
        canvas.drawCircle(0,0,43,ambient);
        switch (style) {
            case AnimationStyle.GLOW: curtain(canvas,state,phase,progress); break;
            case AnimationStyle.ORBIT: orbit(canvas,state,phase,progress); break;
            case AnimationStyle.MINIMAL: trace(canvas,state,phase,progress); break;
            case AnimationStyle.DOTS: constellation(canvas,state,phase,progress); break;
            default: throw new IllegalArgumentException("Unknown accent style: " + style);
        }
    }

    // A light curtain crosses a glass face. The face remains complete throughout the scan.
    private void curtain(Canvas c, int state, float phase, float progress) {
        float p = settle(progress), faceOpacity = 1 - settle(progress / .4f);
        float travel = -(float) Math.cos(phase * TAU);
        int saved = c.save();
        c.scale(1 - .09f * p,1 - .09f * p);
        fill.setShader(glass); fill.setAlpha(Math.round(255 * faceOpacity));
        c.drawPath(head,fill);
        fill.setShader(null); fill.setAlpha(255);
        face(c,SILVER,1.35f,faceOpacity);
        int clip = c.save();
        c.clipPath(head);
        lightPosition.setTranslate(23 * travel,0);
        curtain.setLocalMatrix(lightPosition);
        fill.setShader(curtain); fill.setAlpha(Math.round(255 * faceOpacity));
        c.drawRect(-21,-26,21,26,fill);
        fill.setShader(null); fill.setAlpha(255);
        // Only the landmarks inside the illuminated slice gain an extra highlight.
        c.clipRect(23 * travel - 4,-26,23 * travel + 5,26);
        face(c,WHITE,2,faceOpacity);
        c.restoreToCount(clip);
        c.restoreToCount(saved);
        int tint = blend(GOLD,state == 2 ? CORAL : MINT,p);
        brackets(c,28 + 3 * p,29 + 2 * p,tint,.7f + .3f * p);
        outcome(c,state,progress,2.1f);
    }

    // An upright face holds its position while a slender orbit moves outside the facial features.
    private void orbit(Canvas c, int state, float phase, float progress) {
        float p = settle(progress), angle = phase * 360;
        float rx = mix(27,25,p), ry = mix(34,25,p);
        int accent = blend(GOLD,state == 2 ? CORAL : MINT,p);
        int saved = c.save();
        c.rotate(-24 * (1 - p));
        bounds.set(-rx,-ry,rx,ry);
        line.setStrokeWidth(.8f); line.setColor(alpha(accent,70));
        c.drawOval(bounds,line);
        orbitTrail(c,angle,rx,ry,1 - p);
        line.setStrokeWidth(1.25f); line.setColor(alpha(accent,Math.round(220 * p)));
        c.drawOval(bounds,line);
        c.restoreToCount(saved);
        saved = c.save();
        float turn = 1.25f * (float) Math.sin(phase * TAU) * (1 - p);
        c.translate(turn,0); c.scale(.85f,.85f);
        face(c,WHITE,1.75f,1 - settle(progress / .4f));
        c.restoreToCount(saved);
        outcome(c,state,progress,1.9f);
    }

    private void orbitTrail(Canvas c, float angle, float rx, float ry, float opacity) {
        for (int i = 27; i >= 0; i--) {
            line.setColor(alpha(WHITE,Math.round(245 * (1 - i / 28f) * opacity)));
            line.setStrokeWidth(1.45f);
            c.drawArc(bounds,angle - i * 2.5f - 2.8f,3,false,line);
        }
        float x = rx * (float) Math.cos(Math.toRadians(angle));
        float y = ry * (float) Math.sin(Math.toRadians(angle));
        disk(c,x,y,4,alpha(GOLD,Math.round(28 * opacity)));
        disk(c,x,y,1.9f,alpha(WHITE,Math.round(255 * opacity)));
    }

    // The contour is the transition: a drawn face folds directly into the outcome glyph.
    private void trace(Canvas c, int state, float phase, float progress) {
        float p = settle(progress);
        float[] target = state == 2 ? CROSS : SIGNATURE;
        for (int i = 0; i < CONTOUR.length; i++) morph[i] = mix(CONTOUR[i],target[i],p);
        contour(path,morph);
        strokePath(c,path,4.3f,alpha(0xff0c1722,125));
        line.setShader(silver); line.setStrokeWidth(1.45f + .45f * p);
        line.setAlpha(220); c.drawPath(path,line);
        line.setShader(null); line.setAlpha(255);
        if (state != 0) strokePath(c,path,1.9f,alpha(state == 2 ? CORAL : MINT,Math.round(255 * p)));
        float featuresOpacity = 1 - settle(progress / .45f);
        strokePath(c,features,1.9f,alpha(SILVER,Math.round(250 * featuresOpacity)));
        strokePath(c,mouth,1.65f,alpha(SILVER,Math.round(250 * featuresOpacity)));
        measure.setPath(path,false);
        float length = measure.getLength();
        segment.reset();
        measure.getSegment(Math.max(0,phase - .25f) * length,phase * length,segment,true);
        if (phase < .25f) measure.getSegment((1 + phase - .25f) * length,length,segment,true);
        strokePath(c,segment,2.2f,alpha(WHITE,Math.round(255 * (1 - p))));
        measure.getPosTan(phase * length,position,null);
        disk(c,position[0],position[1],2.9f,alpha(SILVER,Math.round(32 * (1 - p))));
        if (state == 2) stroke(c,-10 * p,10 * p,10 * p,-10 * p,1.9f,alpha(CORAL,Math.round(255 * p)));
    }

    // A facial landmark constellation, with a wave of light rather than random point motion.
    private void constellation(Canvas c, int state, float phase, float progress) {
        float turn = (float) Math.sin(phase * TAU);
        float scanY = -28 * (float) Math.cos(phase * TAU);
        for (int i = 0; i < POINTS; i++) {
            float x = dotX[i], y = dotY[i];
            float depth = i < 28 ? .15f : 1;
            x += turn * depth * 1.6f;
            float t = i / (float) (POINTS - 1), tx, ty;
            if (state == 2) {
                float leg = (i % (POINTS / 2)) / (float) (POINTS / 2 - 1);
                tx = -11 + 22 * leg; ty = i < POINTS / 2 ? tx : -tx;
            } else if (t < .36f) {
                tx = mix(-12,-4,t / .36f); ty = mix(0,8,t / .36f);
            } else {
                tx = mix(-4,14,(t - .36f) / .64f); ty = mix(8,-11,(t - .36f) / .64f);
            }
            float p = state == 0 ? 0 : settle((progress - (y + 25) * .002f) / .84f);
            float light = Math.max(0,1 - Math.abs(y - scanY) / 15);
            float luminance = mix(.72f + .28f * light,i % 3 == 0 ? 1 : 0,p);
            float radius = mix(i < 28 ? 1.05f + .3f * light : 1.25f,1.1f,p);
            int color = blend(i < 28 ? GOLD : WHITE,state == 2 ? CORAL : MINT,p);
            x = mix(x,tx,p); y = mix(y,ty,p);
            disk(c,x,y,radius * 2.2f,alpha(color,Math.round(18 * luminance)));
            disk(c,x,y,radius + .55f,alpha(0xff101820,Math.round(150 * luminance)));
            disk(c,x,y,radius,alpha(color,Math.round(255 * luminance)));
        }
    }

    private void face(Canvas c, int color, float width, float opacity) {
        strokePath(c,head,width,alpha(color,Math.round(220 * opacity)));
        strokePath(c,features,width + .4f,alpha(color,Math.round(255 * opacity)));
        strokePath(c,mouth,width,alpha(color,Math.round(255 * opacity)));
    }
    private void brackets(Canvas c, float x, float y, int color, float opacity) {
        path.reset();
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                path.moveTo(sx * (x - 7),sy * y);
                path.lineTo(sx * (x - 3),sy * y);
                path.quadTo(sx * x,sy * y,sx * x,sy * (y - 3));
                path.lineTo(sx * x,sy * (y - 7));
            }
        }
        strokePath(c,path,1.6f,alpha(color,Math.round(255 * opacity)));
    }
    private void outcome(Canvas c, int state, float progress, float width) {
        if (state == 0) return;
        float amount = settle((progress - .12f) / .78f);
        if (state == 1) {
            path.reset(); path.moveTo(-10,0);
            if (amount < .35f) path.lineTo(-10 + 7 * amount / .35f,7 * amount / .35f);
            else {
                path.lineTo(-3,7);
                float p = (amount - .35f) / .65f;
                path.lineTo(-3 + 15 * p,7 - 17 * p);
            }
            strokePath(c,path,width,alpha(MINT,Math.round(255 * amount)));
        } else {
            stroke(c,-7 * amount,-7 * amount,7 * amount,7 * amount,width,alpha(CORAL,Math.round(255 * amount)));
            stroke(c,7 * amount,-7 * amount,-7 * amount,7 * amount,width,alpha(CORAL,Math.round(255 * amount)));
        }
    }
    private static void contour(Path target, float[] points) {
        target.reset(); target.moveTo(points[0],points[1]);
        for (int i = 2; i < points.length; i += 6)
            target.cubicTo(points[i],points[i + 1],points[i + 2],points[i + 3],points[i + 4],points[i + 5]);
    }
    private void sample(Path source, int start, int count, boolean closed) {
        measure.setPath(source,closed);
        for (int i = 0; i < count; i++) {
            measure.getPosTan(measure.getLength() * i / (closed ? count : count - 1),position,null);
            dotX[start + i] = position[0]; dotY[start + i] = position[1];
        }
    }
    private void disk(Canvas c, float x, float y, float r, int color) {
        fill.setColor(color); c.drawCircle(x,y,r,fill);
    }
    private void strokePath(Canvas c, Path shape, float width, int color) {
        line.setColor(alpha(0xff101820,Math.round(Color.alpha(color) * .6f)));
        line.setStrokeWidth(width + 1.25f); c.drawPath(shape,line);
        line.setColor(color); line.setStrokeWidth(width); c.drawPath(shape,line);
    }
    private void stroke(Canvas c, float x1, float y1, float x2, float y2, float width, int color) {
        line.setColor(color); line.setStrokeWidth(width); c.drawLine(x1,y1,x2,y2,line);
    }
    private static float clamp(float p) { return Math.max(0,Math.min(1,p)); }
    private static float settle(float p) { return SETTLE.getInterpolation(clamp(p)); }
    private static float mix(float from, float to, float p) { return from + (to - from) * p; }
    private static int alpha(int color, int a) { return (color & 0x00ffffff) | (a << 24); }
    private static int blend(int from, int to, float p) {
        return Color.rgb(Math.round(mix(Color.red(from),Color.red(to),p)),
                Math.round(mix(Color.green(from),Color.green(to),p)),
                Math.round(mix(Color.blue(from),Color.blue(to),p)));
    }
}
