package com.yusd.pixel2dface;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.widget.Button;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowKeyguardManager;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35}, shadows = AtomicFileHostShadow.class)
public final class DeviceCredentialConfirmationTest {
    private ShadowKeyguardManager keyguard;

    @Before public void secureDevice() {
        keyguard = shadowOf(RuntimeEnvironment.getApplication().getSystemService(KeyguardManager.class));
        keyguard.setIsDeviceSecure(true);
        keyguard.setIsDeviceLocked(false);
    }

    @Test public void confirmationAuthorizesExactlyOneAction() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            DeviceCredentialConfirmation confirmation = new DeviceCredentialConfirmation(activity);
            AtomicInteger accepted = new AtomicInteger();
            AtomicInteger denied = new AtomicInteger();
            confirmation.request("Confirm", accepted::incrementAndGet, denied::incrementAndGet);
            ShadowActivity.IntentForResult request = shadowOf(activity).getNextStartedActivityForResult();
            assertEquals("android.app.action.CONFIRM_DEVICE_CREDENTIAL", request.intent.getAction());
            assertNotNull(request.intent.getPackage());
            assertEquals(0, accepted.get());
            assertTrue(confirmation.isPending());
            assertTrue(confirmation.onActivityResult(request.requestCode, Activity.RESULT_OK));
            assertTrue(confirmation.onActivityResult(request.requestCode, Activity.RESULT_OK));
            assertEquals(1, accepted.get());
            assertEquals(0, denied.get());
            assertFalse(confirmation.isPending());
        }
    }

    @Test public void cancellationOrRelockingCannotAuthorizeMutation() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            DeviceCredentialConfirmation confirmation = new DeviceCredentialConfirmation(activity);
            AtomicInteger accepted = new AtomicInteger();
            AtomicInteger denied = new AtomicInteger();
            confirmation.request("Confirm", accepted::incrementAndGet, denied::incrementAndGet);
            int request = shadowOf(activity).getNextStartedActivityForResult().requestCode;
            confirmation.onActivityResult(request, Activity.RESULT_CANCELED);
            confirmation.request("Confirm", accepted::incrementAndGet, denied::incrementAndGet);
            request = shadowOf(activity).getNextStartedActivityForResult().requestCode;
            keyguard.setIsDeviceLocked(true);
            confirmation.onActivityResult(request, Activity.RESULT_OK);
            assertEquals(0, accepted.get());
            assertEquals(2, denied.get());
        }
    }

    @Test public void missingCredentialOrLostPendingActionCannotAuthorizeMutation() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            DeviceCredentialConfirmation confirmation = new DeviceCredentialConfirmation(activity);
            AtomicInteger accepted = new AtomicInteger();
            AtomicInteger denied = new AtomicInteger();
            keyguard.setIsDeviceSecure(false);
            confirmation.request("Confirm", accepted::incrementAndGet, denied::incrementAndGet);
            assertNull(shadowOf(activity).getNextStartedActivityForResult());
            assertEquals(1, denied.get());
            keyguard.setIsDeviceSecure(true);
            confirmation.request("Confirm", accepted::incrementAndGet, denied::incrementAndGet);
            int request = shadowOf(activity).getNextStartedActivityForResult().requestCode;
            confirmation.cancel();
            confirmation.onActivityResult(request, Activity.RESULT_OK);
            new DeviceCredentialConfirmation(activity).onActivityResult(request, Activity.RESULT_OK);
            assertEquals(0, accepted.get());
        }
    }

    @Test public void enrollmentChallengesBeforeCameraAndCancellationPreservesTemplate() {
        EmbeddingIdentity model = new EmbeddingIdentity(EmbeddingIdentityTest.vector(0), .22f);
        assertTrue(TemplateStore.saveIdentityModel(RuntimeEnvironment.getApplication(), model));
        long revision = TemplateStore.readState(RuntimeEnvironment.getApplication()).revision;
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), FaceCaptureActivity.class)
                .putExtra(Constants.EXTRA_MODE, Constants.MODE_ENROLL);
        try (ActivityController<FaceCaptureActivity> controller =
                Robolectric.buildActivity(FaceCaptureActivity.class, intent).setup()) {
            FaceCaptureActivity activity = controller.get();
            ShadowActivity.IntentForResult request = shadowOf(activity).getNextStartedActivityForResult();
            assertNotNull(request);
            assertEquals("android.app.action.CONFIRM_DEVICE_CREDENTIAL", request.intent.getAction());
            assertNull(org.robolectric.util.ReflectionHelpers.getField(activity, "camera"));
            activity.onActivityResult(request.requestCode, Activity.RESULT_CANCELED, null);
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(activity.isFinishing());
            assertEquals(revision, TemplateStore.readState(activity).revision);
        }
    }

    @Test public void recreatedEnrollmentCannotReuseAnOldConfirmation() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), FaceCaptureActivity.class)
                .putExtra(Constants.EXTRA_MODE, Constants.MODE_ENROLL);
        try (ActivityController<FaceCaptureActivity> controller =
                Robolectric.buildActivity(FaceCaptureActivity.class, intent).create(new Bundle())) {
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(controller.get().isFinishing());
            assertNull(shadowOf(controller.get()).getNextStartedActivityForResult());
            assertNull(org.robolectric.util.ReflectionHelpers.getField(controller.get(), "camera"));
        }
    }

    @Test public void enrollmentStartsPermissionFlowOnlyAfterConfirmation() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), FaceCaptureActivity.class)
                .putExtra(Constants.EXTRA_MODE, Constants.MODE_ENROLL);
        try (ActivityController<FaceCaptureActivity> controller =
                Robolectric.buildActivity(FaceCaptureActivity.class, intent).setup()) {
            FaceCaptureActivity activity = controller.get();
            assertNull(shadowOf(activity).getLastRequestedPermission());
            int request = shadowOf(activity).getNextStartedActivityForResult().requestCode;
            activity.onActivityResult(request, Activity.RESULT_OK, null);
            assertNotNull(shadowOf(activity).getLastRequestedPermission());
        }
    }

    @Test public void settingsWriteWaitsForConfirmationAndSurvivesPauseResume() throws Exception {
        assertTrue(TemplateStore.saveIdentityModel(RuntimeEnvironment.getApplication(),
                new EmbeddingIdentity(EmbeddingIdentityTest.vector(0), .22f)));
        try (ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            drainStore();
            Button strict = org.robolectric.util.ReflectionHelpers.getField(activity, "strictRecognitionButton");
            assertTrue(strict.performClick());
            int request = shadowOf(activity).getNextStartedActivityForResult().requestCode;
            drainStore();
            assertEquals(1, TemplateStore.getRecognitionProfile(activity));
            controller.pause().stop();
            activity.onActivityResult(request, Activity.RESULT_CANCELED, null);
            controller.start().resume();
            drainStore();
            assertEquals(1, TemplateStore.getRecognitionProfile(activity));
            assertTrue(strict.isEnabled());

            assertTrue(strict.performClick());
            request = shadowOf(activity).getNextStartedActivityForResult().requestCode;
            controller.pause().stop();
            activity.onActivityResult(request, Activity.RESULT_OK, null);
            controller.start().resume();
            drainStore();
            assertEquals(0, TemplateStore.getRecognitionProfile(activity));
            assertTrue(strict.isEnabled());
        }
    }

    private static void drainStore() throws Exception {
        // A completed write schedules a UI refresh, which schedules another store read.
        for (int pass = 0; pass < 2; pass++) {
            CountDownLatch drained = new CountDownLatch(1);
            StoreTasks.execute(drained::countDown);
            assertTrue(drained.await(5, TimeUnit.SECONDS));
            shadowOf(Looper.getMainLooper()).idle();
        }
    }
}
