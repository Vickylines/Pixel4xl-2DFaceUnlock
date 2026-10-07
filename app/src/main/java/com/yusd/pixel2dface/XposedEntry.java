package com.yusd.pixel2dface;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.ViewGroup;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** LSPosed entry: discover capabilities and forward lifecycle events to the coordinator. */
public final class XposedEntry implements IXposedHookLoadPackage {
    private static SystemUiSessionCoordinator coordinator;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loaded) {
        if (!Constants.SYSTEM_UI_PACKAGE.equals(loaded.packageName)
                || !Constants.SYSTEM_UI_PACKAGE.equals(loaded.processName)) {
            return;
        }
        if (!SystemUiCompat.isPixelDevice() || !SystemUiCompat.isSupportedAndroidVersion()) {
            SystemUiLog.info("Unsupported platform; hooks inactive: " + SystemUiCompat.platformLabel());
            return;
        }
        Looper mainLooper = Looper.getMainLooper();
        if (mainLooper == null) {
            SystemUiLog.info("SystemUI main looper unavailable; hooks inactive");
            return;
        }
        synchronized (XposedEntry.class) {
            if (coordinator != null) {
                return;
            }
            coordinator = new SystemUiSessionCoordinator(new Handler(mainLooper));
        }
        SystemUiSessionCoordinator sessions = coordinator;
        try {
            Class<?> monitor = SystemUiCompat.findFirstClass(loaded.classLoader,
                    "com.android.keyguard.KeyguardUpdateMonitor");
            if (monitor == null) {
                SystemUiLog.info("KeyguardUpdateMonitor unavailable; hooks inactive");
                return;
            }
            XposedBridge.hookAllConstructors(monitor, after(param -> {
                Context context = SystemUiCompat.resolveContext(param.thisObject, param.args);
                if (context != null) {
                    sessions.attach(param.thisObject, context);
                }
            }));
            sessions.wakeMethodInstalled = hook(monitor, "handleStartedWakingUp",
                    after(param -> sessions.wake(param.thisObject, 80L)));
            hook(monitor, "setKeyguardShowing", before(param -> {
                if (firstBooleanIsFalse(param)) {
                    sessions.hidden();
                }
            }));
            hook(monitor, "handleStartedGoingToSleep", before(param ->
                    sessions.invalidate(param.thisObject, "started going to sleep")));
            XC_MethodHook cacheCleared = after(param ->
                    sessions.restorePassiveAuthentication(param.thisObject));
            hook(monitor, "clearFingerprintRecognized", cacheCleared);
            hook(monitor, "clearBiometricRecognized", cacheCleared);
            // Revoke our pending state when native policy/user callbacks invalidate biometrics.
            hook(monitor, "notifyStrongAuthStateChanged", before(param ->
                    sessions.invalidate(param.thisObject, "strong authentication state changed")));
            hook(monitor, "handleDevicePolicyManagerStateChanged", before(param ->
                    sessions.invalidate(param.thisObject, "device policy changed")));
            hook(monitor, "handleUserSwitching", before(param ->
                    sessions.invalidate(param.thisObject, "selected user changed")));
            optional("inline wake", () -> inlineCoralWake(loaded.classLoader, sessions));
            optional("bouncer", () -> bouncer(loaded.classLoader, sessions));
            optional("entry icon", () -> entryIcon(loaded.classLoader, sessions));
            optional("keyguard root", () -> overlay(loaded.classLoader, sessions));
            optional("camera-host animation", () -> occlusion(loaded.classLoader, sessions));
            optional("hidden-keyguard cleanup", () -> visibility(loaded.classLoader, sessions));
            SystemUiLog.info("SystemUI lifecycle hooks installed: " + SystemUiCompat.platformLabel());
        } catch (Throwable error) {
            SystemUiLog.error("Unable to install SystemUI lifecycle hooks", error);
        }
    }

    private static void bouncer(ClassLoader loader, SystemUiSessionCoordinator sessions) {
        Class<?> type = SystemUiCompat.findFirstClass(loader,
                "com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager");
        if (type != null) {
            XposedBridge.hookAllConstructors(type, after(param ->
                    sessions.feedback.captureBouncer(param.thisObject)));
        }
    }

    private static void entryIcon(ClassLoader loader, SystemUiSessionCoordinator sessions) {
        Class<?> type = SystemUiCompat.findFirstClass(loader,
                "com.android.systemui.keyguard.ui.view.DeviceEntryIconView");
        if (type != null) {
            XposedBridge.hookAllConstructors(type, after(param ->
                    sessions.feedback.captureEntryIcon(param.thisObject)));
        }
    }

    private static void overlay(ClassLoader loader, SystemUiSessionCoordinator sessions) {
        Class<?> type = SystemUiCompat.findFirstClass(loader,
                "com.android.systemui.shade.NotificationShadeWindowView",
                "com.android.systemui.statusbar.phone.NotificationShadeWindowView",
                "com.android.systemui.statusbar.phone.StatusBarWindowView");
        if (type != null) {
            sessions.overlayInstalled = !XposedBridge.hookAllConstructors(type, after(param -> {
                if (param.thisObject instanceof ViewGroup) {
                    sessions.feedback.captureRoot((ViewGroup) param.thisObject);
                    sessions.heartbeat();
                }
            })).isEmpty();
        }
    }

    private static void visibility(ClassLoader loader, SystemUiSessionCoordinator sessions) {
        XC_MethodHook hidden = before(param -> {
            if (firstBooleanIsFalse(param)) {
                sessions.hidden();
            }
        });
        hook(SystemUiCompat.findFirstClass(loader,
                "com.android.systemui.keyguard.KeyguardViewMediator"), "setShowingLocked", hidden);
        hook(SystemUiCompat.findFirstClass(loader,
                "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl"),
                "notifyKeyguardState", hidden);
    }

    private static void occlusion(ClassLoader loader, SystemUiSessionCoordinator sessions) {
        Class<?> mediator = SystemUiCompat.findFirstClass(loader,
                "com.android.systemui.keyguard.KeyguardViewMediator");
        sessions.occlusionInstalled = hook(mediator, "setOccluded", before(param -> {
            if (param.args.length > 0 && Boolean.TRUE.equals(param.args[0])
                    && sessions.shouldKeepKeyguardVisible()) {
                // Prove the foreground task is our exact host on every call. A phone or
                // alarm becoming the top activity retains its normal native occlusion.
                param.setResult(null);
                sessions.cameraHostOcclusionSuppressed();
            }
        }));
        Class<?> type = SystemUiCompat.findFirstClass(loader,
                "com.android.systemui.keyguard.KeyguardViewMediator$OccludeActivityLaunchRemoteAnimationRunner");
        boolean animationHook = hook(type, "onAnimationStart", before(param -> {
            if (!sessions.isCameraHostActive()
                    || !SystemUiCompat.isCameraHostAnimation(param.args)) {
                return;
            }
            Object finished = SystemUiCompat.findAnimationFinishedCallback(param.args);
            if (finished != null) {
                XposedHelpers.callMethod(finished, "onAnimationFinished");
                param.setResult(null);
                sessions.cameraHostOcclusionSuppressed();
            }
        }));
        sessions.occlusionInstalled |= animationHook;
    }

    private static void inlineCoralWake(ClassLoader loader, SystemUiSessionCoordinator sessions) {
        if (Build.VERSION.SDK_INT != 36 || !"coral".equalsIgnoreCase(Build.DEVICE)) {
            return;
        }
        Class<?> handler = SystemUiCompat.findFirstClass(loader,
                "com.android.keyguard.KeyguardUpdateMonitor$13");
        if (handler == null) {
            return;
        }
        // This adapter is intentionally limited to the previously inspected coral API 36
        // implementation. Other versions use capability hooks and protected screen broadcasts.
        XposedHelpers.findAndHookMethod(handler, "handleMessage", Message.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Message message = (Message) param.args[0];
                    if (message != null && (message.what == 320 || message.what == 321
                            || message.what == 323)) {
                        sessions.invalidate(XposedHelpers.getSurroundingThis(param.thisObject),
                                "coral inline sleep");
                    }
                } catch (Throwable error) {
                    SystemUiLog.error("Coral inline sleep adapter failed", error);
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Message message = (Message) param.args[0];
                    if (message != null && message.what == 319) {
                        sessions.wake(XposedHelpers.getSurroundingThis(param.thisObject), 80L);
                    }
                } catch (Throwable error) {
                    SystemUiLog.error("Coral inline wake adapter failed", error);
                }
            }
        });
    }

    private static boolean firstBooleanIsFalse(XC_MethodHook.MethodHookParam param) {
        return param.args.length > 0 && Boolean.FALSE.equals(param.args[0]);
    }

    private static boolean hook(Class<?> type, String name, XC_MethodHook callback) {
        if (type == null || !SystemUiCompat.hasMethod(type, name)) {
            return false;
        }
        try {
            return !XposedBridge.hookAllMethods(type, name, callback).isEmpty();
        } catch (Throwable error) {
            SystemUiLog.error("Optional hook unavailable: " + name, error);
            return false;
        }
    }

    private interface HookAction {
        void run(XC_MethodHook.MethodHookParam param) throws Throwable;
    }

    private static XC_MethodHook before(HookAction action) {
        return new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                safe(action, param);
            }
        };
    }

    private static XC_MethodHook after(HookAction action) {
        return new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                safe(action, param);
            }
        };
    }

    private static void safe(HookAction action, XC_MethodHook.MethodHookParam param) {
        try {
            action.run(param);
        } catch (Throwable error) {
            // An adapter failure must not escape into SystemUI's native lifecycle method.
            SystemUiLog.error("SystemUI hook callback failed", error);
        }
    }

    private interface OptionalAdapter {
        void install() throws Throwable;
    }

    private static void optional(String label, OptionalAdapter adapter) {
        try {
            adapter.install();
        } catch (Throwable error) {
            SystemUiLog.error("Optional " + label + " adapter unavailable", error);
        }
    }
}
