package com.yusd.pixel2dface;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.view.WindowInsets;
import android.view.animation.PathInterpolator;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XposedHelpers;

/** Optional visual and haptic feedback for the native keyguard. */
final class SystemUiFeedback {
    private final Handler main;
    private volatile WeakReference<Object> entryIcon = new WeakReference<>(null);
    private volatile WeakReference<ViewGroup> root = new WeakReference<>(null);
    private UnlockFaceAnimationView animation;
    private RetrySwipeTarget retryTarget;
    private Runnable retryAction;
    private boolean retryOnCredentialPage;
    private long resultTicket;
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
            int nextStyle = AnimationStyle.normalize(requestedStyle);
            if (waitingForCapture() && style == nextStyle) animation.captureStarted();
            else detachAnimation();
            lightRequested = false;
            stopLight();
            style = nextStyle;
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
            int top = style == AnimationStyle.FACE
                    ? Math.max(Math.round(84f * density), statusBar + Math.round(28f * density))
                    : Math.max(Math.round(8f*density), statusBar + Math.round(3f*density));
            top = Math.min(top, Math.max(statusBar, container.getHeight() - height));
            int left = (container.getWidth() - width) / 2;
            animation.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            animation.layout(left, top, left + width, top + height);
            if (retryTarget != null && container instanceof FrameLayout)
                retryTarget.place((FrameLayout)container);
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
                dismissAfterResult(shown);
            }
        });
    }

    void failure(boolean credentialEntryVisible, Runnable retry) {
        onMain(() -> {
            scanningRequested = false;
            lightRequested = false;
            stopLight();
            UnlockFaceAnimationView shown = animation;
            if (shown != null) {
                if (credentialEntryVisible) {
                    detachAnimation();
                    return;
                }
                ViewGroup parent = root.get();
                boolean actionable = retry != null && parent instanceof FrameLayout;
                shown.showFailure(actionable);
                if (actionable) {
                    retryAction = retry;
                    long delay = shown.motionDelay(650);
                    long pendingTicket = resultTicket + 1;
                    main.postDelayed(() -> {
                        if (animation != shown || resultTicket != pendingTicket || !shown.isFailure()) return;
                        retryTarget = new RetrySwipeTarget(shown, () -> acceptRetry(shown,false));
                        retryTarget.place((FrameLayout)parent);
                    },delay);
                }
                dismissAfterResult(shown);
            }
        });
    }

    boolean hasSwipeHint() {
        return animation != null && (animation.isFailure() || animation.waitingForCapture());
    }

    boolean waitingForCapture() { return animation != null && animation.waitingForCapture(); }
    boolean retryOnCredentialPage() { return retryOnCredentialPage; }

    // The keyguard itself recognizes a swipe from the bottom. Use its committed
    // credential-page transition without intercepting navigation or keypad input.
    void credentialPageShown() {
        if (animation == null) return;
        if (animation.isFailure() && retryAction != null) acceptRetry(animation,true);
        else if (animation.isFailure() || (waitingForCapture() && !retryOnCredentialPage)) remove();
    }

    private void acceptRetry(UnlockFaceAnimationView shown, boolean fromCredentialPage) {
        if (animation != shown || !shown.isFailure() || retryAction == null) return;
        Runnable action = retryAction;
        retryAction = null;
        retryOnCredentialPage = fromCredentialPage;
        resultTicket++;
        shown.retrySearching(true);
        removeRetryTarget();
        action.run();
    }

    void resultHaptic(boolean success) {
        onMain(() -> {
            ViewGroup container = root.get();
            if (container == null || !container.isShown()) return;
            int effect;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                effect = success ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.REJECT;
            } else {
                effect = success ? HapticFeedbackConstants.KEYBOARD_TAP : HapticFeedbackConstants.LONG_PRESS;
            }
            try {
                container.performHapticFeedback(effect);
            } catch (RuntimeException error) {
                SystemUiLog.error("Unable to play keyguard haptic feedback", error);
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

    private void dismissAfterResult(UnlockFaceAnimationView shown) {
        long ticket = ++resultTicket;
        long hold = shown.successHoldMs();
        if (shown.isFailure()) {
            AccessibilityManager manager = shown.getContext().getSystemService(AccessibilityManager.class);
            hold = manager == null ? FocusFailure.HINT_HOLD_MS : manager.getRecommendedTimeoutMillis(
                    FocusFailure.HINT_HOLD_MS, AccessibilityManager.FLAG_CONTENT_TEXT | AccessibilityManager.FLAG_CONTENT_CONTROLS);
        }
        main.postDelayed(() -> dismissIfCurrent(shown,ticket),
                hold + shown.motionDelay(shown.resultDuration()));
    }

    private void dismissIfCurrent(UnlockFaceAnimationView expected, long ticket) {
        if (animation != expected || ticket != resultTicket) return;
        if (retryTarget != null && retryTarget.tracking()) {
            main.postDelayed(() -> dismissIfCurrent(expected,ticket),250);
            return;
        }
        retryAction = null;
        removeRetryTarget();
        expected.animate().alpha(0f).setDuration(160L)
                .setInterpolator(new PathInterpolator(0.23f, 1f, 0.32f, 1f))
                .withEndAction(() -> { if (animation == expected && ticket == resultTicket) detachAnimation(); }).start();
    }

    private void removeRetryTarget() {
        RetrySwipeTarget target = retryTarget;
        retryTarget = null;
        if (target != null && target.getParent() instanceof ViewGroup)
            ((ViewGroup)target.getParent()).removeView(target);
    }

    private void detachAnimation() {
        resultTicket++;
        retryAction = null;
        retryOnCredentialPage = false;
        removeRetryTarget();
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
                ? insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()).top
                : insets.getStableInsetTop();
    }
}
