package com.yusd.pixel2dface;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = AtomicFileHostShadow.class)
public final class CaptureOutcomeStorageTest {
    private Context context;

    @Before public void enrollment() {
        context = RuntimeEnvironment.getApplication();
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.CAMERA);
        assertTrue(TemplateStore.saveIdentityModel(context,
                new EmbeddingIdentity(EmbeddingIdentityTest.vector(0), .22f)));
    }

    @Test public void successClearsEarlierFailureEvenWhenTheStoreQueueWasBusy() throws Exception {
        assertTrue(TemplateStore.tryRecordFailure(context));
        assertTrue(TemplateStore.tryRecordFailure(context));
        queueFailureThenSuccess();
        assertEquals("0", AtomicStoreFiles.readProperties(context,
                context.getFilesDir()).getProperty("failures"));
    }

    @Test public void queuedFifthFailureCannotBeErasedByANewerRecognition() throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) assertTrue(TemplateStore.tryRecordFailure(context));
        queueFailureThenSuccess();
        assertTrue(TemplateStore.getLockoutUntil(context) > System.currentTimeMillis());
    }

    @Test public void cancellationStillWinsBeforeSuccessCanQueueItsWrite() throws Exception {
        assertTrue(TemplateStore.tryRecordFailure(context));
        try (ActivityController<FaceCaptureActivity> controller = capture()) {
            FaceCaptureActivity activity = controller.get();
            ReflectionHelpers.callInstanceMethod(activity, "finishFailure",
                    from(boolean.class, false), from(String.class, "cancelled"));
            success(activity);
            drainStore();
            assertEquals("1", AtomicStoreFiles.readProperties(context,
                    context.getFilesDir()).getProperty("failures"));
        }
    }

    private void queueFailureThenSuccess() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        StoreTasks.execute(() -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
        });
        try (ActivityController<FaceCaptureActivity> oldCapture = capture();
                ActivityController<FaceCaptureActivity> newCapture = capture()) {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try {
                ReflectionHelpers.callInstanceMethod(oldCapture.get(), "finishFailure",
                        from(boolean.class, true), from(String.class, "timeout"));
                success(newCapture.get());
            } finally { release.countDown(); }
            drainStore();
        } finally { release.countDown(); }
    }

    private ActivityController<FaceCaptureActivity> capture() {
        Intent intent = new Intent(context, FaceCaptureActivity.class)
                .putExtra(Constants.EXTRA_MODE, Constants.MODE_TEST);
        ActivityController<FaceCaptureActivity> controller =
                Robolectric.buildActivity(FaceCaptureActivity.class, intent).create();
        // Exercise the production completion methods without starting a native camera.
        assertNull(ReflectionHelpers.getField(controller.get(), "camera"));
        ReflectionHelpers.setField(controller.get(), "mode", Constants.MODE_UNLOCK);
        ReflectionHelpers.setField(controller.get(), "settings", TemplateStore.loadRecognitionSettings(context));
        return controller;
    }

    private static void success(FaceCaptureActivity activity) {
        ReflectionHelpers.callInstanceMethod(activity, "finishSuccess",
                from(float.class, .1f), from(boolean.class, false));
    }

    private static void drainStore() throws Exception {
        CountDownLatch drained = new CountDownLatch(1);
        StoreTasks.execute(drained::countDown);
        assertTrue(drained.await(5, TimeUnit.SECONDS));
    }
}
