package com.yusd.pixel2dface;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35}, shadows = AtomicFileHostShadow.class)
public final class AnimationSelectionTest {
    @Test public void everySavedStyleReachesTheKeyguardRendererThroughTheProvider() {
        Context context = RuntimeEnvironment.getApplication();
        ShadowBinder.setCallingUid(context.getApplicationInfo().uid);
        ConfigProvider provider = Robolectric.buildContentProvider(ConfigProvider.class).create().get();
        FrameLayout keyguard = new FrameLayout(context);
        SystemUiFeedback feedback = new SystemUiFeedback(new Handler(Looper.getMainLooper()));
        feedback.captureRoot(keyguard);
        for (int style = 0; style < AnimationStyle.COUNT; style++) {
            assertTrue(TemplateStore.trySetAnimationStyle(context, style));
            Bundle state = provider.call("state", null, null);
            assertEquals(style, state.getInt("animation_style"));
            feedback.style(state.getInt("animation_style"));
            feedback.scanning();
            assertEquals(1, keyguard.getChildCount());
            int rendered = ReflectionHelpers.getField(keyguard.getChildAt(0), "visualStyle");
            assertEquals(style, rendered);
        }
        feedback.remove();
        assertEquals(0, keyguard.getChildCount());
    }
}
