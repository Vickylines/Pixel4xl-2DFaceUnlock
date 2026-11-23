package com.yusd.pixel2dface;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;

/** Shared native drawing surface. A separate, bounded input target owns the retry gesture. */
public final class UnlockFaceAnimationView extends View {
    public static final int STYLE_FACE_ID = AnimationStyle.FACE, STYLE_DYNAMIC_ISLAND = AnimationStyle.ISLAND;
    static final int SEARCHING = 0, SUCCESS = 1, FAILURE = 2;
    private final float density;
    private final ValueAnimator ticker = ValueAnimator.ofFloat(0,1);
    private LockscreenStyleRenderer artwork;
    private int visualStyle, state = SEARCHING;
    private float phase, outcomePhase, outcomeAge, retryFrom = -1, drag, releasedDrag;
    private float durationScale = 1;
    private long stateStartedAt, scanStartedAt;
    private boolean visible = true, retryAllowed, waitingForCapture;
    private View animationHost;

    public UnlockFaceAnimationView(Context context) { this(context,STYLE_FACE_ID); }

    public UnlockFaceAnimationView(Context context, int style) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        visualStyle = AnimationStyle.normalize(style);
        artwork = new LockscreenStyleRenderer(getResources().getConfiguration().fontScale, visualStyle);
        setContentDescription("正在进行人脸识别");
        ticker.setRepeatCount(ValueAnimator.INFINITE);
        ticker.setInterpolator(new LinearInterpolator());
        ticker.setDuration(scanCycleMs());
        ticker.addUpdateListener(animation -> {
            phase = (float) animation.getAnimatedValue();
            invalidate();
            if (state != SEARCHING && transitionProgress() >= 1) ticker.cancel();
        });
    }

    public void setVisualStyle(int style) {
        int next = AnimationStyle.normalize(style);
        if (next == visualStyle) return;
        visualStyle = next;
        artwork = new LockscreenStyleRenderer(getResources().getConfiguration().fontScale, visualStyle);
        ticker.setDuration(scanCycleMs());
        invalidate();
    }

    void bindAnimationHost(View host) { animationHost = host; }
    boolean isFailure() { return state == FAILURE; }
    boolean waitingForCapture() { return waitingForCapture; }
    int retryWidthPx() { return Math.round(artwork.hintWidth(true) * renderScale()); }
    int retryOffsetYPx() { return Math.round(artwork.hintY() * renderScale()); }
    void pullRetry(float amount) { drag = amount; invalidate(); }

    public void showSuccess() {
        showSuccessAt(scanAge());
    }

    void showSuccessAt(float age) {
        if (state != SEARCHING) return;
        startResult(SUCCESS, age);
        setContentDescription("人脸识别成功，请向上滑动打开");
    }

    public void showFailure(String ignored) { showFailure(false); }

    void showFailure(boolean canRetry) {
        if (state != SEARCHING) return;
        startResult(FAILURE, scanAge());
        retryAllowed = canRetry;
        setContentDescription("人脸识别失败，" + (canRetry ? FocusFailure.SWIPE_HINT : FocusFailure.PASSCODE_HINT));
    }

    private void startResult(int next, float age) {
        outcomeAge = age;
        outcomePhase = waitingForCapture ? 0 : phase;
        if (next == SUCCESS && visualStyle == AnimationStyle.ISLAND) {
            boolean completedLap = !waitingForCapture && ticker.isStarted()
                    && ticker.getCurrentPlayTime() >= scanCycleMs();
            outcomePhase = IslandMotion.confirmationPhase(outcomePhase, age, completedLap);
        }
        durationScale = readDurationScale();
        state = next;
        stateStartedAt = SystemClock.elapsedRealtime();
        waitingForCapture = false;
        invalidate();
    }

    void restartSearching() { beginScan(false,false); }
    void retrySearching(boolean waiting) { beginScan(true,waiting); }

    private void beginScan(boolean fromHint, boolean waiting) {
        retryFrom = fromHint && state == FAILURE ? transitionProgress() * resultDuration() : -1;
        releasedDrag = fromHint ? drag : 0;
        durationScale = readDurationScale();
        drag = 0;
        waitingForCapture = waiting;
        state = SEARCHING;
        phase = 0;
        scanStartedAt = SystemClock.elapsedRealtime();
        if (ticker.isStarted()) ticker.setCurrentFraction(0);
        setContentDescription(waiting ? "准备重新识别" : "正在进行人脸识别");
        resumeTicker();
        invalidate();
    }

    void captureStarted() {
        if (!waitingForCapture) return;
        waitingForCapture = false;
        setContentDescription("正在进行人脸识别");
        phase = 0;
        if (ticker.isStarted()) ticker.setCurrentFraction(0);
        invalidate();
    }

    private float scanAge() {
        return !isAttachedToWindow() || scanStartedAt == 0 ? 1000f
                : (SystemClock.elapsedRealtime() - scanStartedAt) / durationScale;
    }

    private float readDurationScale() {
        float scale = Build.VERSION.SDK_INT >= 33 ? ValueAnimator.getDurationScale()
                : Settings.Global.getFloat(getContext().getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        return scale > 0 && Float.isFinite(scale) ? scale : 1;
    }

    long motionDelay(long milliseconds) {
        return ValueAnimator.areAnimatorsEnabled() ? Math.round(milliseconds * durationScale) : 0;
    }

    long successHoldMs() {
        if (!ValueAnimator.areAnimatorsEnabled()) return 650;
        return visualStyle == AnimationStyle.ISLAND ? motionDelay(135) : 275;
    }

    long scanCycleMs() {
        if (visualStyle == AnimationStyle.ISLAND) return IslandMotion.CYCLE_MS;
        return visualStyle == AnimationStyle.FACE ? FaceHalo.CYCLE_MS : FocusMotion.CYCLE_MS;
    }

    long resultDuration() {
        if (state == FAILURE) return FocusMotion.FAILURE_DURATION_MS;
        if (visualStyle == AnimationStyle.FACE) return 670;
        if (visualStyle == AnimationStyle.ISLAND) return IslandMotion.resultDuration(outcomePhase);
        return FocusMotion.resultDuration(outcomePhase,false);
    }

    private float transitionProgress() {
        if (!ValueAnimator.areAnimatorsEnabled()) return 1;
        return FocusMotion.clamp((SystemClock.elapsedRealtime()-stateStartedAt)/(durationScale * resultDuration()));
    }

    @Override protected void onDraw(Canvas canvas) {
        drawFrame(canvas,state == SEARCHING ? 0 : transitionProgress());
    }

    void drawFrame(Canvas canvas,float progress) {
        drawFrameAt(canvas,progress,state == SEARCHING ? scanAge() : outcomeAge + progress*resultDuration());
    }

    private float renderScale() { return Math.min(density, Math.min(getWidth()/176f,getHeight()/88f)); }

    void drawThumbnail(Canvas canvas) {
        float pose = visualStyle == AnimationStyle.ISLAND ? IslandMotion.TURN_END_MS / (float) IslandMotion.CYCLE_MS : 0;
        drawArtwork(canvas, pose, 0, 1000, 0, false);
    }

    void drawFrameAt(Canvas canvas,float progress,float age) {
        boolean motion = ValueAnimator.areAnimatorsEnabled();
        float sampleAge = motion ? age : 1000;
        float sample = state == SEARCHING ? (motion && !waitingForCapture ? phase : 0) : outcomePhase;
        if (state == FAILURE && motion)
            sample = (sample + progress * resultDuration() / scanCycleMs()) % 1;
        if (visualStyle == AnimationStyle.FACE && state == SUCCESS && motion)
            sample = (sample + progress*(1-.5f*progress)*resultDuration()/scanCycleMs()) % 1;
        float pull = motion ? drag + releasedDrag * (1 - FocusMotion.ease(0,190,age)) : 0;
        drawArtwork(canvas, sample, progress, sampleAge, pull, motion);
    }

    private void drawArtwork(Canvas canvas, float sample, float progress, float age, float pull, boolean motion) {
        int save = canvas.save();
        canvas.translate(getWidth()/2f,getHeight()/2f);
        float scale = renderScale();
        canvas.scale(scale,scale);
        artwork.draw(canvas,state,sample,progress,age,resultDuration(),retryAllowed,retryFrom,pull,motion);
        canvas.restoreToCount(save);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        durationScale = readDurationScale();
        if (scanStartedAt == 0) scanStartedAt = SystemClock.elapsedRealtime();
        if (ValueAnimator.areAnimatorsEnabled()) {
            resumeTicker();
            if (visualStyle != AnimationStyle.ISLAND) {
                setAlpha(.78f);
                animate().alpha(1f).setDuration(145).setInterpolator(new PathInterpolator(.22f,1,.36f,1)).start();
            }
        }
    }

    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        this.visible = animationHost == null ? visible : animationHost.isShown();
        if (!isAttachedToWindow()) return;
        if (this.visible) resumeTicker(); else ticker.cancel();
    }

    private void resumeTicker() {
        View host = animationHost == null ? this : animationHost;
        if (!isAttachedToWindow() || !visible || host.getWindowVisibility()!=VISIBLE
                || !ValueAnimator.areAnimatorsEnabled() || ticker.isStarted()
                || (state != SEARCHING && transitionProgress() >= 1)) return;
        float previous = phase;
        ticker.start();
        ticker.setCurrentFraction(previous);
    }

    @Override protected void onDetachedFromWindow() {
        animate().cancel();
        ticker.cancel();
        super.onDetachedFromWindow();
    }
}
