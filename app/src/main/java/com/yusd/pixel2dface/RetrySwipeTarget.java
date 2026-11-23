package com.yusd.pixel2dface;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;

/** A programmatic touch target for the visible capsule. All other keyguard input goes to its usual owner. */
@SuppressLint("ViewConstructor")
final class RetrySwipeTarget extends View {
    private final UnlockFaceAnimationView art;
    private final Runnable retry;
    private final float density, slop;
    private float downX, downY, amount, grabbedAmount;
    private boolean tracking, consumed;
    private ValueAnimator release;

    RetrySwipeTarget(UnlockFaceAnimationView art, Runnable retry) {
        super(art.getContext());
        this.art = art;
        this.retry = retry;
        density = getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        setContentDescription("继续人脸识别");
        setClickable(true);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void place(FrameLayout parent) {
        int height = Math.round(48 * density);
        int width = Math.min(parent.getWidth(), Math.max(height, art.retryWidthPx()));
        int left = art.getLeft() + (art.getWidth() - width) / 2;
        int start = parent.getLayoutDirection() == LAYOUT_DIRECTION_RTL
                ? parent.getWidth() - parent.getPaddingRight() - left - width : left - parent.getPaddingLeft();
        int top = art.getTop() + (art.getHeight() - height) / 2 + art.retryOffsetYPx() - parent.getPaddingTop();
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) getLayoutParams();
        if (params != null && params.width == width && params.height == height
                && params.getMarginStart() == start && params.topMargin == top) return;
        params = new FrameLayout.LayoutParams(width, height, Gravity.START | Gravity.TOP);
        params.setMarginStart(start);
        params.topMargin = top;
        if (getParent() == parent) {
            setLayoutParams(params);
        } else {
            float front = 0;
            for (int i = 0; i < parent.getChildCount(); i++) front = Math.max(front, parent.getChildAt(i).getZ());
            setZ(front + 1);
            parent.addView(this, params);
        }
    }

    boolean tracking() { return tracking; }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (consumed) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (release != null) release.cancel();
            grabbedAmount = amount;
            downX = event.getX();
            downY = event.getY();
            tracking = true;
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        if (!tracking) return true;
        float across = Math.abs(event.getX() - downX);
        float up = downY - event.getY();
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN
                || across > Math.max(slop * 2, Math.max(0, up) * 1.3f) || up < -slop) {
            settle();
            return true;
        }
        float travel = Math.copySign(Math.max(0, Math.abs(up) - slop * .35f), up);
        amount = FocusMotion.clamp(grabbedAmount + travel / (34 * density));
        art.pullRetry(amount);
        if (action == MotionEvent.ACTION_UP) {
            long duration = Math.max(1, event.getEventTime() - event.getDownTime());
            boolean flick = up >= Math.max(slop * 1.6f, 16 * density) && up / duration >= .32f * density;
            if (up > across * 1.3f && (up >= Math.max(slop * 2, 28 * density) || flick)) performClick();
            else settle();
        }
        return true;
    }

    @Override public boolean performClick() {
        if (consumed || !isShown()) return false;
        consumed = true;
        tracking = false;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        super.performClick();
        retry.run();
        return true;
    }

    private void settle() {
        tracking = false;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        if (amount == 0 || !ValueAnimator.areAnimatorsEnabled()) {
            amount = 0;
            art.pullRetry(0);
            return;
        }
        release = ValueAnimator.ofFloat(amount, 0);
        release.setInterpolator(new DecelerateInterpolator());
        release.setDuration(145);
        release.addUpdateListener(animation -> {
            amount = (float) animation.getAnimatedValue();
            art.pullRetry(amount);
        });
        release.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (release != null) release.cancel();
        if (!consumed) art.pullRetry(0);
        tracking = false;
        super.onDetachedFromWindow();
    }

    @Override public CharSequence getAccessibilityClassName() { return Button.class.getName(); }
}
