package com.yusd.pixel2dface;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XposedHelpers;

/** Optional visual adapters. No method in this class grants authentication. */
final class SystemUiFeedback {
    private final Handler main;
    private volatile WeakReference<Object> bouncerManager = new WeakReference<>(null);
    private volatile WeakReference<Object> entryIcon = new WeakReference<>(null);
    private volatile WeakReference<ViewGroup> root = new WeakReference<>(null);
    private UnlockFaceAnimationView animation;
    private int style = UnlockFaceAnimationView.STYLE_FACE_ID;

    SystemUiFeedback(Handler main) {
        this.main = main;
    }

    void captureBouncer(Object manager) {
        bouncerManager = new WeakReference<>(manager);
    }

    void captureEntryIcon(Object view) {
        entryIcon = new WeakReference<>(view);
    }

    void captureRoot(ViewGroup view) {
        root = new WeakReference<>(view);
    }

    boolean isBouncerShowing() {
        Object manager = bouncerManager.get();
        if (manager == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(XposedHelpers.callMethod(manager, "isBouncerShowing"));
        } catch (Throwable error) {
            SystemUiLog.error("Credential-bouncer state unavailable; retaining swipe", error);
            return false;
        }
    }

    void scanning() {
        onMain(() -> {
            ViewGroup container = root.get();
            if (container == null) {
                return;
            }
            removeCurrent();
            try {
                UnlockFaceAnimationView view = new UnlockFaceAnimationView(container.getContext(), style);
                view.setClickable(false);
                view.setFocusable(false);
                view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                float density = container.getResources().getDisplayMetrics().density;
                int screenWidth = container.getResources().getConfiguration().screenWidthDp;
                float widthDp = Math.min(176f, Math.max(144f,
                        screenWidth > 0 ? screenWidth - 32f : 176f));
                int height = Math.round(76f * density);
                int statusBar = statusBarInset(container);
                int top = Math.max(Math.round(90f * density), statusBar + Math.round(42f * density));
                if (container.getHeight() > 0) {
                    top = Math.min(top, Math.max(statusBar,
                            container.getHeight() - height - Math.round(16f * density)));
                }
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        Math.round(widthDp * density), height, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                params.topMargin = top;
                container.addView(view, params);
                view.bringToFront();
                animation = view;
            } catch (Throwable error) {
                SystemUiLog.error("Native keyguard root rejected animation", error);
            }
        });
    }

    void style(int requested) {
        onMain(() -> {
            style = requested == UnlockFaceAnimationView.STYLE_DYNAMIC_ISLAND
                    ? UnlockFaceAnimationView.STYLE_DYNAMIC_ISLAND
                    : UnlockFaceAnimationView.STYLE_FACE_ID;
            if (animation != null) {
                animation.setVisualStyle(style);
            }
        });
    }

    void success() {
        onMain(() -> {
            UnlockFaceAnimationView shown = animation;
            if (shown != null) {
                shown.showSuccess();
                main.postDelayed(() -> removeIfCurrent(shown), 720L);
            }
        });
    }

    void failure() {
        onMain(() -> {
            UnlockFaceAnimationView shown = animation;
            if (shown != null) {
                shown.showFailure("请使用密码");
                // A previous session's delayed cleanup must never remove a new animation.
                main.postDelayed(() -> removeIfCurrent(shown), 700L);
            }
        });
    }

    void remove() {
        onMain(this::removeCurrent);
    }

    private void removeIfCurrent(UnlockFaceAnimationView expected) {
        if (animation == expected) {
            removeCurrent();
        }
    }

    private void removeCurrent() {
        UnlockFaceAnimationView shown = animation;
        animation = null;
        try {
            if (shown != null && shown.getParent() instanceof ViewGroup) {
                ((ViewGroup) shown.getParent()).removeView(shown);
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
