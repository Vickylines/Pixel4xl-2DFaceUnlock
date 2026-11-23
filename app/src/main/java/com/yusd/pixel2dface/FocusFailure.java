package com.yusd.pixel2dface;

import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;

/** The lock stays closed as it shakes, gets smaller and makes room for the swipe hint. */
final class FocusFailure {
    static final String SWIPE_HINT = "向上扫继续识别";
    static final String PASSCODE_HINT = "请使用密码";
    static final int HINT_HOLD_MS = 5200;
    private static final float HALF_WIDTH = 11.6f;
    private static final float GAP = 5.5f;
    private final Path lock = new Path();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final LinearGradient revealEdge = new LinearGradient(0, 0, 1, 0,
            Color.WHITE, 0x00ffffff, Shader.TileMode.CLAMP);
    private final Paint edgeMask = new Paint(Paint.DITHER_FLAG);
    private final Matrix revealMatrix = new Matrix();
    private final float retryWidth, passcodeWidth, baseline;

    FocusFailure(float fontScale) {
        // One silhouette with an open counter; overlapping a separate shackle and body
        // would leave a seam during the transition from the scanning symbol.
        lock.setFillType(Path.FillType.EVEN_ODD);
        lock.moveTo(-8.25f, -2.7f);
        lock.lineTo(-8.25f, -7.05f);
        lock.cubicTo(-8.25f, -12.45f, -4.95f, -15.65f, 0, -15.65f);
        lock.cubicTo(4.95f, -15.65f, 8.25f, -12.45f, 8.25f, -7.05f);
        lock.lineTo(8.25f, -2.7f);
        lock.lineTo(8.65f, -2.7f);
        lock.quadTo(HALF_WIDTH, -2.7f, HALF_WIDTH, .38f);
        lock.lineTo(HALF_WIDTH, 12.2f);
        lock.quadTo(HALF_WIDTH, 15.25f, 8.55f, 15.25f);
        lock.lineTo(-8.55f, 15.25f);
        lock.quadTo(-HALF_WIDTH, 15.25f, -HALF_WIDTH, 12.2f);
        lock.lineTo(-HALF_WIDTH, .38f);
        lock.quadTo(-HALF_WIDTH, -2.7f, -8.65f, -2.7f);
        lock.close();
        lock.moveTo(-5.1f, -2.7f);
        lock.lineTo(-5.1f, -7.1f);
        lock.cubicTo(-5.1f, -10.55f, -3.05f, -12.55f, 0, -12.55f);
        lock.cubicTo(3.05f, -12.55f, 5.1f, -10.55f, 5.1f, -7.1f);
        lock.lineTo(5.1f, -2.7f);
        lock.close();
        fill.setColor(Color.WHITE);
        edge.setColor(0xff10151c);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeJoin(Paint.Join.ROUND);
        edge.setStrokeWidth(.95f);

        text.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
        text.setTextSize(14.5f * fontScale);
        float desired = text.measureText(SWIPE_HINT);
        if (desired > 130) text.setTextSize(text.getTextSize() * 130 / desired);
        retryWidth = text.measureText(SWIPE_HINT);
        passcodeWidth = text.measureText(PASSCODE_HINT);
        Paint.FontMetrics metrics = text.getFontMetrics();
        baseline = -.5f * (metrics.ascent + metrics.descent);
        text.setColor(Color.WHITE);
        edgeMask.setShader(revealEdge);
        edgeMask.setBlendMode(BlendMode.DST_IN);
    }

    float pillWidth(boolean retry) {
        return 28f + 2 * HALF_WIDTH * .56f + GAP + (retry ? retryWidth : passcodeWidth);
    }

    void draw(Canvas canvas, float elapsed, boolean retry, float unfold) {
        String hint = retry ? SWIPE_HINT : PASSCODE_HINT;
        float labelWidth = retry ? retryWidth : passcodeWidth;
        float opacity = FocusMotion.ease(20, 125, elapsed);
        if (opacity == 0) return;
        float travel = FocusMotion.hintTravel(elapsed) * unfold;
        float scale = FocusMotion.lockScale(elapsed);
        float x = FocusMotion.lockOffset(elapsed) - .5f * (labelWidth + GAP) * travel;
        int saved = canvas.save();
        canvas.translate(x, 0);
        canvas.scale(scale, scale);
        edge.setAlpha(Math.round(99 * opacity));
        fill.setAlpha(Math.round(255 * opacity));
        canvas.drawPath(lock, edge);
        canvas.drawPath(lock, fill);
        canvas.restoreToCount(saved);

        float reveal = FocusMotion.ease(.17f, 1, travel);
        if (reveal == 0) return;
        float left = x + HALF_WIDTH * scale + GAP * travel;
        if (reveal >= 1) {
            canvas.drawText(hint, left, baseline, text);
            return;
        }
        // Mask the reveal edge after drawing. A shader on the text itself also
        // tints its shadow, making already-visible characters bloom as they move.
        float width = labelWidth * reveal, right = left + width;
        float feather = Math.min(7.5f, width) * (1 - FocusMotion.ease(.84f, 1, reveal));
        revealMatrix.setScale(Math.max(.01f, feather), 1);
        revealMatrix.postTranslate(right - feather, 0);
        revealEdge.setLocalMatrix(revealMatrix);
        int labelAlpha = Math.round(255 * FocusMotion.ease(.17f, .70f, travel));
        saved = canvas.save();
        canvas.clipRect(left - 2, -26, right, 26);
        canvas.saveLayerAlpha(left - 2, -26, right, 26, labelAlpha);
        canvas.drawText(hint, left, baseline, text);
        canvas.drawRect(right - feather, -26, right, 26, edgeMask);
        canvas.restoreToCount(saved);
    }
}
