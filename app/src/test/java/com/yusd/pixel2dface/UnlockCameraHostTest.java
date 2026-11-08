package com.yusd.pixel2dface;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Intent;
import android.os.Looper;
import android.view.WindowManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class UnlockCameraHostTest {
    private static final String TOKEN = "host-power-regression-token-000001";
    private ActivityController<Activity> controller;
    private Activity activity;
    private UnlockCameraHost host;
    private int cancellations;

    @Before public void createHost() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        host = new UnlockCameraHost(activity, TOKEN, () -> {
            cancellations++;
            host.close();
            activity.finish();
        });
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                "android.permission.STATUS_BAR_SERVICE");
    }

    @After public void closeHost() {
        host.close();
        controller.close();
    }

    @Test public void preparationAndAttachNeverKeepAwakeOrWakeTheScreen() {
        activity.setTurnScreenOn(true);
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        host.prepareWindow();
        assertNoWakeRequest();
        assertTrue(shadowOf(activity).getShowWhenLocked());
        host.attach();
        assertNoWakeRequest();
    }

    @Test public void screenOffCancelsBeforeModelInitializationFinishes() {
        host.prepareWindow();
        signal(new Intent(Intent.ACTION_SCREEN_OFF));
        assertEquals(1, cancellations);
        assertTrue(activity.isFinishing());
        assertFalse(shadowOf(activity).getShowWhenLocked());
        // A late model callback must not reattach or publish readiness after cancellation.
        host.attach();
        shadowOf(Looper.getMainLooper()).idle();
        assertNoWakeRequest();
        assertTrue(shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents().stream()
                .noneMatch(intent -> Constants.ACTION_CAMERA_HOST_READY.equals(intent.getAction())));
    }

    @Test public void earlyCancellationIsBoundToTheCurrentSession() {
        host.prepareWindow();
        signal(new Intent(Constants.ACTION_CANCEL_CAPTURE)
                .putExtra(Constants.EXTRA_SESSION_TOKEN, "previous-session"));
        assertEquals(0, cancellations);
        signal(new Intent(Constants.ACTION_CANCEL_CAPTURE)
                .putExtra(Constants.EXTRA_SESSION_TOKEN, TOKEN));
        assertEquals(1, cancellations);
        assertTrue(activity.isFinishing());
    }

    @Test public void screenOffAfterAttachReleasesTheHostOnlyOnce() {
        host.attach();
        signal(new Intent(Intent.ACTION_SCREEN_OFF));
        signal(new Intent(Intent.ACTION_SCREEN_OFF));
        assertEquals(1, cancellations);
        assertFalse(shadowOf(activity).getShowWhenLocked());
    }

    private void assertNoWakeRequest() {
        assertFalse(shadowOf(activity).getTurnScreenOn());
        assertEquals(0, activity.getWindow().getAttributes().flags
                & (WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON));
    }

    private void signal(Intent intent) {
        activity.sendBroadcast(intent);
        shadowOf(Looper.getMainLooper()).idle();
    }
}
