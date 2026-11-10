package com.yusd.pixel2dface;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Handler;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;

/** Temporary, untouchable light on the real keyguard; it owns no authentication state. */
final class SystemUiScreenLight {
    private final Handler main;
    private ViewGroup container;
    private View windowRoot;
    private EdgeLight light;
    private float previousBrightness, appliedBrightness;
    private boolean brightnessChanged;
    private final Runnable expiry = this::remove;

    SystemUiScreenLight(Handler main) { this.main = main; }

    void show(ViewGroup target, long deadline) {
        if (container == target) {
            light.layout(0, 0, target.getWidth(), target.getHeight());
            return;
        }
        remove();
        container = target;
        light = new EdgeLight(target.getContext());
        light.setClickable(false);
        light.setFocusable(false);
        light.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        light.setAlpha(ValueAnimator.areAnimatorsEnabled() ? 0f : 1f);
        target.getOverlay().add(light);
        light.layout(0, 0, target.getWidth(), target.getHeight());
        // Also expires if a capture process dies without delivering its result.
        main.postDelayed(expiry, Math.max(0, deadline - android.os.SystemClock.elapsedRealtime()));
        windowRoot = target.getRootView();
        if (windowRoot.getLayoutParams() instanceof WindowManager.LayoutParams attributes) {
            previousBrightness = attributes.screenBrightness;
            float userBrightness = Settings.System.getInt(target.getContext().getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 64) / 255f;
            appliedBrightness = Math.max(previousBrightness, Math.max(.24f, Math.min(1f, userBrightness)));
            attributes.screenBrightness = appliedBrightness;
            brightnessChanged = true;
            target.getContext().getSystemService(WindowManager.class).updateViewLayout(windowRoot, attributes);
        }
        if (ValueAnimator.areAnimatorsEnabled()) {
            light.animate().alpha(1f).setDuration(180L)
                    .setInterpolator(new PathInterpolator(.23f, 1f, .32f, 1f)).start();
        }
        android.util.Log.i("Pixel2DFace", "Low-light screen fill started");
    }

    void remove() {
        main.removeCallbacks(expiry);
        if (container == null) return;
        try {
            light.animate().cancel();
            container.getOverlay().remove(light);
            if (brightnessChanged && windowRoot.getLayoutParams() instanceof WindowManager.LayoutParams attributes
                    && Float.compare(attributes.screenBrightness, appliedBrightness) == 0) {
                // Do not overwrite a newer brightness decision made by SystemUI itself.
                attributes.screenBrightness = previousBrightness;
                if (windowRoot.isAttachedToWindow()) container.getContext()
                        .getSystemService(WindowManager.class).updateViewLayout(windowRoot, attributes);
            }
        } catch (RuntimeException failure) {
            android.util.Log.w("Pixel2DFace", "Unable to restore screen light window", failure);
        } finally {
            container = null;
            windowRoot = null;
            light = null;
            brightnessChanged = false;
            android.util.Log.i("Pixel2DFace", "Low-light screen fill removed");
        }
    }

    /** Four soft edges keep the clock, face animation and credential controls readable. */
    private static final class EdgeLight extends View {
        private static final int WARM = 0xfffff0dd;
        private final Paint paint = new Paint();
        private final Shader[] gradients = new Shader[4];
        private float edge;

        EdgeLight(Context context) { super(context); }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            edge = Math.min(width * .15f, 72f * getResources().getDisplayMetrics().density);
            gradients[0] = new LinearGradient(0, 0, edge, 0, WARM, Color.TRANSPARENT, Shader.TileMode.CLAMP);
            gradients[1] = new LinearGradient(width, 0, width - edge, 0, WARM, Color.TRANSPARENT, Shader.TileMode.CLAMP);
            gradients[2] = new LinearGradient(0, 0, 0, edge, WARM, Color.TRANSPARENT, Shader.TileMode.CLAMP);
            gradients[3] = new LinearGradient(0, height, 0, height - edge, WARM, Color.TRANSPARENT, Shader.TileMode.CLAMP);
        }

        @Override protected void onDraw(Canvas canvas) {
            paint.setShader(gradients[0]); canvas.drawRect(0, 0, edge, getHeight(), paint);
            paint.setShader(gradients[1]); canvas.drawRect(getWidth() - edge, 0, getWidth(), getHeight(), paint);
            paint.setShader(gradients[2]); canvas.drawRect(0, 0, getWidth(), edge, paint);
            paint.setShader(gradients[3]); canvas.drawRect(0, getHeight() - edge, getWidth(), getHeight(), paint);
        }
    }
}
