package com.yusd.pixel2dface;

import static com.yusd.pixel2dface.FocusMotion.clamp;
import static com.yusd.pixel2dface.FocusMotion.ease;
import static com.yusd.pixel2dface.FocusRibbon.blend;
import static com.yusd.pixel2dface.FocusRibbon.mix;
import static com.yusd.pixel2dface.UnlockFaceAnimationView.FAILURE;
import static com.yusd.pixel2dface.UnlockFaceAnimationView.SEARCHING;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;

/** Density-independent artwork for the chooser and native lock screen. */
final class LockscreenStyleRenderer {
    private static final int WHITE = 0xfff0f7ff;
    private static final int BLUE = 0xffa9d9ff;
    private static final int GREEN = 0xff96e6a5;
    private static final int FACE_GREEN = 0xffb7f1be;
    private static final int MARK_GREEN = 0xff8fe6a2;
    private static final int INK = 0xff0b1320;
    private static final double TAU = 2 * Math.PI;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final LinearGradient cornerLight = new LinearGradient(-24, -24, -12, -12,
            new int[]{0xfff0f7ff, 0xcbc6e7ff, 0x00a9d9ff},
            new float[]{0, .26f, 1}, Shader.TileMode.CLAMP);
    private final FocusRibbon ribbon = new FocusRibbon();
    private final FocusCheckmark checkmark = new FocusCheckmark(INK);
    private final FocusFailure failure;
    private final IslandSurface surface = new IslandSurface();
    private final FaceHalo halo = new FaceHalo();
    private final IslandGlyph islandGlyph = new IslandGlyph();
    private final int style;

    LockscreenStyleRenderer(float fontScale, int style) {
        this.style = style;
        failure = new FocusFailure(fontScale);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
    }

    float hintWidth(boolean retry) { return failure.pillWidth(retry); }
    float hintY() { return style == AnimationStyle.ISLAND ? IslandSurface.TOP + 21f : 0; }

    void draw(Canvas canvas, int state, float phase, float progress, float age, float duration,
            boolean retry, float retryFrom, float drag, boolean motion) {
        boolean island = style == AnimationStyle.ISLAND;
        boolean disc = style == AnimationStyle.FACE;
        float elapsed = state == SEARCHING ? 0 : progress * duration;
        float collapse = motion && island && state == UnlockFaceAnimationView.SUCCESS
                ? IslandMotion.collapse(elapsed, duration) : 0;
        float open = island ? (retryFrom >= 0 ? 1 : IslandSurface.opening(age)) * (1-collapse) : 1;
        float width = island ? mix(69, IslandSurface.EXPANDED, clamp(open)) : disc ? 65 : 73;
        float height = island ? mix(20.5f, 81, open) : width;
        float y = island ? IslandSurface.TOP + 1.4f * (1-clamp(open)) + height/2 : 0;
        float radius = island ? mix(10.25f,21.5f,clamp(open)) : disc ? width/2 : 21;
        float bridge = retryFrom >= 0 ? 1 - ease(25, 360, age) : 0;
        float stretch = ease(155,770,retryFrom) * bridge;
        if (state == FAILURE) stretch = mix(stretch,1,ease(155,770,elapsed));
        y = mix(y, hintY(), stretch);
        canvas.translate(0, -3.5f * drag);
        canvas.scale(1 + .014f * drag, 1 - .048f * drag);
        surface.draw(canvas, mix(width, hintWidth(retry), stretch), mix(height, 42, stretch),
                y, mix(radius,21,stretch));
        if (bridge > 0) surface.clipContents(canvas);
        canvas.translate(0,y);
        float incoming = retryFrom >= 0 ? ease(130,330,age) : 1;
        float artAlpha = (state == FAILURE ? 1 - ease(0,90,elapsed) : 1) * incoming;
        if (artAlpha > 0) {
            int saved = artAlpha < .9999f
                    ? canvas.saveLayerAlpha(-44,-44,44,44,Math.round(255*artAlpha)) : canvas.save();
            boolean confirmed = state == UnlockFaceAnimationView.SUCCESS;
            if (disc) {
                halo.draw(canvas, phase, confirmed ? progress : 0);
                float face = confirmed ? 1 - ease(0,.32f,progress) : 1;
                if (face > 0) {
                    int inner = canvas.saveLayerAlpha(-19,-19,19,19,Math.round(255*face));
                    canvas.scale(.60f,.60f);
                    drawFace(canvas,SEARCHING,0,0);
                    canvas.restoreToCount(inner);
                    halo.drawScanLine(canvas, phase, face);
                }
            } else {
                float size = island ? mix(.855f, .315f, collapse) : .875f;
                float visible = island ? ease(.30f,.96f,clamp(open + collapse)) : 1;
                if (island && retryFrom < 0) visible = Math.min(visible, ease(135, 350, age));
                int glyph = visible < 1 ? canvas.saveLayerAlpha(-44,-44,44,44,Math.round(255*visible)) : canvas.save();
                canvas.scale(size,size);
                if (island) islandGlyph.draw(canvas, phase, confirmed, elapsed, duration, collapse);
                else drawFace(canvas, confirmed ? UnlockFaceAnimationView.SUCCESS : SEARCHING,
                        phase, confirmed ? progress : 0);
                canvas.restoreToCount(glyph);
            }
            canvas.restoreToCount(saved);
        }
        if (bridge > 0 && age < 165) {
            float outgoing = (1 - ease(20,165,age)) * (state == FAILURE ? 1 - ease(0,90,elapsed) : 1);
            int saved = canvas.saveLayerAlpha(-88,-32,88,32,Math.round(255*outgoing));
            failure.draw(canvas,retryFrom,true,1-ease(0,160,age));
            canvas.restoreToCount(saved);
        }
        if (state == FAILURE) failure.draw(canvas,elapsed,retry,1);
    }

    private void drawFace(Canvas canvas, int state, float startPhase, float progress) {
        float turn = FocusMotion.scanTurn(startPhase);
        float elapsed = 0, landed = 0, retainedRim = 0;
        if (state != SEARCHING) {
            retainedRim = FocusRibbon.returningRim(turn);
            long remaining = FocusMotion.remainingTurnMs(startPhase);
            elapsed = progress * FocusMotion.resultDuration(startPhase, false);
            turn = FocusMotion.resultTurn(startPhase, remaining == 0 ? 1 : clamp(elapsed / remaining), remaining);
            landed = elapsed - remaining;
        }
        // The result may arrive while entirely edge-on or already back at rest.
        // Age gates the first frame, and the angle determines when the ring can close.
        float cue = ease(0, 126, elapsed);
        float seal = cue * ease(.80f, .932f, turn);
        float fold = FocusMotion.fold(turn);
        float response = state == SEARCHING ? 0 : ease(0, .80f, progress);
        float markTone = cue * ease(-36, 180, landed);
        float greenLight = ease(0, .045f, turn) * (1 - ease(.88f, 1, turn));
        int cornerColor = blend(WHITE, 0xff74dd89, greenLight);
        int faceColor = blend(WHITE, 0xff9cebaf, greenLight * .70f);
        cornerColor = blend(blend(cornerColor, GREEN, response), MARK_GREEN, markTone);
        faceColor = blend(blend(faceColor, FACE_GREEN, response), MARK_GREEN, markTone);

        if (fold == 0 && seal == 0) {
            drawRestingCorners(canvas, cornerColor, 1 - response);
        } else if (fold == 0 && seal == 1) {
            path.rewind();
            path.addCircle(0, 0, FocusRibbon.RIM_RADIUS, Path.Direction.CW);
            stroke(canvas, cornerColor, FocusRibbon.RIM_WIDTH, 1);
        } else {
            ribbon.draw(canvas, turn, fold, cornerColor, 1 - response, seal, retainedRim);
        }

        float opacity = 1 - ease(.11f, .80f, fold);
        if (opacity == 0) return;
        double angle = TAU * turn;
        float offset = (float) Math.sin(angle);
        int save = canvas.save();
        canvas.translate(13.5f * offset * (.32f + .68f * fold), -1.2f * fold);
        canvas.rotate(14 * offset);
        canvas.scale(Math.max(.18f, (float) Math.cos(angle)), 1);
        drawFeatures(canvas, faceColor, opacity, response, landed, cue);
        canvas.restoreToCount(save);
    }

    private void drawRestingCorners(Canvas canvas, int color, float reflection) {
        for (int q = 0; q < 4; q++) {
            float catchLight = FocusRibbon.cornerLight(q);
            float half = FocusRibbon.cornerHalf(q);
            float tangent = 10 - half;
            float gap = (half - 10) * (10f / 14f);
            path.rewind();
            path.moveTo(-gap, -half);
            path.lineTo(tangent, -half);
            path.arcTo(-half, -half, tangent + 10, tangent + 10, -90, -90, false);
            path.lineTo(-half, -gap);
            int saved = canvas.save();
            canvas.rotate(q * 90);
            stroke(canvas, blend(color, BLUE, .34f * reflection * catchLight),
                    2.15f, 1 - .10f * reflection);
            if (reflection > 0) {
                line.setShader(cornerLight);
                line.setAlpha(Math.round(255 * reflection * (.20f + .73f * catchLight)));
                line.setStrokeWidth(1);
                canvas.drawPath(path, line);
                line.setShader(null);
            }
            canvas.restoreToCount(saved);
        }
    }

    private void drawFeatures(Canvas canvas, int color, float opacity, float smile, float landed, float cue) {
        float eyeY = -6.05f - .16f * smile;
        float eyeHalf = 1.65f - .27f * smile;
        float release = cue * ease(-6, 118, landed);
        if (release < 1) {
            eyeHalf = mix(eyeHalf, .40f, release);
            path.rewind();
            path.moveTo(-7.2f, eyeY - eyeHalf);
            path.lineTo(-7.2f, eyeY + eyeHalf);
            path.moveTo(7.2f, eyeY - eyeHalf);
            path.lineTo(7.2f, eyeY + eyeHalf);
            stroke(canvas, color, 2.3f, opacity * (1 - release));
        }
        float nose = 1 - cue * ease(-22, 93, landed);
        if (nose > 0) {
            path.rewind();
            path.moveTo(.7f, -5.8f);
            path.lineTo(.7f, 1.5f);
            path.quadTo(.7f, 3.6f, -1.5f, 3.6f);
            path.lineTo(-2.7f, 3.6f);
            stroke(canvas, color, 2, opacity * nose);
        }
        drawMouth(canvas, color, opacity, smile, cue * ease(-18, 126, landed), landed);
    }

    private void drawMouth(Canvas canvas, int color, float opacity, float smile, float gather, float landed) {
        float cheekX = 8 + .55f * smile, cheekY = 8 - 1.10f * smile;
        float lowY = 11.5f + .18f * smile;
        if (gather == 0) {
            path.rewind();
            path.moveTo(-cheekX, cheekY);
            path.cubicTo(mix(-cheekX, 0, .225f), mix(cheekY, lowY, 5f / 7f),
                    mix(-cheekX, 0, .60f), lowY, 0, lowY);
            path.cubicTo(.40f * cheekX, lowY, .775f * cheekX, mix(lowY, cheekY, 2f / 7f), cheekX, cheekY);
            stroke(canvas, color, 2.1f, opacity);
            return;
        }
        checkmark.draw(canvas, blend(color, 0xffc4f3cb, .32f * gather), opacity,
                cheekX, cheekY, lowY, gather, landed);
    }

    private void stroke(Canvas canvas, int color, float width, float opacity) {
        setLine(INK, width + 1.35f, .65f * opacity);
        canvas.drawPath(path, line);
        setLine(color, width, opacity);
        canvas.drawPath(path, line);
    }

    private void setLine(int color, float width, float opacity) {
        line.setColor((color & 0x00ffffff) | (Math.round(255 * opacity) << 24));
        line.setStrokeWidth(width);
    }
}
