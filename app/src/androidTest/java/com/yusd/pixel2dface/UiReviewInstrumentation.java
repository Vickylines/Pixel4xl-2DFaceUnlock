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
                result.putString("result", "All six native overlays animated above elevated content and cleaned up");
                finish(Activity.RESULT_OK, result);
                return;
            }
            // Keep instrumentation active while checking real navigation, camera failure,
            // permission recovery, font scale, orientation and UI-mode transitions.
            new CountDownLatch(1).await(180, TimeUnit.SECONDS);
            result.putString("result", "Visual review session ended");
            finish(Activity.RESULT_OK, result);
        } catch (Exception failure) {
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
        for (int style = 0; style < AnimationStyle.COUNT; style++) {
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
            awaitFrame(750);
            if (((ValueAnimator) field(view, "ticker")).isStarted())
                throw new IllegalStateException("Completed outcome kept animating");
            awaitFrame(450);
            if (field(feedback, "animation") != null)
                throw new IllegalStateException("Outcome overlay was not removed");
        }
        runOnMainSync(feedback::remove);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private void awaitFrame(long milliseconds) throws InterruptedException {
        CountDownLatch frame = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).postDelayed(frame::countDown, milliseconds);
        if (!frame.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("UI thread stalled");
    }

    @Override public void callActivityOnResume(Activity activity) {
        super.callActivityOnResume(activity);
        if (reviewAllowed && activity.getClass() == FaceCaptureActivity.class) {
            // The fresh emulator contains no real face images. Production keeps FLAG_SECURE.
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }
}
