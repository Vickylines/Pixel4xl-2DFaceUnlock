package com.yusd.pixel2dface;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/** Exercises visible recovery without native camera mocks or changing recognition policy. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class CaptureRecoveryTest {
    @Before public void denyCamera() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.CAMERA);
    }

    @Test public void permissionDenialStaysOnARecoverableScreenAndReleasesWakeFlag() {
        try (ActivityController<FaceCaptureActivity> controller = capture()) {
            FaceCaptureActivity activity = controller.get();
            deny(activity);
            assertFalse(activity.isFinishing());
            assertEquals(0, activity.getWindow().getAttributes().flags
                    & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            assertNotEquals(0, activity.getWindow().getAttributes().flags
                    & WindowManager.LayoutParams.FLAG_SECURE);
            assertTrue(find(activity.getWindow().getDecorView(), "开启相机权限").performClick());
            assertEquals("android.settings.APPLICATION_DETAILS_SETTINGS",
                    shadowOf(activity).getNextStartedActivityForResult().intent.getAction());
        }
    }

    @Test public void permissionRecoveryForwardsTheOriginalCallerToAFreshAttempt() {
        try (ActivityController<FaceCaptureActivity> controller = capture()) {
            FaceCaptureActivity activity = controller.get();
            deny(activity);
            find(activity.getWindow().getDecorView(), "开启相机权限").performClick();
            int request = shadowOf(activity).getNextStartedActivityForResult().requestCode;
            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.CAMERA);
            activity.onActivityResult(request, Activity.RESULT_CANCELED, null);
            Intent retry = shadowOf(activity).getNextStartedActivity();
            assertEquals(FaceCaptureActivity.class.getName(), retry.getComponent().getClassName());
            assertEquals(Constants.MODE_TEST, retry.getStringExtra(Constants.EXTRA_MODE));
            assertEquals(Intent.FLAG_ACTIVITY_FORWARD_RESULT, retry.getFlags());
            assertTrue(activity.isFinishing());
        }
    }

    @Test public void restoredResultDoesNotRestartCameraOrReuseEnrollmentAuthorization() {
        Bundle state = new Bundle();
        state.putBoolean("showing_result", true);
        state.putBoolean("result_success", true);
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), FaceCaptureActivity.class)
                .putExtra(Constants.EXTRA_MODE, Constants.MODE_ENROLL);
        try (ActivityController<FaceCaptureActivity> controller =
                Robolectric.buildActivity(FaceCaptureActivity.class, intent).create(state).start().resume()) {
            FaceCaptureActivity activity = controller.get();
            assertNotNull(find(activity.getWindow().getDecorView(), "面容已录入"));
            assertNull(shadowOf(activity).getLastRequestedPermission());
            assertNull(shadowOf(activity).getNextStartedActivityForResult());
            assertNull(org.robolectric.util.ReflectionHelpers.getField(activity, "camera"));
            find(activity.getWindow().getDecorView(), "完成").performClick();
            assertTrue(activity.isFinishing());
        }
    }

    @Test public void backCancelsWithoutAnErrorResultScreen() {
        try (ActivityController<FaceCaptureActivity> controller = capture()) {
            FaceCaptureActivity activity = controller.get();
            activity.getOnBackPressedDispatcher().onBackPressed();
            assertTrue(activity.isFinishing());
            assertNull(find(activity.getWindow().getDecorView(), "这次未能识别"));
        }
    }

    private static ActivityController<FaceCaptureActivity> capture() {
        return Robolectric.buildActivity(FaceCaptureActivity.class,
                new Intent(RuntimeEnvironment.getApplication(), FaceCaptureActivity.class)
                        .putExtra(Constants.EXTRA_MODE, Constants.MODE_TEST)).setup();
    }
    private static void deny(FaceCaptureActivity activity) {
        activity.onRequestPermissionsResult(200, new String[]{Manifest.permission.CAMERA},
                new int[]{PackageManager.PERMISSION_DENIED});
        shadowOf(Looper.getMainLooper()).idle();
    }
    private static View find(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
