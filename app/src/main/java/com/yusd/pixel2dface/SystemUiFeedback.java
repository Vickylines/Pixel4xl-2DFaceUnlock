package com.yusd.pixel2dface;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.PathInterpolator;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XposedHelpers;

/** Optional visual adapters. No method in this class grants authentication. */
final class SystemUiFeedback {
    private final Handler main;
    private volatile WeakReference<Object> entryIcon = new WeakReference<>(null);
    private volatile WeakReference<ViewGroup> root = new WeakReference<>(null);
    private UnlockFaceAnimationView animation;
    private int style = UnlockFaceAnimationView.STYLE_FACE_ID;
    private boolean scanningRequested;
    private boolean lightRequested;
    private long lightDeadline;
    private final SystemUiScreenLight screenLight;
    private final View.OnLayoutChangeListener layoutListener =
            (v, l, t, r, b, ol, ot, or, ob) -> showWhenReady();
    private final View.OnAttachStateChangeListener attachListener = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) { showWhenReady(); }
        @Override public void onViewDetachedFromWindow(View view) { detachAnimation(); stopLight(); }
    };

    SystemUiFeedback(Handler main) {
        this.main = main;
        screenLight = new SystemUiScreenLight(main);
    }

    void captureEntryIcon(Object view) {
        entryIcon = new WeakReference<>(view);
    }

    void captureRoot(ViewGroup view) {
        onMain(() -> {
            ViewGroup previous = root.get();
            if (previous == view) return;
            detachAnimation();
            stopLight();
            if (previous != null) {
                previous.removeOnLayoutChangeListener(layoutListener);
                previous.removeOnAttachStateChangeListener(attachListener);
            }
            root = new WeakReference<>(view);
            view.addOnLayoutChangeListener(layoutListener);
            view.addOnAttachStateChangeListener(attachListener);
            showWhenReady();
        });
    }

    void scanning(int requestedStyle) {
        onMain(() -> {
            detachAnimation();
            lightRequested = false;
            stopLight();
            style = AnimationStyle.normalize(requestedStyle);
            scanningRequested = true;
            showWhenReady();
        });
    }

    void lowLight() {
        onMain(() -> {
            if (!scanningRequested || lightRequested) return;
            lightRequested = true;
            lightDeadline = android.os.SystemClock.elapsedRealtime() + 12_000L;
            showWhenReady();
        });
    }

    private void showWhenReady() {
        ViewGroup container = root.get();
        if (container == null || !container.isAttachedToWindow() || container.getWidth() == 0) return;
        try {
            if (scanningRequested && lightRequested) {
                if (android.os.SystemClock.elapsedRealtime() < lightDeadline) {
                    screenLight.show(container, lightDeadline);
                } else {
                    lightRequested = false;
                    stopLight();
                }
            }
            if (animation == null && scanningRequested) {
                UnlockFaceAnimationView view = new UnlockFaceAnimationView(container.getContext(), style);
                view.bindAnimationHost(container);
                view.setClickable(false);
                view.setFocusable(false);
                view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                // The native overlay draws above elevated notification/clock children and
                // never takes touch or accessibility focus from the actual keyguard.
                container.getOverlay().add(view);
                animation = view;
            }
            if (animation == null) return;
            float density = container.getResources().getDisplayMetrics().density;
            int width = Math.min(Math.round(176f * density), container.getWidth());
            int height = Math.round(88f * density);
            int statusBar = statusBarInset(container);
            int top = Math.max(Math.round(84f * density), statusBar + Math.round(28f * density));
            top = Math.min(top, Math.max(statusBar, container.getHeight() - height));
            int left = (container.getWidth() - width) / 2;
            animation.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            animation.layout(left, top, left + width, top + height);
        } catch (Throwable error) {
            SystemUiLog.error("Native keyguard root rejected animation", error);
        }
    }

    void success() {
        onMain(() -> {
            scanningRequested = false;
            lightRequested = false;
            stopLight();
            UnlockFaceAnimationView shown = animation;
            if (shown != null) {
                shown.showSuccess();
                main.postDelayed(() -> dismissIfCurrent(shown), 800L);
            }
        });
    }

    void failure() {
        onMain(() -> {
            scanningRequested = false;
            lightRequested = false;
            stopLight();
            UnlockFaceAnimationView shown = animation;
            if (shown != null) {
                shown.showFailure("请使用密码");
                // A previous session's delayed cleanup must never remove a new animation.
                main.postDelayed(() -> dismissIfCurrent(shown), 800L);
            }
        });
    }

    void remove() {
        onMain(() -> {
            scanningRequested = false;
            lightRequested = false;
            stopLight();
            detachAnimation();
        });
    }

    private void stopLight() {
        screenLight.remove();
    }

    private void dismissIfCurrent(UnlockFaceAnimationView expected) {
        if (animation != expected) return;
        expected.animate().alpha(0f).setDuration(160L)
                .setInterpolator(new PathInterpolator(0.23f, 1f, 0.32f, 1f))
                .withEndAction(() -> { if (animation == expected) detachAnimation(); }).start();
    }

    private void detachAnimation() {
        UnlockFaceAnimationView shown = animation;
        animation = null;
        try {
            ViewGroup container = root.get();
            if (shown != null && container != null) {
                shown.animate().cancel();
                container.getOverlay().remove(shown);
            }
        } catch (Throwable error) {
            SystemUiLog.error("Unable to detach keyguard animation", error);
        }
    }

    void unlockedIcon(boolean unlocked) {
        onMain(() -> {
            Object container = entryIcon.get();
            if (!(container instanceof View)) {
                return;
            }
            try {
                ClassLoader loader = container.getClass().getClassLoader();
                Class<?> iconClass = XposedHelpers.findClass(
                        "com.android.systemui.keyguard.ui.view.DeviceEntryIconView$IconType", loader);
                Object icon = XposedHelpers.getStaticObjectField(iconClass, unlocked ? "UNLOCK" : "LOCK");
                int[] state = (int[]) XposedHelpers.callStaticMethod(container.getClass(),
                        "getIconState", icon, false);
                XposedHelpers.callMethod(XposedHelpers.getObjectField(container, "iconView"),
                        "setImageState", state, true);
                View view = (View) container;
                int description = (int) XposedHelpers.callMethod(icon, "getContentDescriptionResId");
                view.setContentDescription(view.getContext().getString(description));
                Class<?> hintClass = XposedHelpers.findClass(
                        "com.android.systemui.keyguard.ui.view.DeviceEntryIconView$AccessibilityHintType", loader);
                XposedHelpers.setObjectField(container, "accessibilityHintType",
                        XposedHelpers.getStaticObjectField(hintClass, unlocked ? "ENTER" : "BOUNCER"));
                view.sendAccessibilityEvent(2048);
            } catch (Throwable error) {
                SystemUiLog.error("Native lock icon adapter unavailable", error);
            }
        });
    }

    private void onMain(Runnable task) {
        if (Looper.myLooper() == main.getLooper()) {
            task.run();
        } else {
            main.post(task);
        }
    }

    @SuppressWarnings("deprecation")
    private static int statusBarInset(View view) {
        WindowInsets insets = view.getRootWindowInsets();
        if (insets == null) {
            return 0;
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? insets.getInsets(WindowInsets.Type.statusBars()).top : insets.getStableInsetTop();
    }
}
