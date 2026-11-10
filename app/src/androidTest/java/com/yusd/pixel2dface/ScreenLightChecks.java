package com.yusd.pixel2dface;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;

/** Native window/overlay verification in the disposable validator, without face data. */
final class ScreenLightChecks {
    static void run(Instrumentation instrumentation, Bundle result) throws Exception {
        Activity activity = instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        SystemUiScreenLight light = new SystemUiScreenLight(new Handler(Looper.getMainLooper()));
        FrameLayout root = new FrameLayout(activity);
        try {
            instrumentation.runOnMainSync(() -> {
                activity.setShowWhenLocked(true);
                root.setBackgroundColor(Color.rgb(18, 24, 33));
                TextView text = new TextView(activity);
                text.setText("21:15"); text.setTextSize(64); text.setTextColor(Color.WHITE);
                text.setGravity(Gravity.CENTER); root.addView(text, new FrameLayout.LayoutParams(-1, -1));
                activity.setContentView(root);
                WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
                attributes.screenBrightness = .09f;
                activity.getWindow().setAttributes(attributes);
            });
            instrumentation.waitForIdleSync();
            SystemClock.sleep(300);
            View window = root.getRootView();
            instrumentation.runOnMainSync(() -> {
                light.show(root, SystemClock.elapsedRealtime() + 1500);
                require(((WindowManager.LayoutParams) window.getLayoutParams()).screenBrightness >= .24f,
                        "temporary brightness floor applied");
                require(!root.hasFocusable(), "light cannot take focus");
            });
            SystemClock.sleep(350);
            Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
            File file = new File(instrumentation.getTargetContext().getExternalFilesDir("light-review"), "screen-light.png");
            try (FileOutputStream stream = new FileOutputStream(file)) {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream);
            } finally { screenshot.recycle(); }
            result.putString("screenshot", file.getAbsolutePath());
            instrumentation.runOnMainSync(light::remove);
            require(((WindowManager.LayoutParams) window.getLayoutParams()).screenBrightness == .09f,
                    "original window brightness restored");
            instrumentation.runOnMainSync(() -> light.show(root, SystemClock.elapsedRealtime() + 250));
            SystemClock.sleep(400);
            instrumentation.waitForIdleSync();
            require(((WindowManager.LayoutParams) window.getLayoutParams()).screenBrightness == .09f,
                    "deadline restores brightness without a capture result");
            instrumentation.runOnMainSync(() -> {
                light.show(root, SystemClock.elapsedRealtime() + 1000);
                WindowManager.LayoutParams attributes = (WindowManager.LayoutParams) window.getLayoutParams();
                attributes.screenBrightness = .17f;
                activity.getSystemService(WindowManager.class).updateViewLayout(window, attributes);
                light.remove();
                require(attributes.screenBrightness == .17f, "preserve a newer system brightness change");
            });
            result.putInt("screenLightChecks", 5);
        } finally {
            instrumentation.runOnMainSync(() -> { light.remove(); activity.finish(); });
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
