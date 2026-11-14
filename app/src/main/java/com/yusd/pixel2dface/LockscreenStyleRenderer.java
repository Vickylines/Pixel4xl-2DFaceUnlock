package com.yusd.pixel2dface;

import static com.yusd.pixel2dface.UnlockFaceAnimationView.FAILURE;
import static com.yusd.pixel2dface.UnlockFaceAnimationView.SEARCHING;
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

/** Draws the four additional styles from reusable geometry in lock-screen density units. */
final class LockscreenStyleRenderer {
    private static final int INK = 0xff0c1421;
    private static final int IVORY = 0xfffdf6eb;
    private static final int SILVER = 0xffd0e2f2;
    private static final int MINT = 0xffabebd4;
    private static final int CORAL = 0xfff5b29d;
    private static final double TAU = 2 * Math.PI;
    private static final PathInterpolator SETTLE = new PathInterpolator(.23f, 1f, .32f, 1f);

    private static final float[] JAW = {
            -15.8f,-.7f, -15.6f,5.7f,-12.3f,12.2f,-7.9f,15.7f,
            -4.5f,18.5f,-2,19.4f,.1f,19.4f, 2.6f,19.4f,5.6f,18.1f,8.8f,15.3f,
            13,11.4f,15.7f,5.5f,16.1f,-.7f};
    private static final float[] TICK = {
            -10,-.4f, -8.4f,1.2f,-6.8f,2.8f,-5.2f,4.4f,
            -3.9f,5.7f,-2.6f,7,-1.3f,8.3f, .8f,6.0f,2.9f,3.7f,5,.9f,
            7,-1.9f,9.3f,-4.3f,11.6f,-6.6f};
    private static final float[] SLASH = {
            -7.6f,-7.6f, -6,-6,-5,-5,-4,-4, -3,-3,-1.25f,-1.25f,0,0,
            1.25f,1.25f,3,3,4,4, 5,5,6,6,7.6f,7.6f};

    private static final int DOT_ROWS = 7;
    private static final int DOT_COLUMNS = 3;
    private static final int DOTS_PER_SIDE = 18;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path face = new Path();
    private final Path leftRim = new Path();
    private final Path rightRim = new Path();
    private final Path eyes = new Path();
    private final Path nose = new Path();
    private final Path mouth = new Path();
    private final Path profile = new Path();
    private final Path profileRim = new Path();
    private final Path profileDetails = new Path();
    private final Path forehead = new Path();
    private final Path dotFace = new Path();
    private final Path dotFaceRim = new Path();
    private final Path jaw = new Path();
    private final Path scratch = new Path();
    private final Path light = new Path();
    private final PathMeasure jawLength;
    private final PathMeasure tickLength;
    private final float[] jawCoordinates = new float[JAW.length];
    private final RectF orbit = new RectF();
    private final Matrix shaderPosition = new Matrix();

    private final LinearGradient faceGlass = new LinearGradient(-18,-23,22,24,
            new int[]{0xf3242a35,0xf2131c2a,0xed0b1420},null,Shader.TileMode.CLAMP);
    private final LinearGradient bronze = new LinearGradient(-20,-19,-2,22,
            new int[]{0xffc49782,0xfff8ddbc,0xff8f5e50,0xfff3ccb0},
            new float[]{0,.35f,.67f,1},Shader.TileMode.CLAMP);
    private final LinearGradient steel = new LinearGradient(15,-23,16,23,
            new int[]{0xffecf3fc,0xff93aabc,0xff506781,0xffc1d6e5},
            new float[]{0,.34f,.68f,1},Shader.TileMode.CLAMP);
    private final LinearGradient beam = new LinearGradient(-14,0,14,0,
            new int[]{0x00f9d4ad,0x43f7d4b2,0xc0ffedcd,0x14ffe6be,0x00ffe6be},
            new float[]{0,.34f,.57f,.78f,1},Shader.TileMode.CLAMP);
    private final LinearGradient profileGlass = new LinearGradient(-20,-20,17,24,
            new int[]{0xff405669,0xff243345,0xff101a29},
            new float[]{0,.40f,1},Shader.TileMode.CLAMP);
    private final LinearGradient silverLine = new LinearGradient(-16,-19,17,19,
            new int[]{0xffecf2fe,0xff738aa3,0xffdbe6f5},
            new float[]{0,.52f,1},Shader.TileMode.CLAMP);

    private final LinearGradient darkGlass = new LinearGradient(-11,-19,13,16,
            new int[]{0xff2b435a,0xff182a3b,0xff101a27},
            new float[]{0,.43f,1},Shader.TileMode.CLAMP);
    private final LinearGradient glassRim = new LinearGradient(-12,-18,13,14,
            new int[]{0xffe3eff0,0xffa2bdcf,0xff3b5d76},
            new float[]{0,.41f,1},Shader.TileMode.CLAMP);
    private final float[] dotX = new float[2 * DOTS_PER_SIDE];
    private final float[] dotY = new float[2 * DOTS_PER_SIDE];
    private final byte[] dotDepth = new byte[2 * DOTS_PER_SIDE];

    LockscreenStyleRenderer() {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        createFace();
        createProfile();
        contour(jaw,JAW);
        jawLength = new PathMeasure(jaw,false);
        contour(scratch,TICK);
        tickLength = new PathMeasure(new Path(scratch),false);
        createDots();
    }

    void draw(Canvas canvas, int style, int state, float phase, float progress) {
        switch (style) {
            case AnimationStyle.GLOW: drawCurtain(canvas,state,phase,progress); break;
            case AnimationStyle.ORBIT: drawOrbit(canvas,state,phase,progress); break;
            case AnimationStyle.MINIMAL: drawTrace(canvas,state,phase,progress); break;
            case AnimationStyle.DOTS: drawDots(canvas,state,phase,progress); break;
            default: throw new IllegalArgumentException("Unknown lock-screen style: " + style);
        }
    }

    private void createFace() {
        face.moveTo(0,-22.4f);
        face.cubicTo(13.5f,-22,20.5f,-12.1f,20.1f,-.3f);
        face.cubicTo(19.6f,10.1f,12.5f,20.8f,.7f,22.6f);
        face.cubicTo(-10.9f,20.7f,-19.7f,10.2f,-20.3f,-.9f);
        face.cubicTo(-21,-12.8f,-13.5f,-22,0,-22.4f);
        face.close();

        leftRim.moveTo(-2.6f,-22.7f);
        leftRim.cubicTo(-15,-22,-20,-13.6f,-20.2f,-4.4f);
        leftRim.cubicTo(-20.1f,7.9f,-14.4f,17.5f,-4.1f,21.2f);
        leftRim.cubicTo(-10.9f,15.1f,-13.8f,7.7f,-14.5f,-2.8f);
        leftRim.cubicTo(-15.1f,-12.1f,-10.6f,-19.6f,-2.6f,-22.7f);
        leftRim.close();

        rightRim.moveTo(6.6f,-22);
        rightRim.cubicTo(16.4f,-18.9f,20.8f,-10.2f,20.3f,.2f);
        rightRim.cubicTo(19.7f,10.4f,13.3f,19.6f,4.1f,21.2f);
        rightRim.cubicTo(10.4f,16.8f,14.1f,9.4f,14.6f,1.5f);
        rightRim.cubicTo(15.2f,-8.5f,13.2f,-17.1f,6.6f,-22);
        rightRim.close();

        eyes.moveTo(-6.8f,-6.2f);
        eyes.lineTo(-6.8f,-3.5f);
        eyes.moveTo(6.8f,-6.2f);
        eyes.lineTo(6.8f,-3.5f);
        nose.moveTo(.4f,-2.1f);
        nose.lineTo(-1,3.5f);
        nose.quadTo(-1.4f,4.9f,.4f,5);
        nose.lineTo(2.2f,5);
        mouth.moveTo(-4,10.6f);
        mouth.quadTo(0,12.2f,4,10.6f);

        forehead.moveTo(-15.8f,-5.4f);
        forehead.cubicTo(-14.3f,-16.7f,-6.4f,-19.9f,.2f,-20);
        forehead.cubicTo(7.4f,-20,14.2f,-16.6f,16.2f,-5.4f);
    }

    private void createProfile() {
        profileRim.moveTo(-9.7f,-20.5f);
        profileRim.cubicTo(-.2f,-24.4f,9.4f,-20.8f,12.9f,-11.7f);
        profileRim.cubicTo(13.8f,-9.3f,13.1f,-6.7f,14.5f,-4.3f);
        profileRim.lineTo(19.3f,.5f);
        profileRim.quadTo(20.5f,2.2f,18,2.8f);
        profileRim.lineTo(14.6f,3.3f);
        profileRim.quadTo(13.5f,3.6f,14.1f,5.4f);
        profileRim.lineTo(15.1f,6.9f);
        profileRim.quadTo(16,8.1f,14.3f,9.2f);
        profileRim.quadTo(15.2f,10.3f,14.3f,11.4f);
        profileRim.lineTo(13.3f,12.4f);
        profileRim.quadTo(13.3f,16.7f,9.1f,18.5f);
        profileRim.cubicTo(5.8f,19.7f,2.3f,17.9f,-.4f,16);
        profile.set(profileRim);
        profile.lineTo(-3.3f,21.7f);
        profile.cubicTo(-6.5f,20.4f,-8.3f,18.9f,-10.5f,17);
        profile.cubicTo(-17.8f,12,-21.7f,4.7f,-21.3f,-3.6f);
        profile.cubicTo(-21.1f,-10.9f,-16.8f,-17.6f,-9.7f,-20.5f);
        profile.close();

        profileDetails.moveTo(6.6f,-7.7f);
        profileDetails.quadTo(8.7f,-8.5f,10.9f,-7.9f);
        profileDetails.moveTo(10,7.7f);
        profileDetails.quadTo(12,7.4f,14.1f,8.5f);
    }

    private void drawCurtain(Canvas canvas, int state, float phase, float progress) {
        float amount = settle(progress);
        float remaining = 1 - smooth(.03f,.44f,progress);
        float rimOffset = (state == FAILURE ? -3f : 6.8f) * amount;
        float scan = 23 * (float) Math.sin(phase * TAU - .55f);
        if (remaining > 0) {
            canvas.save();
            float scale = mix(1,.92f,amount);
            canvas.scale(scale,scale);
            shaderFill(canvas,face,faceGlass,remaining);
            drawRim(canvas,leftRim,bronze,rimOffset,remaining);
            drawRim(canvas,rightRim,steel,-rimOffset,remaining);
            canvas.save();
            canvas.clipPath(face);
            shaderPosition.setRotate(-22);
            shaderPosition.postTranslate(mix(scan,0,amount),0);
            beam.setLocalMatrix(shaderPosition);
            fill.setShader(beam);
            fill.setAlpha(alpha(.31f * remaining));
            canvas.drawRect(-22,-23,22,23,fill);
            fill.setShader(null);
            canvas.restore();
            facialFeatures(canvas,IVORY,remaining,phase,false);
            canvas.restore();
        }
        drawOutcome(canvas,state,progress,2.55f,.24f);
    }

    private void drawRim(Canvas canvas, Path rim, Shader material, float offset, float opacity) {
        canvas.save();
        canvas.translate(offset,0);
        stroke(canvas,rim,INK,1.15f,.65f * opacity);
        shaderFill(canvas,rim,material,opacity);
        canvas.restore();
    }

    private void drawOrbit(Canvas canvas, int state, float phase, float progress) {
        float amount = settle(progress);
        float remaining = 1 - smooth(.06f,.43f,progress);
        float width = mix(29,13,amount);
        float height = mix(12.8f,4.5f,amount);
        float angle = phase * 360 + 36 + 32 * amount;
        float tilt = -24 + 15 * amount;
        orbit.set(-width,-height,width,height);
        canvas.save();
        canvas.translate(0,2.5f * remaining);
        canvas.rotate(tilt);
        ink(INK,1.75f,.34f * remaining);
        canvas.drawOval(orbit,line);
        ink(SILVER,.8f,.33f * remaining);
        canvas.drawOval(orbit,line);
        trail(canvas,angle,width,height,remaining,false);
        canvas.restore();

        if (remaining > 0) {
            canvas.save();
            canvas.translate(1.2f * amount,0);
            stroke(canvas,profile,INK,1.20f,.72f * remaining);
            shaderFill(canvas,profile,profileGlass,remaining);
            line.setShader(silverLine);
            line.setAlpha(alpha(.96f * remaining));
            line.setStrokeWidth(1.23f);
            canvas.drawPath(profileRim,line);
            line.setShader(null);
            stroke(canvas,profileDetails,SILVER,1.11f,.88f * remaining);
            canvas.restore();
        }

        canvas.save();
        canvas.translate(0,2.5f * remaining);
        canvas.rotate(tilt);
        trail(canvas,angle,width,height,1 - smooth(.14f,.58f,progress),true);
        canvas.restore();
        drawOutcome(canvas,state,progress,2.55f,.28f);
    }

    private void trail(Canvas canvas, float angle, float width, float height,
            float opacity, boolean front) {
        if (opacity <= 0) return;
        line.setStrokeCap(Paint.Cap.BUTT);
        for (int i = 7; i >= 0; i--) {
            float end = angle - i * 7.5f;
            float depth = (float) Math.sin(Math.toRadians(end - 3.75f));
            if ((depth >= 0) == front) {
                float weight = 1 - i / 8f;
                ink(SILVER,.60f + .95f * weight,opacity * weight * (.64f + .27f * depth));
                canvas.drawArc(orbit,end - 7.9f,7.9f,false,line);
            }
        }
        line.setStrokeCap(Paint.Cap.ROUND);
        double radians = Math.toRadians(angle);
        float depth = (float) Math.sin(radians);
        if ((depth >= 0) == front) {
            float x = width * (float) Math.cos(radians);
            float y = height * depth;
            circle(canvas,x,y,2.8f,SILVER,.19f * opacity);
            circle(canvas,x,y,1.45f,IVORY,(.86f + .14f * depth) * opacity);
        }
    }

    private void drawTrace(Canvas canvas, int state, float phase, float progress) {
        float morph = smooth(.02f,.72f,progress);
        float details = 1 - smooth(0,.30f,progress);
        float[] destination = state == FAILURE ? SLASH : TICK;
        for (int i = 0; i < JAW.length; i++) {
            jawCoordinates[i] = mix(JAW[i],destination[i],morph);
        }
        contour(scratch,jawCoordinates);
        stroke(canvas,scratch,INK,3.1f + morph,.72f);
        if (morph < 1) {
            line.setShader(silverLine);
            line.setAlpha(alpha(1 - morph));
            line.setStrokeWidth(1.68f + .83f * morph);
            canvas.drawPath(scratch,line);
            line.setShader(null);
        }
        if (morph > 0) {
            stroke(canvas,scratch,resultColor(state),1.68f + .83f * morph,morph);
        }
        edgedStroke(canvas,forehead,SILVER,1.0f,.61f * details);
        facialFeatures(canvas,IVORY,details,phase,true);

        float sweep = (1 - smooth(0,.24f,progress)) * (float) Math.sin(phase * Math.PI);
        if (sweep > 0) {
            float length = jawLength.getLength();
            float head = phase * length;
            light.rewind();
            jawLength.getSegment(Math.max(0,head - .29f * length),head,light,true);
            stroke(canvas,light,IVORY,2.02f,.81f * sweep);
        }

        if (state == FAILURE) {
            float span = 7.6f * settle((progress - .37f) / .52f);
            scratch.rewind();
            scratch.moveTo(-span,span);
            scratch.lineTo(span,-span);
            edgedStroke(canvas,scratch,CORAL,2.51f,smooth(0,5,span));
        }
    }

    private void facialFeatures(Canvas canvas, int color, float opacity, float phase, boolean edged) {
        if (opacity <= 0) return;
        float blink = smooth(.54f,.59f,phase) * (1 - smooth(.59f,.65f,phase));
        canvas.save();
        canvas.translate(0,-4.85f);
        canvas.scale(1,1 - .57f * blink);
        canvas.translate(0,4.85f);
        if (edged) edgedStroke(canvas,eyes,color,1.48f,opacity);
        else stroke(canvas,eyes,color,1.58f,opacity);
        canvas.restore();
        if (edged) {
            edgedStroke(canvas,nose,color,1.28f,opacity);
            edgedStroke(canvas,mouth,color,1.37f,opacity);
        } else {
            stroke(canvas,nose,color,1.42f,opacity);
            stroke(canvas,mouth,color,1.43f,opacity);
        }
    }

    private void createDots() {
        dotFace.moveTo(-.2f,-17.8f);
        dotFace.cubicTo(7.9f,-17.7f,13.1f,-12.9f,13.1f,-6.3f);
        dotFace.cubicTo(13,.7f,10,8.1f,4.6f,12.3f);
        dotFace.quadTo(1.7f,14.7f,0,14.7f);
        dotFace.quadTo(-2.3f,14.7f,-5.3f,12.3f);
        dotFace.cubicTo(-10,8.6f,-13,.9f,-13.2f,-6.3f);
        dotFace.cubicTo(-13.6f,-13.3f,-7.5f,-17.8f,-.2f,-17.8f);
        dotFace.close();

        dotFaceRim.moveTo(-11.8f,-9.8f);
        dotFaceRim.cubicTo(-10.2f,-14.6f,-5,-17.8f,-.2f,-17.8f);
        dotFaceRim.cubicTo(5.1f,-17.7f,9.2f,-15.6f,11.3f,-12);
        dotFaceRim.moveTo(12.2f,-.3f);
        dotFaceRim.cubicTo(10.8f,4.9f,8.5f,9.2f,4.6f,12.3f);
        dotFaceRim.quadTo(1.7f,14.7f,0,14.7f);
        dotFaceRim.quadTo(-2.3f,14.7f,-5.3f,12.3f);
        dotFaceRim.cubicTo(-7.9f,10.2f,-10.6f,6.1f,-11.7f,2);

        int index = 0;
        for (int side = -1; side <= 1; side += 2) {
            for (int row = 0; row < DOT_ROWS; row++) {
                float y = (row - 3) * 4.65f;
                for (int column = 0; column < DOT_COLUMNS; column++) {
                    // Taper the outside columns to follow the curve of each temple.
                    if (column == 2 && (row == 0 || row == 1 || row == DOT_ROWS - 1)) {
                        continue;
                    }
                    float taper = 2.25f * y * y / 225;
                    dotX[index] = side * (15.9f + column * 3.95f - taper);
                    dotY[index] = y + side * column * 1.2f;
                    dotDepth[index++] = (byte) column;
                }
            }
        }
    }

    private void drawDots(Canvas canvas, int state, float phase, float progress) {
        float faceOpacity = 1 - smooth(.04f,.45f,progress);
        float drift = .55f * (float) Math.sin(phase * TAU);
        float scan = 15 * (float) Math.cos(phase * TAU + .7f);
        float focus = smooth(.055f,.50f,progress);
        float retreat = state == FAILURE ? 5.6f * focus : 0;
        float dustOpacity = 1 - smooth(.20f,.54f,progress);
        int outcomeColor = resultColor(state);

        for (int i = 0; i < dotX.length; i++) {
            int column = dotDepth[i];
            float side = Math.signum(dotX[i]);
            float distance = Math.abs(dotY[i] - scan * side);
            float current = 1 - smooth(0,7.4f,distance);
            float x = dotX[i] + side * (drift + retreat);
            float y = dotY[i] + drift * .75f * side;
            if (state == SUCCESS) {
                x = mix(x,side * 3.2f,focus);
                y = mix(y,.8f,focus);
            }
            float radius = (.93f - .13f * column + .26f * current) * (1 - .31f * focus);
            float opacity = (.68f - .13f * column + .28f * current) * dustOpacity;
            int color = blend(SILVER,IVORY,.65f * current);
            if (progress > 0) color = blend(color,outcomeColor,focus * .7f);
            circle(canvas,x,y,radius + .35f,INK,.61f * opacity);
            circle(canvas,x,y,radius,color,opacity);
        }

        if (faceOpacity > 0) {
            int portrait = canvas.save();
            float scale = mix(1,.93f,settle(progress));
            canvas.scale(scale,scale);
            stroke(canvas,dotFace,INK,1.45f,.59f * faceOpacity);
            shaderFill(canvas,dotFace,darkGlass,faceOpacity);
            line.setShader(glassRim);
            line.setAlpha(alpha(.94f * faceOpacity));
            line.setStrokeWidth(1.02f);
            canvas.drawPath(dotFaceRim,line);
            line.setShader(null);
            int features = canvas.save();
            canvas.scale(.83f,.83f);
            facialFeatures(canvas,0xffedf6f9,faceOpacity,phase,false);
            canvas.restoreToCount(features);
            canvas.restoreToCount(portrait);
        }

        drawOutcome(canvas,state,progress,2.44f,.24f);
    }

    private void drawOutcome(Canvas canvas, int state, float progress, float width, float start) {
        if (state == SEARCHING) return;
        float reveal = settle((progress - start) / (1 - start));
        if (reveal <= 0) return;
        scratch.rewind();
        if (state == SUCCESS) {
            tickLength.getSegment(0,tickLength.getLength() * reveal,scratch,true);
        } else {
            float span = 7.7f * reveal;
            scratch.moveTo(-span,-span);
            scratch.lineTo(span,span);
            scratch.moveTo(span,-span);
            scratch.lineTo(-span,span);
        }
        edgedStroke(canvas,scratch,resultColor(state),width,smooth(0,.37f,reveal));
    }

    private void shaderFill(Canvas canvas, Path path, Shader shader, float opacity) {
        fill.setShader(shader);
        fill.setAlpha(alpha(opacity));
        canvas.drawPath(path,fill);
        fill.setShader(null);
    }

    private void edgedStroke(Canvas canvas, Path path, int color, float width, float opacity) {
        stroke(canvas,path,INK,width + 1.38f,.71f * opacity);
        stroke(canvas,path,color,width,opacity);
    }

    private void stroke(Canvas canvas, Path path, int color, float width, float opacity) {
        ink(color,width,opacity);
        canvas.drawPath(path,line);
    }

    private void ink(int color, float width, float opacity) {
        line.setColor(tint(color,opacity));
        line.setStrokeWidth(width);
    }

    private void circle(Canvas canvas, float x, float y, float radius, int color, float opacity) {
        fill.setColor(tint(color,opacity));
        canvas.drawCircle(x,y,radius,fill);
    }

    private static void contour(Path path, float[] points) {
        path.rewind();
        path.moveTo(points[0],points[1]);
        for (int i = 2; i < points.length; i += 6) {
            path.cubicTo(points[i],points[i+1],points[i+2],points[i+3],points[i+4],points[i+5]);
        }
    }

    private static int resultColor(int state) {
        return state == FAILURE ? CORAL : MINT;
    }

    private static float settle(float progress) {
        return SETTLE.getInterpolation(clamp(progress));
    }

    private static float smooth(float start, float end, float value) {
        float x = clamp((value - start) / (end - start));
        return x * x * (3 - 2 * x);
    }

    private static float clamp(float value) {
        return Math.max(0,Math.min(1,value));
    }

    private static float mix(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    private static int alpha(float opacity) {
        return Math.round(255 * clamp(opacity));
    }

    private static int tint(int color, float opacity) {
        return (color & 0x00ffffff) | (alpha(opacity) << 24);
    }

    private static int blend(int from, int to, float amount) {
        return Color.rgb(Math.round(mix(Color.red(from),Color.red(to),amount)),
                Math.round(mix(Color.green(from),Color.green(to),amount)),
                Math.round(mix(Color.blue(from),Color.blue(to),amount)));
    }
}
