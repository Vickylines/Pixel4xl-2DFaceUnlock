package com.yusd.pixel2dface;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.camera.view.PreviewView;
import androidx.activity.ComponentActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;

/** Visible capture UI only. The keyguard host never constructs this view or a preview. */
@SuppressLint("ViewConstructor") // Constructed with the owning Activity, never inflated from XML.
final class CaptureContentView extends FrameLayout {
    final PreviewView preview;
    private final TextView instruction;
    private final FaceGuideView guide;
    private final LinearLayout capture;

    CaptureContentView(ComponentActivity activity, boolean enrollment, Runnable cancel) {
        super(activity);
        Context context = activity;
        setBackgroundColor(context.getColor(R.color.capture_background));
        ViewCompat.setOnApplyWindowInsetsListener(this, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });
        capture = Ui.column(context);
        capture.setGravity(Gravity.CENTER_HORIZONTAL);
        capture.setPadding(dp(24), dp(20), dp(24), dp(16));
        addView(capture, new LayoutParams(-1, -1));
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout controls = landscape ? Ui.column(context) : capture;
        if (landscape) {
            capture.setOrientation(LinearLayout.HORIZONTAL);
            controls.setGravity(Gravity.CENTER);
            controls.setPadding(0, 0, dp(24), 0);
            ScrollView panel = new ScrollView(context);
            panel.setFillViewport(true);
            panel.addView(controls);
            capture.addView(panel, new LinearLayout.LayoutParams(0, -1, 1));
        }
        TextView title = label(enrollment ? "录入面容" : "测试识别", 28);
        title.setAccessibilityHeading(true);
        controls.addView(title, Ui.space(context, 0, 8));
        instruction = label("看向屏幕", 18);
        instruction.setMinHeight(dp(56));
        instruction.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        controls.addView(instruction, Ui.space(context, 0, 8));
        FrameLayout viewport = new FrameLayout(context);
        preview = new PreviewView(context);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        viewport.addView(preview, new LayoutParams(-1, -1));
        guide = new FaceGuideView(context);
        guide.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        viewport.addView(guide, new LayoutParams(-1, -1));
        CircularProgressIndicator loading = new CircularProgressIndicator(context);
        loading.setIndeterminate(true);
        loading.setContentDescription("正在打开相机");
        viewport.addView(loading, new LayoutParams(dp(48), dp(48), Gravity.CENTER));
        preview.getPreviewStreamState().observe(activity, state ->
                loading.setVisibility(state == PreviewView.StreamState.STREAMING ? GONE : VISIBLE));
        capture.addView(viewport, landscape ? new LinearLayout.LayoutParams(0, -1, 1)
                : new LinearLayout.LayoutParams(-1, 0, 1));
        MaterialButton cancelButton = Ui.button(context, "取消", false);
        cancelButton.setOnClickListener(v -> cancel.run());
        controls.addView(cancelButton, Ui.space(context, 20, 0));
    }

    void showDetail(String value) {
        if (!instruction.getText().toString().equals(value)) instruction.setText(value);
    }

    void showProgress(float progress) { guide.setProgress(progress); }

    void showResult(boolean success, boolean enrollment, String message, String retryLabel,
            Runnable retry, Runnable done) {
        removeView(capture);
        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);
        ViewCompat.setAccessibilityPaneTitle(scroll, "识别结果");
        LinearLayout result = Ui.column(getContext());
        result.setGravity(Gravity.CENTER);
        result.setPadding(dp(32), dp(32), dp(32), dp(24));
        scroll.addView(result);
        ResultGlyph glyph = new ResultGlyph(getContext(), success);
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        result.addView(glyph, new LinearLayout.LayoutParams(dp(104), dp(104)));
        String outcome = success ? (enrollment ? "面容已录入" : "识别成功") : message;
        TextView title = label(outcome, 28);
        title.setAccessibilityHeading(true);
        result.addView(title, Ui.space(getContext(), 28, 16));
        MaterialButton primary = Ui.button(getContext(), success && enrollment ? "完成" : retryLabel, true);
        primary.setOnClickListener(v -> { if (success && enrollment) done.run(); else retry.run(); });
        result.addView(primary, Ui.space(getContext(), 12, 12));
        if (!success || !enrollment) {
            MaterialButton back = Ui.button(getContext(), "返回", false);
            back.setOnClickListener(v -> done.run());
            result.addView(back);
        }
        addView(scroll, new LayoutParams(-1, -1));
        if (ValueAnimator.areAnimatorsEnabled()) {
            result.setAlpha(0);
            result.setTranslationY(dp(12));
            result.animate().alpha(1).translationY(0).setDuration(220).start();
        }
    }

    private TextView label(String value, int size) {
        TextView text = new TextView(getContext());
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(Color.rgb(231, 236, 250));
        text.setGravity(Gravity.CENTER);
        text.setLineSpacing(0, 1.15f);
        return text;
    }
    private int dp(int value) { return Ui.dp(getContext(), value); }

    private static final class ResultGlyph extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final boolean success;
        ResultGlyph(Context context, boolean success) { super(context); this.success = success; }
        @Override protected void onDraw(Canvas canvas) {
            float size = Math.min(getWidth(), getHeight());
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(success ? Color.rgb(40, 64, 61) : Color.rgb(53, 62, 87));
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, size / 2, paint);
            paint.setColor(success ? Color.rgb(164, 226, 197) : Color.rgb(182, 196, 255));
            paint.setStrokeWidth(Ui.dp(getContext(), 4));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStyle(Paint.Style.STROKE);
            path.reset();
            if (success) {
                path.moveTo(size * .29f, size * .52f);
                path.lineTo(size * .44f, size * .66f);
                path.lineTo(size * .72f, size * .36f);
                canvas.drawPath(path, paint);
            } else {
                canvas.drawLine(size * .5f, size * .28f, size * .5f, size * .54f, paint);
                canvas.drawPoint(size * .5f, size * .7f, paint);
            }
        }
    }

    private static final class FaceGuideView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mask = new Path();
        private final RectF oval = new RectF();
        private final ValueAnimator animator = new ValueAnimator();
        private float progress;
        private float targetProgress;
        FaceGuideView(Context context) {
            super(context);
            animator.setDuration(180);
            animator.addUpdateListener(animation -> {
                progress = (float) animation.getAnimatedValue();
                invalidate();
            });
        }
        void setProgress(float next) {
            if (next == targetProgress) return;
            targetProgress = next;
            animator.cancel();
            if (ValueAnimator.areAnimatorsEnabled()) {
                animator.setFloatValues(progress, next);
                animator.start();
            } else { progress = next; invalidate(); }
        }
        @Override protected void onDetachedFromWindow() {
            animator.cancel();
            super.onDetachedFromWindow();
        }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            float width = Math.min(w - Ui.dp(getContext(), 24), (h - Ui.dp(getContext(), 24)) / 1.28f);
            float height = width * 1.28f;
            oval.set((w - width) / 2, (h - height) / 2, (w + width) / 2, (h + height) / 2);
            mask.reset();
            mask.setFillType(Path.FillType.EVEN_ODD);
            mask.addRect(0, 0, w, h, Path.Direction.CW);
            mask.addOval(oval, Path.Direction.CW);
        }
        @Override protected void onDraw(Canvas canvas) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(getContext().getColor(R.color.capture_background));
            canvas.drawPath(mask, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(getContext(), 3));
            paint.setColor(Color.rgb(97, 109, 133));
            canvas.drawOval(oval, paint);
            paint.setColor(Color.rgb(182, 196, 255));
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawArc(oval, -90, progress * 360f, false, paint);
        }
    }
}
