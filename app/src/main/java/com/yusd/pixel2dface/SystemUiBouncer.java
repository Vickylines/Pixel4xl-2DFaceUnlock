package com.yusd.pixel2dface;

import java.lang.ref.WeakReference;
import de.robv.android.xposed.XposedHelpers;

/** Native credential-screen adapter, called only after the coordinator validates authentication. */
final class SystemUiBouncer {
    private volatile WeakReference<Object> manager = new WeakReference<>(null);

    void capture(Object value) { manager = new WeakReference<>(value); }
    Object current() { return manager.get(); }

    boolean isShowing() {
        Object current = manager.get();
        if (current == null) return false;
        try {
            // Fully shown means Android committed the swipe. A partly dragged or cancelled
            // gesture must not turn a passive face match into automatic dismissal.
            return Boolean.TRUE.equals(XposedHelpers.callMethod(current, "isBouncerShowing"));
        } catch (Throwable error) {
            SystemUiLog.error("Credential-bouncer state unavailable; retaining swipe", error);
            return false;
        }
    }

    boolean completeWeakAuthentication(Object expected) {
        if (current() != expected || !isShowing()) return false;
        try {
            // The false flag preserves native non-credential/weak authentication handling.
            // Android's bouncer still checks its current security mode and dismiss policy.
            XposedHelpers.callMethod(expected, "notifyKeyguardAuthenticated", false);
            return true;
        } catch (Throwable error) {
            SystemUiLog.error("Native bouncer could not accept weak authentication", error);
            return false;
        }
    }
}
