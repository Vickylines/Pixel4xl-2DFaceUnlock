package com.yusd.pixel2dface;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.widget.Toast;

/** A fresh system challenge authorizes one action, never an Intent extra or saved boolean. */
final class DeviceCredentialConfirmation {
    private static final int REQUEST_CREDENTIAL = 201;
    private final Activity activity;
    private Runnable confirmed;
    private Runnable rejected;

    DeviceCredentialConfirmation(Activity activity) {
        this.activity = activity;
    }

    @SuppressWarnings("deprecation")
    void request(String title, Runnable onConfirmed, Runnable onRejected) {
        if (isPending()) return;
        // This system-owned flow also supports API 29, where DEVICE_CREDENTIAL-only
        // BiometricPrompt is unavailable. The platform pins the Settings package.
        Intent intent = activity.getSystemService(KeyguardManager.class)
                .createConfirmDeviceCredentialIntent(title, null);
        if (intent == null) {
            Toast.makeText(activity, "请先设置 PIN、图案或密码", Toast.LENGTH_LONG).show();
            onRejected.run();
            return;
        }
        confirmed = onConfirmed;
        rejected = onRejected;
        try {
            activity.startActivityForResult(intent, REQUEST_CREDENTIAL);
        } catch (ActivityNotFoundException unavailable) {
            cancel();
            Toast.makeText(activity, "无法验证身份", Toast.LENGTH_LONG).show();
            onRejected.run();
        }
    }

    boolean onActivityResult(int requestCode, int resultCode) {
        if (requestCode != REQUEST_CREDENTIAL) return false;
        Runnable action = confirmed;
        Runnable denial = rejected;
        cancel();
        // Recreated Activities deliberately have no pending action to authorize.
        if (action == null) return true;
        KeyguardManager keyguard = activity.getSystemService(KeyguardManager.class);
        if (resultCode == Activity.RESULT_OK && !activity.isFinishing() && !activity.isDestroyed()
                && keyguard.isDeviceSecure() && !keyguard.isDeviceLocked()) {
            action.run();
        } else {
            denial.run();
        }
        return true;
    }

    boolean isPending() { return confirmed != null; }

    void cancel() {
        confirmed = null;
        rejected = null;
    }
}
