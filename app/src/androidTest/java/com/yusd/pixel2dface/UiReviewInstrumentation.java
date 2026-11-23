package com.yusd.pixel2dface;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in visual review on a disposable Android Emulator. Fixtures are synthetic; these
 * screens are not evidence of biometric accuracy or a working SystemUI hook.
 * Build with -PuiReview. No production code exposes fixture or screenshot controls.
 */
public final class UiReviewInstrumentation extends Instrumentation {
    private Bundle arguments;
    private boolean reviewAllowed;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments;
        reviewAllowed = BuildConfig.DEBUG && "ranchu".equals(Build.HARDWARE);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        if (!reviewAllowed) {
            result.putString("error", "UI fixtures require a debuggable Android Emulator");
            finish(Activity.RESULT_CANCELED, result);
            return;
        }
        try {
            if (Boolean.parseBoolean(arguments.getString("islandReview","false"))) {
                boolean early = Boolean.parseBoolean(arguments.getString("earlySuccess", "false"));
                IslandMotionReview.export(getTargetContext(),
                        early || Boolean.parseBoolean(arguments.getString("directSuccess", "false")), early);
                result.putString("result","Three native styles and island geometry exported");
                finish(Activity.RESULT_OK,result);
                return;
            }
            if (Boolean.parseBoolean(arguments.getString("artReview", "false"))) {
                int style = Integer.parseInt(arguments.getString("style", String.valueOf(AnimationStyle.FOCUS)));
                boolean quickScan = Boolean.parseBoolean(arguments.getString("quickScan", "false"));
                MotionArtworkReview.export(getTargetContext(), style, quickScan,
                        Boolean.parseBoolean(arguments.getString("hardwareFrames", "false")),
                        Boolean.parseBoolean(arguments.getString("wideFrames", "false")));
                result.putString("result", AnimationStyle.name(style) + " frames and outcome continuity verified");
                finish(Activity.RESULT_OK, result);
                return;
            }
            if (Boolean.parseBoolean(arguments.getString("stillReview", "false"))) {
                // Android delivers the emulator's animator scale when the process opens a window.
                Activity window = startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                waitForIdleSync();
                MotionArtworkReview.verifyStillStates(window);
                result.putString("result", "All three styles show stationary scans and immediate, distinct results");
                finish(Activity.RESULT_OK, result);
                return;
            }
            if (arguments.getString("fixture", "empty").equals("enrolled")) {
                float[] vector = new float[EmbeddingIdentity.DIMENSIONS];
                vector[0] = 1f;
                if (!TemplateStore.saveIdentityModel(getTargetContext(), new EmbeddingIdentity(vector, .22f)))
                    throw new IllegalStateException("Unable to write emulator fixture");
                HookRuntimeStore.heartbeat(getTargetContext(), "Emulator UI fixture",
                        Boolean.parseBoolean(arguments.getString("legacyRuntime", "false")) ? 0 : BuildConfig.VERSION_CODE);
            } else if (!TemplateStore.tryClearTemplates(getTargetContext())) {
                throw new IllegalStateException("Unable to clear emulator fixture");
            }
            Activity activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            if (Boolean.parseBoolean(arguments.getString("overlayReview", "false"))) {
                reviewNativeOverlay(activity);
                result.putString("result", AnimationStyle.COUNT
                        + " native overlays animated above elevated content and cleaned up");
                finish(Activity.RESULT_OK, result);
                return;
            }
            // Keep instrumentation active while checking real navigation, camera failure,
            // permission recovery, font scale, orientation and UI-mode transitions.
            new CountDownLatch(1).await(180, TimeUnit.SECONDS);
            result.putString("result", "Visual review session ended");
            finish(Activity.RESULT_OK, result);
        } catch (Exception | AssertionError failure) {
            result.putString("error", failure.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void reviewNativeOverlay(Activity activity) throws Exception {
        SystemUiFeedback feedback = new SystemUiFeedback(new Handler(Looper.getMainLooper()));
        runOnMainSync(() -> {
            ViewGroup root = activity.findViewById(android.R.id.content);
            root.getChildAt(0).setElevation(100);
            feedback.captureRoot(root);
        });
        File images = getTargetContext().getExternalFilesDir("overlay-review");
        for (int position = 0; position < AnimationStyle.COUNT; position++) {
            int style = AnimationStyle.at(position);
            if (!TemplateStore.trySetAnimationStyle(getTargetContext(), style))
                throw new IllegalStateException("Style could not be saved");
            Bundle configuration = getTargetContext().getContentResolver().call(
                    android.net.Uri.parse(Constants.PROVIDER_URI), "state", null, null);
            runOnMainSync(() -> feedback.scanning(configuration.getInt("animation_style")));
            awaitFrame(320);
            UnlockFaceAnimationView view = (UnlockFaceAnimationView) field(feedback, "animation");
            float phase = (float) field(view, "phase");
            awaitFrame(360);
            if ((float) field(view, "phase") == phase || !(boolean) ((ValueAnimator) field(view, "ticker")).isStarted())
                throw new IllegalStateException("Overlay ticker did not advance: " + style);
            if ((int) field(view, "visualStyle") != style)
                throw new IllegalStateException("Overlay used another style");
            try (FileOutputStream out = new FileOutputStream(new File(images, "style-" + style + ".png"))) {
                Bitmap screenshot = getUiAutomation().takeScreenshot();
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, out);
                screenshot.recycle();
            }
            runOnMainSync(feedback::success);
            awaitFrame(view.motionDelay(view.resultDuration()) + 60);
            if (((ValueAnimator) field(view, "ticker")).isStarted())
                throw new IllegalStateException("Completed outcome kept animating");
            awaitFrame(290 + view.motionDelay(170));
            if (field(feedback, "animation") != null)
                throw new IllegalStateException("Outcome overlay was not removed");
        }
        // The swipe hint needs reading time and must eventually leave the keyguard.
        runOnMainSync(() -> feedback.scanning(AnimationStyle.FOCUS));
        awaitFrame(115);
        UnlockFaceAnimationView failedView = (UnlockFaceAnimationView) field(feedback, "animation");
        runOnMainSync(() -> feedback.failure(false,() -> {}));
        awaitFrame(failedView.motionDelay(failedView.resultDuration()) + 45);
        if (field(feedback, "animation") != failedView
                || ((ValueAnimator) field(failedView, "ticker")).isStarted())
            throw new IllegalStateException("Failure mark did not reach its visible resting pose");
        awaitFrame(FocusFailure.HINT_HOLD_MS - 180);
        if (!feedback.hasSwipeHint() || failedView.getAlpha() != 1)
            throw new IllegalStateException("Swipe hint cleared before its reading interval");
        awaitFrame(470);
        if (field(feedback, "animation") != null)
            throw new IllegalStateException("Failure overlay was not removed");
        runOnMainSync(() -> {
            feedback.scanning(AnimationStyle.FOCUS);
            feedback.failure(true,() -> {} );
        });
        if (field(feedback, "animation") != null)
            throw new IllegalStateException("Swipe hint appeared on the credential screen");
        verifySwipeDispatch(activity,feedback);
        runOnMainSync(feedback::remove);
    }

    private void verifySwipeDispatch(Activity activity,SystemUiFeedback feedback) throws Exception {
        int[] requests = {0};
        runOnMainSync(() -> {
            feedback.scanning(AnimationStyle.FOCUS);
            feedback.failure(false, () -> requests[0]++);
        });
        awaitFrame(FocusMotion.FAILURE_DURATION_MS + 65);
        RetrySwipeTarget target = (RetrySwipeTarget)field(feedback,"retryTarget");
        UnlockFaceAnimationView old = (UnlockFaceAnimationView)field(feedback,"animation");
        if (target == null || !target.isShown()) throw new AssertionError("Missing native swipe control");
        ViewGroup container = activity.findViewById(android.R.id.content);
        float x = target.getX()+target.getWidth()/2f, y=target.getY()+target.getHeight()/2f;
        float distance = 38*activity.getResources().getDisplayMetrics().density;
        Field amount = UnlockFaceAnimationView.class.getDeclaredField("drag");
        amount.setAccessible(true);
        runOnMainSync(() -> {
            drag(container,x,y,0,-distance,MotionEvent.ACTION_CANCEL);
            try {
                float returning = amount.getFloat(old);
                long now = SystemClock.uptimeMillis();
                pointer(container, now, 0, MotionEvent.ACTION_DOWN, x, y);
                pointer(container, now, 8, MotionEvent.ACTION_MOVE, x, y);
                if (returning <= 0 || Math.abs(amount.getFloat(old) - returning) > .001f)
                    throw new AssertionError("Grabbing the returning capsule caused a position jump");
                pointer(container, now, 16, MotionEvent.ACTION_CANCEL, x, y);
            } catch (IllegalAccessException error) {
                throw new AssertionError(error);
            }
            drag(container,x,y,distance,0,MotionEvent.ACTION_UP);
            drag(container,x,y,0,distance,MotionEvent.ACTION_UP);
            drag(container,x,y,0,0,MotionEvent.ACTION_UP);
            if (requests[0] != 0) throw new AssertionError("Cancelled gestures or a tap triggered retry");
            drag(container,x,y,0,-distance,MotionEvent.ACTION_UP);
            if (requests[0] != 1 || !feedback.waitingForCapture()) throw new AssertionError("Swipe did not request retry");
            feedback.scanning(AnimationStyle.FOCUS);
        });
        if (field(feedback,"animation") != old || field(feedback,"retryTarget") != null || feedback.waitingForCapture())
            throw new AssertionError("Retry lost its surface or left a touch target over scanning");
        runOnMainSync(() -> feedback.failure(false,null));
        awaitFrame(760);
        if (field(feedback,"retryTarget") != null) throw new AssertionError("Terminal failure offered a retry");
    }

    private static void drag(ViewGroup parent,float x,float y,float dx,float dy,int end) {
        long now = SystemClock.uptimeMillis();
        int[] actions = {MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,end};
        for (int i=0;i<actions.length;i++) {
            pointer(parent, now, 80*i, actions[i], x+dx*i/2, y+dy*i/2);
        }
    }

    private static void pointer(ViewGroup parent, long down, int offset, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, down + offset, action, x, y, 0);
        try { parent.dispatchTouchEvent(event); } finally { event.recycle(); }
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private void awaitFrame(long milliseconds) throws InterruptedException {
        CountDownLatch frame = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).postDelayed(frame::countDown, milliseconds);
        if (!frame.await(milliseconds + 3000, TimeUnit.MILLISECONDS)) throw new IllegalStateException("UI thread stalled");
    }

    @Override public void callActivityOnResume(Activity activity) {
        super.callActivityOnResume(activity);
        if (reviewAllowed && activity.getClass() == FaceCaptureActivity.class) {
            // The fresh emulator contains no real face images. Production keeps FLAG_SECURE.
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }
}
