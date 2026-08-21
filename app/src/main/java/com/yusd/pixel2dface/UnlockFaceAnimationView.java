package com.yusd.pixel2dface;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;

/** Ambient face-status surface inserted directly into SystemUI's lock-screen tree. */
public final class UnlockFaceAnimationView extends View {
    public static final int STYLE_FACE_ID = 0;
    public static final int STYLE_DYNAMIC_ISLAND = 1;

    private static final int SEARCHING = 0;
    private static final int SUCCESS = 1;
    private static final int FAILURE = 2;
    private static final long SUCCESS_MORPH_MS = 680L;
    private static final long FAILURE_MORPH_MS = 580L;

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ambientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint orbitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scanPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint islandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint islandBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint islandHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF arcBounds = new RectF();
    private final RectF eyeBounds = new RectF();
    private final RectF pillBounds = new RectF();
    private final float density;
    private final ValueAnimator ticker;

    private int state = SEARCHING;
    private int visualStyle;
    private float phase;
    private long stateStartedAt;
    private long attachedAt;

    public UnlockFaceAnimationView(Context context) {
        this(context, STYLE_FACE_ID);
    }

    public UnlockFaceAnimationView(Context context, int visualStyle) {
        super(context);
        this.visualStyle = normalizeStyle(visualStyle);
        density = getResources().getDisplayMetrics().density;
        setContentDescription("正在进行人脸识别");

        backgroundPaint.setColor(Color.argb(236, 4, 10, 18));
        tintPaint.setStyle(Paint.Style.FILL);

        haloPaint.setStyle(Paint.Style.STROKE);
        haloPaint.setStrokeCap(Paint.Cap.ROUND);
        orbitPaint.setStyle(Paint.Style.STROKE);
        orbitPaint.setStrokeCap(Paint.Cap.ROUND);
        glyphPaint.setStrokeCap(Paint.Cap.ROUND);
        glyphPaint.setStrokeJoin(Paint.Join.ROUND);
        scanPaint.setStrokeCap(Paint.Cap.ROUND);
        islandPaint.setStyle(Paint.Style.FILL);
        islandBorderPaint.setStyle(Paint.Style.STROKE);
        islandBorderPaint.setStrokeWidth(dp(0.9f));
        islandHighlightPaint.setStyle(Paint.Style.STROKE);
        islandHighlightPaint.setStrokeWidth(dp(0.55f));
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textPaint.setTextSize(sp(11.5f));

        ticker = ValueAnimator.ofFloat(0f, 1f);
        ticker.setDuration(2800L);
        ticker.setRepeatCount(ValueAnimator.INFINITE);
        ticker.setInterpolator(new LinearInterpolator());
        ticker.addUpdateListener(value -> {
            phase = (float) value.getAnimatedValue();
            invalidate();
        });
    }

    public void setVisualStyle(int style) {
        int safeStyle = normalizeStyle(style);
        if (visualStyle == safeStyle) {
            return;
        }
        visualStyle = safeStyle;
        attachedAt = SystemClock.elapsedRealtime();
        invalidate();
    }

    public void showSuccess() {
        if (state != SEARCHING) {
            return;
        }
        state = SUCCESS;
        stateStartedAt = SystemClock.elapsedRealtime();
        setContentDescription("人脸识别成功，请向上滑动打开");
        invalidate();
    }

    public void showFailure(String ignored) {
        if (state != SEARCHING) {
            return;
        }
        state = FAILURE;
        stateStartedAt = SystemClock.elapsedRealtime();
        setContentDescription("人脸识别失败，请使用密码");
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attachedAt = SystemClock.elapsedRealtime();
        if (!ticker.isStarted()) {
            ticker.start();
        }
        setAlpha(0f);
        float initialScale = visualStyle == STYLE_DYNAMIC_ISLAND ? 0.86f : 0.72f;
        setScaleX(initialScale);
        setScaleY(initialScale);
        animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(460L)
                .setInterpolator(new OvershootInterpolator(0.52f))
                .start();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        ambientPaint.setShader(new RadialGradient(
                width / 2f - dp(8f),
                height / 2f - dp(9f),
                dp(42f),
                new int[]{
                        Color.argb(76, 101, 225, 255),
                        Color.argb(22, 44, 132, 181),
                        Color.TRANSPARENT
                },
                new float[]{0f, 0.50f, 1f},
                Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float breath = 0.5f + 0.5f * (float) Math.sin(phase * Math.PI * 2.0);
        float progress = state == SEARCHING ? 0f : transitionProgress();

        int save = canvas.save();
        if (state == FAILURE) {
            float shake = (float) Math.sin(progress * Math.PI * 7.0)
                    * (1f - progress) * dp(1.55f);
            canvas.translate(shake, 0f);
        }

        if (visualStyle == STYLE_DYNAMIC_ISLAND) {
            float islandY = cy + dp(0.32f)
                    * (float) Math.sin(phase * Math.PI * 2.0);
            drawDynamicIsland(canvas, cx, islandY, progress);
        } else {
            drawBackground(canvas, cx, cy, breath, progress);
            if (state == SUCCESS) {
                drawSuccess(canvas, cx, cy, progress);
            } else if (state == FAILURE) {
                drawFailure(canvas, cx, cy, progress);
            } else {
                drawSearching(canvas, cx, cy, breath);
            }
        }
        canvas.restoreToCount(save);
    }

    private void drawDynamicIsland(Canvas canvas, float cx, float cy, float progress) {
        float intro = easeOut(clamp((SystemClock.elapsedRealtime() - attachedAt) / 520f));
        float targetWidth = state == FAILURE ? 146f : 150f;
        float width = lerp(52f, targetWidth, intro);
        if (state == SUCCESS) {
            width = lerp(width, 130f, smoothStep(0.58f, 1f, progress));
        }
        float height = lerp(31f, 48f, intro);
        float left = cx - dp(width / 2f);
        float right = cx + dp(width / 2f);
        float top = cy - dp(height / 2f);
        float bottom = cy + dp(height / 2f);
        pillBounds.set(left, top, right, bottom);

        int accent;
        if (state == SUCCESS) {
            accent = blend(Color.rgb(93, 210, 255), Color.rgb(65, 226, 126), progress);
        } else if (state == FAILURE) {
            accent = blend(Color.rgb(93, 210, 255), Color.rgb(255, 104, 93), progress);
        } else {
            accent = Color.rgb(93, 210, 255);
        }

        // A restrained outer edge gives the pill presence without using a costly blur layer.
        arcBounds.set(left - dp(1.5f), top - dp(1.5f),
                right + dp(1.5f), bottom + dp(1.5f));
        islandBorderPaint.setStrokeWidth(dp(3f));
        islandBorderPaint.setColor(withAlpha(accent,
                state == SEARCHING ? 12 : Math.round(12f + 13f * progress)));
        canvas.drawRoundRect(arcBounds, dp(height / 2f + 1.5f),
                dp(height / 2f + 1.5f), islandBorderPaint);

        islandPaint.setColor(Color.argb(249, 1, 4, 9));
        canvas.drawRoundRect(pillBounds, dp(height / 2f), dp(height / 2f), islandPaint);

        // State tint, accent rim and a very fine glass highlight form one shared visual system
        // with the circular face style below.
        if (state == SUCCESS) {
            islandPaint.setColor(Color.argb(Math.round(36f * progress), 53, 218, 118));
        } else if (state == FAILURE) {
            islandPaint.setColor(Color.argb(Math.round(33f * progress), 255, 87, 76));
        } else {
            islandPaint.setColor(Color.argb(15, 68, 185, 238));
        }
        canvas.drawRoundRect(pillBounds, dp(height / 2f), dp(height / 2f), islandPaint);

        islandBorderPaint.setStrokeWidth(dp(0.9f));
        islandBorderPaint.setColor(withAlpha(accent,
                state == SEARCHING ? 62 : Math.round(62f + 38f * progress)));
        canvas.drawRoundRect(pillBounds, dp(height / 2f), dp(height / 2f),
                islandBorderPaint);

        arcBounds.set(left + dp(1.25f), top + dp(1.25f),
                right - dp(1.25f), bottom - dp(1.25f));
        islandHighlightPaint.setColor(Color.argb(
                state == SEARCHING ? 22 : Math.round(22f + 8f * progress),
                238, 249, 255));
        canvas.drawRoundRect(arcBounds, dp(height / 2f - 1.25f),
                dp(height / 2f - 1.25f), islandHighlightPaint);
        scanPaint.setStrokeWidth(dp(0.65f));
        scanPaint.setColor(Color.argb(Math.round(34f * intro), 226, 247, 255));
        canvas.drawLine(left + dp(20f), top + dp(1.65f),
                right - dp(20f), top + dp(1.65f), scanPaint);

        float contentAlpha = smoothStep(0.26f, 0.86f, intro);
        float iconCx = left + dp(24.5f);
        if (state == SEARCHING) {
            drawIslandFace(canvas, iconCx, cy, Math.round(255f * contentAlpha));
            drawIslandText(canvas, "正在识别", left + dp(46f), cy,
                    Math.round(235f * contentAlpha));
            drawIslandDots(canvas, left + dp(120f), cy, contentAlpha);
            drawIslandShimmer(canvas, left, right, bottom, contentAlpha);
        } else if (state == SUCCESS) {
            drawIslandSuccess(canvas, iconCx, cy, progress, contentAlpha);
            drawIslandText(canvas, "已识别", left + dp(48f), cy,
                    Math.round(245f * contentAlpha));
        } else {
            drawIslandFailure(canvas, iconCx, cy, progress, contentAlpha);
            drawIslandText(canvas, "请用密码", left + dp(46f), cy,
                    Math.round(240f * contentAlpha));
        }
    }

    private void drawIslandFace(Canvas canvas, float cx, float cy, int alpha) {
        int orbitSave = canvas.save();
        canvas.rotate(phase * 360f, cx, cy);
        orbitPaint.setStyle(Paint.Style.STROKE);
        orbitPaint.setStrokeWidth(dp(1.35f));
        orbitPaint.setColor(Color.argb(Math.round(alpha * 0.88f), 105, 224, 255));
        arcBounds.set(cx - dp(11.5f), cy - dp(11.5f),
                cx + dp(11.5f), cy + dp(11.5f));
        canvas.drawArc(arcBounds, -78f, 96f, false, orbitPaint);
        orbitPaint.setStyle(Paint.Style.FILL);
        orbitPaint.setColor(Color.argb(alpha, 205, 248, 255));
        canvas.drawCircle(cx + dp(11.5f), cy, dp(1.1f), orbitPaint);
        canvas.restoreToCount(orbitSave);

        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeWidth(dp(1.35f));
        glyphPaint.setColor(Color.argb(alpha, 246, 251, 255));
        float half = dp(7.1f);
        float segment = dp(2.85f);
        path.reset();
        path.moveTo(cx - half + segment, cy - half);
        path.lineTo(cx - half, cy - half);
        path.lineTo(cx - half, cy - half + segment);
        path.moveTo(cx + half - segment, cy - half);
        path.lineTo(cx + half, cy - half);
        path.lineTo(cx + half, cy - half + segment);
        path.moveTo(cx - half, cy + half - segment);
        path.lineTo(cx - half, cy + half);
        path.lineTo(cx - half + segment, cy + half);
        path.moveTo(cx + half, cy + half - segment);
        path.lineTo(cx + half, cy + half);
        path.lineTo(cx + half - segment, cy + half);
        canvas.drawPath(path, glyphPaint);

        float blink = blinkAmount();
        glyphPaint.setStyle(Paint.Style.FILL);
        float eyeHeight = dp(1.2f - 0.78f * blink);
        eyeBounds.set(cx - dp(4.3f), cy - dp(2.1f) - eyeHeight / 2f,
                cx - dp(2.1f), cy - dp(2.1f) + eyeHeight / 2f);
        canvas.drawRoundRect(eyeBounds, eyeHeight / 2f, eyeHeight / 2f, glyphPaint);
        eyeBounds.set(cx + dp(2.1f), cy - dp(2.1f) - eyeHeight / 2f,
                cx + dp(4.3f), cy - dp(2.1f) + eyeHeight / 2f);
        canvas.drawRoundRect(eyeBounds, eyeHeight / 2f, eyeHeight / 2f, glyphPaint);
        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeWidth(dp(1.1f));
        arcBounds.set(cx - dp(4.1f), cy - dp(1.3f),
                cx + dp(4.1f), cy + dp(5.2f));
        canvas.drawArc(arcBounds, 22f, 136f, false, glyphPaint);
    }

    private void drawIslandDots(Canvas canvas, float startX, float cy, float contentAlpha) {
        glyphPaint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 3; i++) {
            float wave = 0.5f + 0.5f * (float) Math.sin(
                    phase * Math.PI * 2.0 - i * 0.85f);
            int alpha = Math.round(contentAlpha * (70f + 170f * wave));
            glyphPaint.setColor(Color.argb(alpha, 128, 226, 255));
            canvas.drawCircle(startX + dp(i * 4.8f), cy, dp(1.05f + 0.24f * wave),
                    glyphPaint);
        }
    }

    private void drawIslandShimmer(Canvas canvas, float left, float right, float bottom,
            float contentAlpha) {
        float travel = 0.5f - 0.5f * (float) Math.cos(phase * Math.PI * 2.0);
        float center = lerp(left + dp(15f), right - dp(15f), travel);
        float half = dp(11f);
        scanPaint.setStrokeWidth(dp(3.2f));
        scanPaint.setColor(Color.argb(Math.round(14f * contentAlpha), 92, 218, 255));
        canvas.drawLine(Math.max(left + dp(9f), center - half), bottom - dp(3.8f),
                Math.min(right - dp(9f), center + half), bottom - dp(3.8f), scanPaint);
        scanPaint.setStrokeWidth(dp(0.9f));
        scanPaint.setColor(Color.argb(Math.round(132f * contentAlpha), 112, 229, 255));
        canvas.drawLine(Math.max(left + dp(11f), center - dp(6.5f)), bottom - dp(3.8f),
                Math.min(right - dp(11f), center + dp(6.5f)), bottom - dp(3.8f), scanPaint);
    }

    private void drawIslandSuccess(Canvas canvas, float cx, float cy, float progress,
            float contentAlpha) {
        float ring = easeOut(clamp(progress / 0.65f));
        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeWidth(dp(1.6f));
        glyphPaint.setColor(Color.argb(Math.round(255f * contentAlpha), 73, 230, 132));
        arcBounds.set(cx - dp(10.2f), cy - dp(10.2f),
                cx + dp(10.2f), cy + dp(10.2f));
        canvas.drawArc(arcBounds, -90f, 360f * ring, false, glyphPaint);

        float check = easeOut(clamp((progress - 0.12f) / 0.62f));
        path.reset();
        path.moveTo(cx - dp(5.1f), cy);
        if (check < 0.36f) {
            float local = check / 0.36f;
            path.lineTo(lerp(cx - dp(5.1f), cx - dp(1.35f), local),
                    lerp(cy, cy + dp(3.8f), local));
        } else {
            path.lineTo(cx - dp(1.35f), cy + dp(3.8f));
            float local = (check - 0.36f) / 0.64f;
            path.lineTo(lerp(cx - dp(1.35f), cx + dp(6.1f), local),
                    lerp(cy + dp(3.8f), cy - dp(4.9f), local));
        }
        glyphPaint.setStrokeWidth(dp(2.05f));
        canvas.drawPath(path, glyphPaint);
    }

    private void drawIslandFailure(Canvas canvas, float cx, float cy, float progress,
            float contentAlpha) {
        float ring = easeOut(clamp(progress / 0.64f));
        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeWidth(dp(1.6f));
        glyphPaint.setColor(Color.argb(Math.round(255f * contentAlpha), 255, 108, 97));
        arcBounds.set(cx - dp(10.1f), cy - dp(10.1f),
                cx + dp(10.1f), cy + dp(10.1f));
        canvas.drawArc(arcBounds, -90f, 360f * ring, false, glyphPaint);
        float extent = dp(4.45f) * easeOut(clamp((progress - 0.10f) / 0.58f));
        glyphPaint.setStrokeWidth(dp(1.95f));
        canvas.drawLine(cx - extent, cy - extent, cx + extent, cy + extent, glyphPaint);
        canvas.drawLine(cx + extent, cy - extent, cx - extent, cy + extent, glyphPaint);
    }

    private void drawIslandText(Canvas canvas, String text, float x, float cy, int alpha) {
        textPaint.setColor(Color.argb(Math.max(0, Math.min(255, alpha)), 245, 248, 252));
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float baseline = cy - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(text, x, baseline, textPaint);
    }

    private void drawBackground(Canvas canvas, float cx, float cy, float breath,
            float progress) {
        int accent;
        if (state == SUCCESS) {
            accent = blend(Color.rgb(99, 216, 255), Color.rgb(55, 224, 123), progress);
            tintPaint.setColor(Color.argb(Math.round(39f * progress), 34, 185, 96));
        } else if (state == FAILURE) {
            accent = blend(Color.rgb(99, 216, 255), Color.rgb(255, 101, 91), progress);
            tintPaint.setColor(Color.argb(Math.round(39f * progress), 214, 55, 48));
        } else {
            accent = Color.rgb(99, 216, 255);
            tintPaint.setColor(Color.argb(Math.round(9f + 8f * breath), 55, 187, 234));
        }

        // Soft ambient disk, solid instrument face, then two precise rims. The layering makes
        // the circle read as a deliberate lock-screen surface rather than a small status icon.
        canvas.drawCircle(cx, cy, dp(37f), ambientPaint);
        haloPaint.setStrokeWidth(dp(2.8f));
        haloPaint.setColor(withAlpha(accent, Math.round(10f + 9f * breath)));
        canvas.drawCircle(cx, cy, dp(34.2f), haloPaint);
        canvas.drawCircle(cx, cy, dp(31.8f), backgroundPaint);
        canvas.drawCircle(cx, cy, dp(31.8f), ambientPaint);
        canvas.drawCircle(cx, cy, dp(31.0f), tintPaint);

        haloPaint.setColor(withAlpha(accent,
                state == SEARCHING ? Math.round(58f + 45f * breath) : 132));
        haloPaint.setStrokeWidth(dp(state == SEARCHING ? 1.25f : 1.5f));
        canvas.drawCircle(cx, cy, dp(30.2f + (state == SEARCHING ? 0.62f * breath : 0f)),
                haloPaint);
        haloPaint.setStrokeWidth(dp(0.65f));
        haloPaint.setColor(withAlpha(accent, state == SEARCHING ? 30 : 46));
        canvas.drawCircle(cx, cy, dp(27.8f), haloPaint);

        // A short upper highlight gives both light and dark wallpapers a clean glass edge.
        haloPaint.setStrokeWidth(dp(0.8f));
        haloPaint.setColor(Color.argb(42, 229, 249, 255));
        arcBounds.set(cx - dp(28.7f), cy - dp(28.7f),
                cx + dp(28.7f), cy + dp(28.7f));
        canvas.drawArc(arcBounds, 210f, 120f, false, haloPaint);

        if (state == SEARCHING) {
            arcBounds.set(cx - dp(26.8f), cy - dp(26.8f),
                    cx + dp(26.8f), cy + dp(26.8f));
            int orbitSave = canvas.save();
            canvas.rotate(phase * 360f, cx, cy);
            orbitPaint.setStyle(Paint.Style.STROKE);
            orbitPaint.setStrokeWidth(dp(1.75f));
            orbitPaint.setColor(Color.argb(214, 112, 226, 255));
            canvas.drawArc(arcBounds, -84f, 78f, false, orbitPaint);
            orbitPaint.setStrokeWidth(dp(1.15f));
            orbitPaint.setColor(Color.argb(86, 112, 226, 255));
            canvas.drawArc(arcBounds, 102f, 46f, false, orbitPaint);
            orbitPaint.setStyle(Paint.Style.FILL);
            orbitPaint.setColor(Color.argb(235, 203, 247, 255));
            canvas.drawCircle(cx + dp(26.8f), cy, dp(1.35f), orbitPaint);
            orbitPaint.setColor(Color.argb(138, 156, 236, 255));
            canvas.drawCircle(cx - dp(26.8f), cy, dp(0.85f), orbitPaint);
            orbitPaint.setStyle(Paint.Style.STROKE);
            canvas.restoreToCount(orbitSave);
        } else {
            float pulse = easeOut(progress);
            haloPaint.setStrokeWidth(dp(1.45f));
            haloPaint.setColor(withAlpha(accent, Math.round(118f * (1f - progress))));
            canvas.drawCircle(cx, cy, dp(18f + 12f * pulse), haloPaint);
        }
    }

    private void drawSearching(Canvas canvas, float cx, float cy, float breath) {
        float faceY = cy + dp(0.55f)
                * (float) Math.sin((phase * Math.PI * 2.0) + Math.PI / 2.0);
        drawFace(canvas, cx, faceY, breath, 255);

        float travel = 0.5f - 0.5f * (float) Math.cos(phase * Math.PI * 2.0);
        float scanY = cy - dp(11.5f) + dp(23f) * travel;
        float edgeFade = 0.55f + 0.45f * (float) Math.sin(travel * Math.PI);
        scanPaint.setStrokeWidth(dp(4.8f));
        scanPaint.setColor(Color.argb(Math.round(24f * edgeFade), 87, 216, 255));
        canvas.drawLine(cx - dp(12.8f), scanY, cx + dp(12.8f), scanY, scanPaint);
        scanPaint.setStrokeWidth(dp(1.25f));
        scanPaint.setColor(Color.argb(Math.round(230f * edgeFade), 111, 225, 255));
        canvas.drawLine(cx - dp(11.5f), scanY, cx + dp(11.5f), scanY, scanPaint);
    }

    private void drawFace(Canvas canvas, float cx, float cy, float breath, int alpha) {
        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setColor(Color.argb(alpha, 245, 251, 255));
        glyphPaint.setStrokeWidth(dp(2.0f));

        float half = dp(15.7f + 0.42f * breath);
        float segment = dp(5.9f);
        float radius = dp(2.15f);
        path.reset();
        path.moveTo(cx - half + segment, cy - half);
        path.lineTo(cx - half + radius, cy - half);
        path.quadTo(cx - half, cy - half, cx - half, cy - half + radius);
        path.lineTo(cx - half, cy - half + segment);

        path.moveTo(cx + half - segment, cy - half);
        path.lineTo(cx + half - radius, cy - half);
        path.quadTo(cx + half, cy - half, cx + half, cy - half + radius);
        path.lineTo(cx + half, cy - half + segment);

        path.moveTo(cx - half, cy + half - segment);
        path.lineTo(cx - half, cy + half - radius);
        path.quadTo(cx - half, cy + half, cx - half + radius, cy + half);
        path.lineTo(cx - half + segment, cy + half);

        path.moveTo(cx + half, cy + half - segment);
        path.lineTo(cx + half, cy + half - radius);
        path.quadTo(cx + half, cy + half, cx + half - radius, cy + half);
        path.lineTo(cx + half - segment, cy + half);
        canvas.drawPath(path, glyphPaint);

        float blink = blinkAmount();
        float eyeHeight = dp(1.8f - 1.27f * blink);
        glyphPaint.setStyle(Paint.Style.FILL);
        glyphPaint.setColor(Color.argb(alpha, 245, 251, 255));
        eyeBounds.set(cx - dp(7.1f), cy - dp(4.1f) - eyeHeight / 2f,
                cx - dp(3.5f), cy - dp(4.1f) + eyeHeight / 2f);
        canvas.drawRoundRect(eyeBounds, eyeHeight / 2f, eyeHeight / 2f, glyphPaint);
        eyeBounds.set(cx + dp(3.5f), cy - dp(4.1f) - eyeHeight / 2f,
                cx + dp(7.1f), cy - dp(4.1f) + eyeHeight / 2f);
        canvas.drawRoundRect(eyeBounds, eyeHeight / 2f, eyeHeight / 2f, glyphPaint);

        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeWidth(dp(1.75f));
        arcBounds.set(cx - dp(7.1f), cy - dp(2.6f),
                cx + dp(7.1f), cy + dp(8.1f));
        canvas.drawArc(arcBounds, 20f, 140f, false, glyphPaint);
    }

    private void drawSuccess(Canvas canvas, float cx, float cy, float progress) {
        float oldFaceAlpha = 1f - smoothStep(0f, 0.28f, progress);
        if (oldFaceAlpha > 0f) {
            drawFace(canvas, cx, cy, 0.5f, Math.round(220f * oldFaceAlpha));
        }

        float ringProgress = easeOut(clamp((progress - 0.02f) / 0.68f));
        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeWidth(dp(2.2f));
        glyphPaint.setColor(Color.rgb(72, 228, 130));
        arcBounds.set(cx - dp(17.1f), cy - dp(17.1f),
                cx + dp(17.1f), cy + dp(17.1f));
        canvas.drawArc(arcBounds, -90f, 360f * ringProgress, false, glyphPaint);

        float checkProgress = easeOut(clamp((progress - 0.16f) / 0.62f));
        drawCheck(canvas, cx, cy, checkProgress);

        if (progress > 0.72f) {
            float settle = smoothStep(0.72f, 1f, progress);
            glyphPaint.setColor(Color.argb(Math.round(70f * (1f - settle)), 100, 255, 159));
            glyphPaint.setStrokeWidth(dp(1.45f));
            canvas.drawCircle(cx, cy, dp(18f + 5f * settle), glyphPaint);
        }
    }

    private void drawCheck(Canvas canvas, float cx, float cy, float progress) {
        float ax = cx - dp(8.1f);
        float ay = cy;
        float bx = cx - dp(1.9f);
        float by = cy + dp(6f);
        float cx2 = cx + dp(9.8f);
        float cy2 = cy - dp(7.7f);

        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeWidth(dp(2.8f));
        glyphPaint.setColor(Color.rgb(72, 228, 130));
        path.reset();
        path.moveTo(ax, ay);
        if (progress < 0.34f) {
            float local = progress / 0.34f;
            path.lineTo(lerp(ax, bx, local), lerp(ay, by, local));
        } else {
            path.lineTo(bx, by);
            float local = (progress - 0.34f) / 0.66f;
            path.lineTo(lerp(bx, cx2, local), lerp(by, cy2, local));
        }
        canvas.drawPath(path, glyphPaint);
    }

    private void drawFailure(Canvas canvas, float cx, float cy, float progress) {
        float oldFaceAlpha = 1f - smoothStep(0f, 0.25f, progress);
        if (oldFaceAlpha > 0f) {
            drawFace(canvas, cx, cy, 0.5f, Math.round(210f * oldFaceAlpha));
        }

        float ringProgress = easeOut(clamp(progress / 0.62f));
        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeWidth(dp(2.2f));
        glyphPaint.setColor(Color.rgb(255, 105, 94));
        arcBounds.set(cx - dp(17f), cy - dp(17f),
                cx + dp(17f), cy + dp(17f));
        canvas.drawArc(arcBounds, -90f, 360f * ringProgress, false, glyphPaint);

        float cross = easeOut(clamp((progress - 0.12f) / 0.58f));
        float extent = dp(7.2f) * cross;
        glyphPaint.setStrokeWidth(dp(2.7f));
        canvas.drawLine(cx - extent, cy - extent, cx + extent, cy + extent, glyphPaint);
        canvas.drawLine(cx + extent, cy - extent, cx - extent, cy + extent, glyphPaint);
    }

    private float blinkAmount() {
        if (phase >= 0.57f && phase <= 0.64f) {
            return (float) Math.sin(((phase - 0.57f) / 0.07f) * Math.PI);
        }
        if (phase >= 0.68f && phase <= 0.72f) {
            return 0.62f * (float) Math.sin(((phase - 0.68f) / 0.04f) * Math.PI);
        }
        return 0f;
    }

    private float transitionProgress() {
        long duration = state == SUCCESS ? SUCCESS_MORPH_MS : FAILURE_MORPH_MS;
        return clamp((SystemClock.elapsedRealtime() - stateStartedAt) / (float) duration);
    }

    private static float smoothStep(float edge0, float edge1, float value) {
        float x = clamp((value - edge0) / (edge1 - edge0));
        return x * x * (3f - 2f * x);
    }

    private static float easeOut(float value) {
        float x = 1f - clamp(value);
        return 1f - x * x * x;
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static float lerp(float start, float end, float amount) {
        return start + (end - start) * clamp(amount);
    }

    private static int blend(int from, int to, float amount) {
        float t = clamp(amount);
        return Color.rgb(
                Math.round(lerp(Color.red(from), Color.red(to), t)),
                Math.round(lerp(Color.green(from), Color.green(to), t)),
                Math.round(lerp(Color.blue(from), Color.blue(to), t)));
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int normalizeStyle(int style) {
        return style == STYLE_DYNAMIC_ISLAND ? STYLE_DYNAMIC_ISLAND : STYLE_FACE_ID;
    }

    private float dp(float value) {
        return value * density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    @Override
    protected void onDetachedFromWindow() {
        animate().cancel();
        ticker.cancel();
        super.onDetachedFromWindow();
    }
}
