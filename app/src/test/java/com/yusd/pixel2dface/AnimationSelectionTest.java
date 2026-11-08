package com.yusd.pixel2dface;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35}, shadows = AtomicFileHostShadow.class)
public final class AnimationSelectionTest {
    private ActivityController<Activity> activity;
    private FrameLayout keyguard;
    private SystemUiFeedback feedback;
    private ConfigProvider provider;
    private Context context;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().visible();
        context = activity.get();
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 3);
        keyguard = root();
        feedback = new SystemUiFeedback(new Handler(Looper.getMainLooper()));
        ShadowBinder.setCallingUid(context.getApplicationInfo().uid);
        provider = Robolectric.buildContentProvider(ConfigProvider.class).create().get();
    }
    @After public void tearDown() {
        feedback.remove();
        activity.pause().stop().destroy();
    }
    private FrameLayout root() {
        FrameLayout view = new FrameLayout(context);
        activity.get().setContentView(view);
        view.layout(0, 0, 1080, 2280);
        return view;
    }
    private UnlockFaceAnimationView shown() {
        return ReflectionHelpers.getField(feedback, "animation");
    }
    private int style() { return ReflectionHelpers.getField(shown(), "visualStyle"); }

    @Test public void everySavedStyleIsTheFirstFrameOfTheNextWake() {
        assertTrue(TemplateStore.saveIdentityModel(context,
                new EmbeddingIdentity(EmbeddingIdentityTest.vector(0), .22f)));
        feedback.captureRoot(keyguard);
        for (int style = 0; style < AnimationStyle.COUNT; style++) {
            assertTrue(TemplateStore.trySetAnimationStyle(context, style));
            Bundle state = provider.call("prepare_unlock", "synthetic-animation-session-00000" + style, null);
            assertTrue(state.getBoolean("ok"));
            feedback.scanning(state.getInt("animation_style"));
            assertNotNull(shown().getParent());
            assertTrue(shown().isAttachedToWindow());
            // Native UiReviewInstrumentation checks frame advancement and ticker shutdown;
            // Robolectric's synthetic window remains GONE even with an attached root.
            assertEquals(style, style());
            // It lives above the native child/elevation ordering, not inside that ordering.
            assertEquals(0, keyguard.getChildCount());
            assertNotSame(keyguard, shown().getParent());
        }
    }
    @Test public void wakeBeforeRootInflationIsReplayedWhenTheRootArrives() {
        feedback.scanning(AnimationStyle.DOTS);
        assertNull(shown());
        feedback.captureRoot(keyguard);
        assertEquals(AnimationStyle.DOTS, style());
    }
    @Test public void sleepCancelsAnAnimationWaitingForTheRoot() {
        feedback.scanning(AnimationStyle.ORBIT);
        feedback.remove();
        feedback.captureRoot(keyguard);
        assertNull(shown());
    }
    @Test public void replacedRootKeepsTheActiveStyleAndReleasesTheOldView() {
        feedback.captureRoot(keyguard);
        feedback.scanning(AnimationStyle.GLOW);
        View previous = shown();
        feedback.captureRoot(root());
        assertEquals(AnimationStyle.GLOW, style());
        assertNull(previous.getParent());
        assertTrue(shown().isAttachedToWindow());
    }
    @Test public void oldOutcomeCleanupCannotEraseANewerWake() {
        feedback.captureRoot(keyguard);
        feedback.scanning(AnimationStyle.GLOW);
        feedback.failure();
        feedback.scanning(AnimationStyle.MINIMAL);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
        assertEquals(AnimationStyle.MINIMAL, style());
        assertEquals(1f, shown().getAlpha(), .01f);
    }
    @Test public void rotationRepositionsTheSameOverlay() {
        feedback.captureRoot(keyguard);
        feedback.scanning(AnimationStyle.ORBIT);
        View previous = shown();
        keyguard.layout(0, 0, 2280, 1080);
        assertSame(previous, shown());
        assertEquals(1140, (shown().getLeft() + shown().getRight()) / 2);
    }
    @Test public void runtimeVersionSurvivesStateReadsButNotABootOrLegacyHeartbeat() {
        Bundle runtime = new Bundle();
        runtime.putInt("module_version", BuildConfig.VERSION_CODE);
        assertTrue(provider.call("heartbeat", null, runtime).getBoolean("ok"));
        assertEquals(BuildConfig.VERSION_CODE, HookRuntimeStore.moduleVersion(context));
        provider.call("state", null, null);
        assertEquals(BuildConfig.VERSION_CODE, HookRuntimeStore.moduleVersion(context));
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 4);
        assertEquals(0, HookRuntimeStore.moduleVersion(context));
        provider.call("heartbeat", null, runtime);
        assertEquals(BuildConfig.VERSION_CODE, HookRuntimeStore.moduleVersion(context));
        provider.call("heartbeat", null, new Bundle());
        assertEquals(0, HookRuntimeStore.moduleVersion(context));
    }
    @Test public void malformedRuntimeVersionCannotOverwriteAValidReport() {
        Bundle runtime = new Bundle();
        runtime.putInt("module_version", BuildConfig.VERSION_CODE);
        provider.call("heartbeat", null, runtime);
        runtime.putString("module_version", "28");
        assertThrows(IllegalArgumentException.class, () -> provider.call("heartbeat", null, runtime));
        assertEquals(BuildConfig.VERSION_CODE, HookRuntimeStore.moduleVersion(context));
    }
}
