package com.yusd.pixel2dface;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
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
                TemplateStore.recordHookHeartbeat(getTargetContext(), "Emulator UI fixture");
            } else if (!TemplateStore.tryClearTemplates(getTargetContext())) {
                throw new IllegalStateException("Unable to clear emulator fixture");
            }
            startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
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

    @Override public void callActivityOnResume(Activity activity) {
        super.callActivityOnResume(activity);
        if (reviewAllowed && activity.getClass() == FaceCaptureActivity.class) {
            // The fresh emulator contains no real face images. Production keeps FLAG_SECURE.
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }
}
