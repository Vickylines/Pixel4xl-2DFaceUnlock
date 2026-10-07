package com.yusd.pixel2dface;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import androidx.core.content.ContextCompat;

/** Transparent camera host protocol. Only attach after consuming the private session grant. */
final class UnlockCameraHost {
    private final Activity activity;
    private final String token;
    private final Runnable dismiss;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BroadcastReceiver receiver;
    private BroadcastReceiver cancelReceiver;
    private boolean closed;
    private int readyAttempts;
    private final Runnable exitWatcher = new Runnable() {
        @Override public void run() {
            if (closed || activity.isFinishing() || activity.isDestroyed()) return;
            KeyguardManager keyguard = activity.getSystemService(KeyguardManager.class);
            // Weak authentication can make isDeviceLocked=false while the lock screen
            // still awaits a swipe. Keep the host until the keyguard itself also exits.
            if (keyguard == null || (!keyguard.isDeviceLocked() && !keyguard.isKeyguardLocked())) dismiss.run();
            else main.postDelayed(this, 80);
        }
    };

    UnlockCameraHost(Activity activity, String token, Runnable dismiss) {
        this.activity = activity; this.token = token; this.dismiss = dismiss;
    }

    void attach() {
        activity.setShowWhenLocked(true);
        activity.setTurnScreenOn(true);
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        activity.getWindow().setStatusBarColor(Color.TRANSPARENT);
        activity.getWindow().setNavigationBarColor(Color.TRANSPARENT);
        FrameLayout content = new FrameLayout(activity);
        content.setBackgroundColor(Color.TRANSPARENT);
        activity.setContentView(content);
        WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
        attributes.width = 1; attributes.height = 1; attributes.gravity = Gravity.TOP | Gravity.START;
        attributes.x = 0; attributes.y = 0; attributes.dimAmount = 0; attributes.alpha = 0;
        activity.getWindow().setAttributes(attributes);
        activity.getWindow().setLayout(1, 1);
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { dismiss.run(); }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, filter,
                Context.RECEIVER_NOT_EXPORTED);
        else activity.registerReceiver(receiver, filter);
        cancelReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (intent != null && Constants.ACTION_CANCEL_CAPTURE.equals(intent.getAction())
                        && token != null && token.equals(intent.getStringExtra(Constants.EXTRA_SESSION_TOKEN))) {
                    android.util.Log.i("Pixel2DFace", "Capture host cancellation received");
                    dismiss.run();
                }
            }
        };
        // A platform status-bar permission restricts senders; the token fences old sessions.
        IntentFilter cancelFilter = new IntentFilter(Constants.ACTION_CANCEL_CAPTURE);
        ContextCompat.registerReceiver(activity, cancelReceiver, cancelFilter,
                "android.permission.STATUS_BAR_SERVICE", main, ContextCompat.RECEIVER_EXPORTED);
        activity.getWindow().getDecorView().post(this::notifyReady);
    }

    private void notifyReady() {
        if (closed || activity.isFinishing() || activity.isDestroyed()) return;
        IBinder binder = activity.getWindow().getAttributes().token;
        if (binder == null) {
            if (readyAttempts++ < 12) main.postDelayed(this::notifyReady, 16);
            return;
        }
        Bundle extras = new Bundle();
        extras.putString(Constants.EXTRA_SESSION_TOKEN, token);
        extras.putBinder(Constants.EXTRA_ACTIVITY_TOKEN, binder);
        activity.sendBroadcast(new Intent(Constants.ACTION_CAMERA_HOST_READY)
                .setPackage(Constants.SYSTEM_UI_PACKAGE).putExtras(extras));
    }

    void awaitKeyguardDismissal() {
        if (closed) return;
        // Once camera work ends, this Activity must stop occluding the native lock screen.
        // Input pass-through alone does not restore SystemUI's swipe-to-dismiss state.
        releaseLockScreen();
        main.post(exitWatcher);
        main.postDelayed(() -> { if (!closed) dismiss.run(); }, 60_000);
    }

    void close() {
        if (closed) return;
        closed = true;
        releaseLockScreen();
        main.removeCallbacksAndMessages(null);
        if (receiver != null) {
            try { activity.unregisterReceiver(receiver); }
            catch (IllegalArgumentException ignored) { }
            receiver = null;
        }
        if (cancelReceiver != null) {
            try { activity.unregisterReceiver(cancelReceiver); }
            catch (IllegalArgumentException ignored) { }
            cancelReceiver = null;
        }
        activity.sendBroadcast(new Intent(Constants.ACTION_CAMERA_HOST_FINISHED)
                .setPackage(Constants.SYSTEM_UI_PACKAGE)
                .putExtra(Constants.EXTRA_SESSION_TOKEN, token));
    }

    void releaseLockScreen() {
        activity.setShowWhenLocked(false);
        activity.setTurnScreenOn(false);
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
    }
}
