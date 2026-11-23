package com.yusd.pixel2dface;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

import android.app.Activity;
import android.app.Application;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.FrameLayout;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.util.ReflectionHelpers;

/** Follows a rejected camera result through the native credential-page callback to a fresh camera launch. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, instrumentedPackages = "com.yusd.pixel2dface", shadows = {
        AtomicFileHostShadow.class, InitialAuthenticationDeliveryTest.NativeCache.class,
        InitialAuthenticationDeliveryTest.HookLog.class, SwipeRecognitionRetryTest.Keypad.class})
public final class SwipeRecognitionRetryTest {
    private static final String FIRST = "synthetic-rejected-camera-session-00001";
    private final Object monitor = new Object();
    private final Handler main = new Handler(Looper.getMainLooper());
    private ActivityController<Activity> activity;
    private Application app;
    private SystemUiSessionCoordinator coordinator;
    private UnlockSessionState session;
    private ExecutorService ipc;

    @Implements(value = SystemUiBouncer.class, isInAndroidSdk = false)
    public static final class Keypad {
        static boolean showing;
        @Implementation protected boolean isShowing() { return showing; }
    }

    @Before public void startWithOneRejectedScan() {
        Keypad.showing = false;
        app = RuntimeEnvironment.getApplication();
        activity = Robolectric.buildActivity(Activity.class).setup().visible();
        shadowOf(app.getSystemService(PowerManager.class)).setIsInteractive(true);
        shadowOf(app.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        shadowOf(app.getSystemService(KeyguardManager.class)).setIsDeviceLocked(true);
        Settings.Global.putInt(app.getContentResolver(), Settings.Global.BOOT_COUNT, 3);
        ShadowBinder.setCallingUid(app.getApplicationInfo().uid);
        ConfigProvider config = Robolectric.buildContentProvider(ConfigProvider.class).create().get();
        ShadowContentResolver.registerProviderInternal(Uri.parse(Constants.PROVIDER_URI).getAuthority(), config);
        assertTrue(TemplateStore.saveIdentityModel(app,
                new EmbeddingIdentity(EmbeddingIdentityTest.vector(0), .22f)));

        coordinator = new SystemUiSessionCoordinator(main);
        ReflectionHelpers.setField(coordinator, "context", new WeakReference<Context>(app));
        ReflectionHelpers.setField(coordinator, "monitor", new WeakReference<>(monitor));
        ReflectionHelpers.setField(coordinator, "resultReceiver", new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { }
        });
        FrameLayout root = new FrameLayout(activity.get());
        activity.get().setContentView(root);
        root.layout(0, 0, 1080, 2200);
        coordinator.feedback.captureRoot(root);
        coordinator.feedback.scanning(AnimationStyle.FACE);
        ipc = ReflectionHelpers.getField(coordinator, "ipc");
        session = ReflectionHelpers.getField(coordinator, "session");
        long now = SystemClock.elapsedRealtime();
        long generation = session.wake(now);
        assertTrue(session.beginPreparation(generation, now));
        assertTrue(session.start(FIRST, now, generation, 0));
        session.configRevision = TemplateStore.readState(app).revision;
        assertTrue(TemplateStore.tryRecordFailure(app));
        Intent rejection = new Intent(Constants.ACTION_UNLOCK_RESULT)
                .putExtra(Constants.EXTRA_SESSION_TOKEN, FIRST)
                .putExtra(Constants.EXTRA_SUCCESS, false)
                .putExtra(Constants.EXTRA_CAN_RETRY, true);
        ReflectionHelpers.callInstanceMethod(coordinator, "handleResult", from(Intent.class, rejection));
        assertTrue(coordinator.feedback.hasSwipeHint());
        assertFalse(coordinator.isCameraHostActive());
    }

    @After public void close() {
        main.removeCallbacksAndMessages(null);
        coordinator.feedback.remove();
        ipc.shutdownNow();
        activity.pause().stop().destroy();
    }

    private void enterKeypad() {
        Keypad.showing = true;
        coordinator.bouncerStateChanged();
        assertTrue(coordinator.feedback.waitingForCapture());
    }

    private void finishPreparation() throws Exception {
        long wait = session.launchDelay(SystemClock.elapsedRealtime());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(wait + 1));
        ipc.submit(() -> { }).get(5, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void committedSwipeIssuesOneFreshCameraGrantAfterCooldown() throws Exception {
        enterKeypad();
        coordinator.bouncerStateChanged();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        assertNull(shadowOf(app).getNextStartedActivity());
        assertTrue(session.launchDelay(SystemClock.elapsedRealtime()) > 0);
        finishPreparation();
        Intent launch = shadowOf(app).getNextStartedActivity();
        assertNotNull("A swipe must reach the regular camera host", launch);
        assertEquals(new ComponentName(Constants.PACKAGE_NAME, Constants.UNLOCK_HOST_ACTIVITY), launch.getComponent());
        String fresh = launch.getStringExtra(Constants.EXTRA_SESSION_TOKEN);
        assertNotNull(fresh);
        assertNotEquals(FIRST, fresh);
        assertEquals(session.token, fresh);
        assertTrue(TemplateStore.consumeAuthorizedUnlockSession(app, fresh));
        assertFalse(TemplateStore.consumeAuthorizedUnlockSession(app, fresh));
        assertTrue(coordinator.isCameraHostActive());
        assertFalse(session.hasPassiveAuthentication(SystemClock.elapsedRealtime()));
        assertNull(shadowOf(app).getNextStartedActivity());
    }

    @Test public void lockoutDuringCooldownPreventsTheNewCameraGrant() throws Exception {
        enterKeypad();
        for (int i = 1; i < StorePolicy.FAILURE_LIMIT; i++) assertTrue(TemplateStore.tryRecordFailure(app));
        assertTrue("Fifth rejection must persist a lockout deadline", TemplateStore.readState(app).lockoutUntil > 0);
        finishPreparation();
        assertNull(shadowOf(app).getNextStartedActivity());
        assertFalse(coordinator.isCameraHostActive());
        assertFalse(coordinator.feedback.waitingForCapture());
    }

    @Test public void leavingTheKeypadCancelsThePendingBottomSwipe() throws Exception {
        enterKeypad();
        Keypad.showing = false;
        coordinator.bouncerStateChanged();
        assertFalse(coordinator.feedback.waitingForCapture());
        Keypad.showing = true;
        coordinator.bouncerStateChanged();
        finishPreparation();
        assertNull(shadowOf(app).getNextStartedActivity());
        assertFalse(coordinator.feedback.waitingForCapture());
        assertFalse(session.hasPassiveAuthentication(SystemClock.elapsedRealtime()));
    }
}
