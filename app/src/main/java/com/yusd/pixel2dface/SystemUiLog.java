package com.yusd.pixel2dface;

import de.robv.android.xposed.XposedBridge;

/** Tokens and biometric data must never be logged by SystemUI. */
final class SystemUiLog {
    private SystemUiLog() { }

    static void info(String message) {
        XposedBridge.log("Pixel2DFace: " + message);
    }

    static void error(String message, Throwable error) {
        XposedBridge.log("Pixel2DFace: " + message + "\n"
                + android.util.Log.getStackTraceString(error));
    }
}
