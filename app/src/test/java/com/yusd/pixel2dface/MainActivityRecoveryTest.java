package com.yusd.pixel2dface;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.Looper;
import android.widget.Button;
import androidx.appcompat.app.AlertDialog;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35}, shadows = AtomicFileHostShadow.class)
public final class MainActivityRecoveryTest {
    private final byte[] corrupt = "broken=\\uBAD!".getBytes(StandardCharsets.UTF_8);
    private File store;

    @Before public void unreadableEnrollment() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceSecure(true);
        shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceLocked(false);
        store = new File(context.getFilesDir(), AtomicStoreFiles.STORE_FILE);
        Files.write(store.toPath(), corrupt);
    }

    @Test public void cancelledRepairPreservesTheFileAndNeverStartsCapture() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            drainStore();
            Button reenroll = ReflectionHelpers.getField(activity, "enrollButton");
            assertFalse(reenroll.isEnabled());
            repairDialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertArrayEquals(corrupt, Files.readAllBytes(store.toPath()));
            repairDialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            ShadowActivity.IntentForResult challenge = shadowOf(activity).getNextStartedActivityForResult();
            assertEquals("android.app.action.CONFIRM_DEVICE_CREDENTIAL", challenge.intent.getAction());
            activity.onActivityResult(challenge.requestCode, Activity.RESULT_CANCELED, null);
            drainStore();
            assertArrayEquals(corrupt, Files.readAllBytes(store.toPath()));
            assertNull(shadowOf(activity).getNextStartedActivityForResult());
        }
    }

    @Test public void confirmedRepairMakesEnrollmentUsableAgain() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            drainStore();
            repairDialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            ShadowActivity.IntentForResult challenge = shadowOf(activity).getNextStartedActivityForResult();
            assertEquals("android.app.action.CONFIRM_DEVICE_CREDENTIAL", challenge.intent.getAction());
            assertArrayEquals(corrupt, Files.readAllBytes(store.toPath()));
            activity.onActivityResult(challenge.requestCode, Activity.RESULT_OK, null);
            drainStore();
            TemplateStore.StateSnapshot state = TemplateStore.readState(activity);
            assertTrue(state.storageAvailable);
            assertFalse(state.enrolled);
            assertFalse(state.enabled);
            Button primary = ReflectionHelpers.getField(activity, "primaryButton");
            primary.performClick();
            ShadowActivity.IntentForResult capture = shadowOf(activity).getNextStartedActivityForResult();
            assertEquals(FaceCaptureActivity.class.getName(), capture.intent.getComponent().getClassName());
            assertEquals(Constants.MODE_ENROLL, capture.intent.getStringExtra(Constants.EXTRA_MODE));
        }
    }

    private static AlertDialog repairDialog(MainActivity activity) {
        Button primary = ReflectionHelpers.getField(activity, "primaryButton");
        assertTrue(primary.performClick());
        assertNull(shadowOf(activity).getNextStartedActivityForResult());
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        return dialog;
    }

    private static void drainStore() throws Exception {
        for (int pass = 0; pass < 2; pass++) {
            CountDownLatch drained = new CountDownLatch(1);
            StoreTasks.execute(drained::countDown);
            assertTrue(drained.await(5, TimeUnit.SECONDS));
            shadowOf(Looper.getMainLooper()).idle();
        }
    }
}
