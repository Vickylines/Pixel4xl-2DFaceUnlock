package com.yusd.pixel2dface;

import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.UserManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class XposedEntry implements IXposedHookLoadPackage {
    private static final String TAG = "Pixel2DFace";
    private static final Object LOCK = new Object();
    private static final long DUPLICATE_WAKE_WINDOW_MS = 500L;
    // Let the native lockscreen draw before the transparent camera host starts. This avoids
    // revealing the previously visible task on AOSP-derived SystemUI transitions.
    private static final long LOCKSCREEN_SETTLE_MS = 620L;
    private static final ExecutorService CONFIG_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Pixel2DFace-config");
                thread.setDaemon(true);
                return thread;
            });
    private static volatile Handler mainHandler;

    private static WeakReference<Object> monitorReference = new WeakReference<>(null);
    private static WeakReference<Object> keyguardViewManagerReference =
            new WeakReference<>(null);
    private static WeakReference<Object> deviceEntryIconViewReference =
            new WeakReference<>(null);
    private static WeakReference<ViewGroup> keyguardRootReference = new WeakReference<>(null);
    private static WeakReference<Context> systemUiContextReference =
            new WeakReference<>(null);
    private static BroadcastReceiver resultReceiver;
    private static BroadcastReceiver powerStateReceiver;
    private static UnlockFaceAnimationView keyguardAnimationView;
    private static String activeToken;
    private static long activeTokenCreatedAt;
    private static long lastLaunchAt;
    private static long passiveAuthenticatedAt;
    private static long lastWakeTriggerAt;
    private static long wakeGeneration;
    private static boolean configCheckInFlight;
    private static boolean cameraHostActive;
    private static boolean wakeMethodHookInstalled;
    private static boolean overlayHookInstalled;
    private static boolean occlusionGuardInstalled;
    private static int selectedAnimationStyle = UnlockFaceAnimationView.STYLE_FACE_ID;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (!Constants.SYSTEM_UI_PACKAGE.equals(loadPackageParam.packageName)
                || !Constants.SYSTEM_UI_PACKAGE.equals(loadPackageParam.processName)) {
            return;
        }
        if (!SystemUiCompat.isPixelDevice()) {
            log("Unsupported non-Pixel device; SystemUI hooks were not installed: "
                    + SystemUiCompat.platformLabel());
            return;
        }
        if (!SystemUiCompat.isSupportedAndroidVersion()) {
            log("Unsupported Android version; SystemUI hooks were not installed: "
                    + SystemUiCompat.platformLabel());
            return;
        }
        try {
            Class<?> monitorClass = SystemUiCompat.findFirstClass(
                    loadPackageParam.classLoader,
                    "com.android.keyguard.KeyguardUpdateMonitor");
            if (monitorClass == null) {
                log("Unsupported SystemUI: KeyguardUpdateMonitor was not found");
                return;
            }
            installOptionalHooks(loadPackageParam.classLoader);
            XposedBridge.hookAllConstructors(monitorClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object monitor = param.thisObject;
                        Context context = SystemUiCompat.resolveContext(monitor, param.args);
                        if (context == null) {
                            log("SystemUI monitor context was not found; adapter stays inactive");
                            return;
                        }
                        Context processContext = context.getApplicationContext() != null
                                ? context.getApplicationContext() : context;
                        synchronized (LOCK) {
                            monitorReference = new WeakReference<>(monitor);
                            systemUiContextReference = new WeakReference<>(processContext);
                        }
                        ensureResultReceiver(processContext);
                        ensurePowerStateReceiver(processContext);
                        // Mark the module connected as soon as SystemUI loads it. Broadcast wake
                        // detection remains available when a ROM inlines the AOSP wake callback.
                        publishHookHeartbeat(processContext);
                    } catch (Throwable error) {
                        log("Unable to initialize SystemUI compatibility adapter", error);
                    }
                }
            });

            wakeMethodHookInstalled = hookAllMethodsIfPresent(
                    monitorClass, "handleStartedWakingUp",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            scheduleLaunch(param.thisObject, 80L);
                        }
                    });

            installCoralAndroid16InlineWakeHook(loadPackageParam.classLoader);

            hookAllMethodsIfPresent(monitorClass, "setKeyguardShowing",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length > 0
                                    && Boolean.FALSE.equals(param.args[0])) {
                                // Remove the SystemUI child synchronously before the keyguard
                                // surface starts revealing the launcher.
                                removeKeyguardAnimationNow();
                                log("Removed keyguard animation before unlock transition");
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args.length > 0
                                    && Boolean.FALSE.equals(param.args[0])) {
                                resetPassiveState();
                            }
                        }
                    });

            hookAllMethodsIfPresent(monitorClass, "handleStartedGoingToSleep",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            invalidateAuthenticationForSleep(param.thisObject, -1);
                        }
                    });

            XC_MethodHook restoreAfterClearHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    restorePassiveAuthenticationIfPending(param.thisObject);
                }
            };
            hookAllMethodsIfPresent(monitorClass, "clearFingerprintRecognized",
                    restoreAfterClearHook);
            hookAllMethodsIfPresent(monitorClass, "clearBiometricRecognized",
                    restoreAfterClearHook);

            log("SystemUI compatibility hook installed: " + SystemUiCompat.platformLabel());
        } catch (Throwable error) {
            log("Unable to install SystemUI hook", error);
        }
    }

    private static void installOptionalHooks(ClassLoader classLoader) {
        try {
            installBouncerStateHook(classLoader);
        } catch (Throwable error) {
            log("Credential-bouncer adapter unavailable", error);
        }
        try {
            installDeviceEntryIconHook(classLoader);
        } catch (Throwable error) {
            log("Native lock-icon adapter unavailable", error);
        }
        try {
            installKeyguardOverlayHook(classLoader);
        } catch (Throwable error) {
            log("Native animation-root adapter unavailable", error);
        }
        try {
            installKeyguardOcclusionGuard(classLoader);
        } catch (Throwable error) {
            log("Camera-host occlusion adapter unavailable", error);
        }
        try {
            installKeyguardVisibilityFallbackHooks(classLoader);
        } catch (Throwable error) {
            log("Keyguard-visibility fallback unavailable", error);
        }
    }

    private static boolean hookAllMethodsIfPresent(Class<?> targetClass, String methodName,
            XC_MethodHook hook) {
        if (targetClass == null || !SystemUiCompat.hasMethod(targetClass, methodName)) {
            log("Optional method not found: " + methodName);
            return false;
        }
        try {
            XposedBridge.hookAllMethods(targetClass, methodName, hook);
            return true;
        } catch (Throwable error) {
            log("Unable to hook optional method: " + methodName, error);
            return false;
        }
    }

    private static void installCoralAndroid16InlineWakeHook(ClassLoader classLoader) {
        if (Build.VERSION.SDK_INT != 36 || !"coral".equalsIgnoreCase(Build.DEVICE)) {
            return;
        }
        Class<?> wakeHandlerClass = XposedHelpers.findClassIfExists(
                "com.android.keyguard.KeyguardUpdateMonitor$13", classLoader);
        if (wakeHandlerClass == null) {
            log("Coral Android 16 inline wake handler was not found");
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(wakeHandlerClass, "handleMessage",
                    Message.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Message message = (Message) param.args[0];
                            if (message != null && (message.what == 320
                                    || message.what == 321 || message.what == 323)) {
                                Object monitor = XposedHelpers.getSurroundingThis(
                                        param.thisObject);
                                invalidateAuthenticationForSleep(monitor, message.what);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Message message = (Message) param.args[0];
                            if (message != null && message.what == 319) {
                                Object monitor = XposedHelpers.getSurroundingThis(
                                        param.thisObject);
                                scheduleLaunch(monitor, 80L);
                            }
                        }
                    });
            log("Installed coral Android 16 inline wake adapter");
        } catch (Throwable error) {
            log("Unable to install coral Android 16 inline wake adapter", error);
        }
    }

    private static void scheduleLaunch(Object monitor, long delayMs) {
        long now = android.os.SystemClock.elapsedRealtime();
        long generation;
        synchronized (LOCK) {
            if (now - lastWakeTriggerAt < DUPLICATE_WAKE_WINDOW_MS) {
                return;
            }
            lastWakeTriggerAt = now;
            passiveAuthenticatedAt = 0L;
            activeToken = null;
            configCheckInFlight = false;
            generation = ++wakeGeneration;
        }
        removeKeyguardAnimation();
        clearStoredWeakAuthentication(monitor);
        log("Wake trigger accepted");
        Handler handler = getMainHandler();
        if (handler != null) {
            handler.postDelayed(() -> maybeLaunch(monitor, generation), delayMs);
        } else {
            log("Skipped trigger: main looper is not ready");
        }
    }

    private static void publishHookHeartbeat(Context context) {
        UserManager userManager = context.getSystemService(UserManager.class);
        if (userManager != null && !userManager.isUserUnlocked()) {
            log("Deferred module heartbeat until credential storage is unlocked");
            return;
        }
        try {
            Bundle diagnostics = new Bundle();
            diagnostics.putString("platform", SystemUiCompat.platformLabel());
            diagnostics.putBoolean("wake_method", wakeMethodHookInstalled);
            diagnostics.putBoolean("power_broadcast", powerStateReceiver != null);
            diagnostics.putBoolean("native_overlay", overlayHookInstalled);
            diagnostics.putBoolean("occlusion_guard", occlusionGuardInstalled);
            diagnostics.putString("authentication_adapter",
                    SystemUiCompat.getAuthenticationAdapterLabel());
            context.getContentResolver().call(
                    Uri.parse(Constants.PROVIDER_URI), "heartbeat", null, diagnostics);
        } catch (Throwable error) {
            log("Unable to publish module heartbeat", error);
        }
    }

    private static void publishCurrentHeartbeat() {
        Context context;
        synchronized (LOCK) {
            context = systemUiContextReference.get();
        }
        if (context != null) {
            publishHookHeartbeat(context);
        }
    }

    private static void ensurePowerStateReceiver(Context context) {
        synchronized (LOCK) {
            if (powerStateReceiver != null) {
                return;
            }
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    String action = intent.getAction();
                    Object monitor;
                    synchronized (LOCK) {
                        monitor = monitorReference.get();
                    }
                    if (Intent.ACTION_SCREEN_ON.equals(action)) {
                        if (monitor != null) {
                            scheduleLaunch(monitor, 100L);
                        }
                    } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        invalidateAuthenticationForSleep(monitor, -2);
                    } else if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                        publishHookHeartbeat(receiverContext);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_USER_UNLOCKED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            powerStateReceiver = receiver;
            log("Installed screen-state broadcast fallback");
        }
    }

    private static void maybeLaunch(Object monitor, long generation) {
        Context context;
        synchronized (LOCK) {
            context = systemUiContextReference.get();
        }
        if (context == null || monitor == null) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            if (generation != wakeGeneration || configCheckInFlight) {
                return;
            }
            if (activeToken != null && now - activeTokenCreatedAt < 18_000L) {
                return;
            }
            if (passiveAuthenticatedAt != 0L
                    && now - passiveAuthenticatedAt < 60_000L) {
                log("Skipped: already authenticated and waiting for upward swipe");
                return;
            }
            if (now - lastLaunchAt < 2_500L) {
                return;
            }
        }

        try {
            KeyguardManager keyguardManager = context.getSystemService(KeyguardManager.class);
            PowerManager powerManager = context.getSystemService(PowerManager.class);
            if (keyguardManager == null || !keyguardManager.isKeyguardLocked()
                    || powerManager == null || !powerManager.isInteractive()) {
                log("Skipped: keyguard is not locked or display is not interactive");
                return;
            }
            int selectedUserId = resolveSelectedUserId(monitor);
            if (!SystemUiCompat.isWeakBiometricAllowed(monitor, selectedUserId)) {
                log("Skipped: Android requires PIN, strong authentication, or policy blocks face");
                return;
            }
            if (SystemUiCompat.canSkipBouncer(monitor, selectedUserId)) {
                log("Skipped: lock screen is already authenticated and waiting for swipe");
                return;
            }
            synchronized (LOCK) {
                if (generation != wakeGeneration || configCheckInFlight) {
                    return;
                }
                configCheckInFlight = true;
                monitorReference = new WeakReference<>(monitor);
            }
            showKeyguardAnimation();
            CONFIG_EXECUTOR.execute(() -> {
                Bundle state = null;
                try {
                    state = context.getContentResolver().call(
                            Uri.parse(Constants.PROVIDER_URI), "state", null, null);
                } catch (Throwable error) {
                    log("Unable to read module state", error);
                }
                Bundle result = state;
                Handler handler = getMainHandler();
                if (handler != null) {
                    handler.post(() -> finishLaunch(monitor, generation, result));
                }
            });
        } catch (Throwable error) {
            synchronized (LOCK) {
                configCheckInFlight = false;
            }
            removeKeyguardAnimation();
            log("Unable to prepare 2D face session", error);
        }
    }

    private static void finishLaunch(Object monitor, long generation, Bundle state) {
        Context context;
        long launchNotBefore;
        synchronized (LOCK) {
            if (generation != wakeGeneration) {
                configCheckInFlight = false;
                return;
            }
            context = systemUiContextReference.get();
            launchNotBefore = lastWakeTriggerAt + LOCKSCREEN_SETTLE_MS;
        }
        if (state != null) {
            applyAnimationStyle(state.getInt("animation_style",
                    UnlockFaceAnimationView.STYLE_FACE_ID));
        }
        long remaining = launchNotBefore - android.os.SystemClock.elapsedRealtime();
        if (remaining > 0L) {
            Handler handler = getMainHandler();
            if (handler != null) {
                handler.postDelayed(() -> finishLaunch(monitor, generation, state), remaining);
            }
            return;
        }
        synchronized (LOCK) {
            configCheckInFlight = false;
        }
        if (context == null || state == null || !state.getBoolean("enabled")
                || !state.getBoolean("enrolled")
                || state.getLong("lockout_until", 0L) > System.currentTimeMillis()) {
            removeKeyguardAnimation();
            log("Skipped: module is disabled, not enrolled, or temporarily locked out");
            return;
        }
        try {
            KeyguardManager keyguardManager = context.getSystemService(KeyguardManager.class);
            PowerManager powerManager = context.getSystemService(PowerManager.class);
            if (keyguardManager == null || !keyguardManager.isKeyguardLocked()
                    || powerManager == null || !powerManager.isInteractive()) {
                removeKeyguardAnimation();
                return;
            }
            String token = UUID.randomUUID().toString() + UUID.randomUUID();
            Bundle authorization = context.getContentResolver().call(
                    Uri.parse(Constants.PROVIDER_URI), "authorize_unlock", token, null);
            if (authorization == null || !authorization.getBoolean("ok", false)) {
                removeKeyguardAnimation();
                log("Skipped: camera host session authorization failed");
                return;
            }
            long now = android.os.SystemClock.elapsedRealtime();
            synchronized (LOCK) {
                if (generation != wakeGeneration) {
                    removeKeyguardAnimation();
                    return;
                }
                activeToken = token;
                activeTokenCreatedAt = now;
                lastLaunchAt = now;
                cameraHostActive = true;
            }
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(Constants.PACKAGE_NAME,
                    Constants.PACKAGE_NAME + ".UnlockFaceCaptureActivity"));
            intent.putExtra(Constants.EXTRA_MODE, Constants.MODE_UNLOCK);
            intent.putExtra(Constants.EXTRA_SESSION_TOKEN, token);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            ActivityOptions options = ActivityOptions.makeCustomAnimation(context, 0, 0);
            if (Build.VERSION.SDK_INT >= 36) {
                // Android 16 requires an explicit opt-in for cross-UID touches outside a
                // translucent launched Activity. The host window also uses WM alpha=0 so
                // older Android releases follow their documented safe pass-through path.
                options.setAllowPassThroughOnTouchOutside(true);
            }
            context.startActivity(intent, options.toBundle());
        } catch (Throwable error) {
            synchronized (LOCK) {
                activeToken = null;
                cameraHostActive = false;
            }
            removeKeyguardAnimation();
            log("Unable to start 2D face session", error);
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private static void ensureResultReceiver(Context context) {
        synchronized (LOCK) {
            if (resultReceiver != null) {
                return;
            }
            resultReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    String token = intent.getStringExtra(Constants.EXTRA_SESSION_TOKEN);
                    if (Constants.ACTION_CAMERA_HOST_READY.equals(intent.getAction())) {
                        boolean valid;
                        synchronized (LOCK) {
                            valid = cameraHostActive && activeToken != null
                                    && activeToken.equals(token);
                        }
                        Bundle extras = intent.getExtras();
                        IBinder activityToken = extras == null ? null
                                : extras.getBinder(Constants.EXTRA_ACTIVITY_TOKEN);
                        if (valid && activityToken != null) {
                            disableCameraHostInputSink(activityToken);
                        } else {
                            log("Ignored camera-host ready signal with an invalid session");
                        }
                        return;
                    }
                    if (Constants.ACTION_CAMERA_HOST_FINISHED.equals(intent.getAction())) {
                        boolean matched = false;
                        synchronized (LOCK) {
                            if (activeToken != null && activeToken.equals(token)) {
                                activeToken = null;
                                cameraHostActive = false;
                                matched = true;
                            }
                        }
                        if (matched) {
                            removeKeyguardAnimationNow();
                        }
                        return;
                    }
                    boolean success = intent.getBooleanExtra(Constants.EXTRA_SUCCESS, false);
                    Object monitor;
                    synchronized (LOCK) {
                        if (activeToken == null || !activeToken.equals(token)) {
                            log("Ignored result with an invalid session token");
                            return;
                        }
                        // Keep the transparent camera host marked active until the user really
                        // leaves keyguard. Closing it at recognition time makes Android perform
                        // an OCCLUDED -> LOCKSCREEN task transition, exposing the background app
                        // for one frame and creating the visible white flash.
                        // Authentication itself can emit another keyguard callback. Start the
                        // cooldown at result time so a slow scan cannot reopen the overlay.
                        lastLaunchAt = android.os.SystemClock.elapsedRealtime();
                        monitor = monitorReference.get();
                    }
                    if (!success || monitor == null) {
                        showKeyguardFailure();
                        return;
                    }
                    Handler handler = getMainHandler();
                    if (handler != null) {
                        boolean bouncerShowing = isCredentialBouncerShowing();
                        if (bouncerShowing) {
                            // The user has already asked for the credential screen. A valid
                            // face result should complete that pending unlock immediately.
                            removeKeyguardAnimationNow();
                            handler.post(() -> reportWeakFaceSuccess(monitor, true));
                            return;
                        }
                        showKeyguardSuccess();
                        long successGeneration;
                        synchronized (LOCK) {
                            successGeneration = wakeGeneration;
                        }
                        handler.post(() -> reportWeakFaceSuccess(monitor, false));
                        // Keep the success tick briefly on the lock screen. The synchronous
                        // setKeyguardShowing(false) hook above always wins if the user swipes
                        // to the launcher sooner.
                        handler.postDelayed(() -> {
                            synchronized (LOCK) {
                                if (wakeGeneration != successGeneration) {
                                    return;
                                }
                            }
                            removeKeyguardAnimation();
                        }, 720L);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(Constants.ACTION_UNLOCK_RESULT);
            filter.addAction(Constants.ACTION_CAMERA_HOST_FINISHED);
            filter.addAction(Constants.ACTION_CAMERA_HOST_READY);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(resultReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(resultReceiver, filter);
            }
        }
    }

    private static void disableCameraHostInputSink(IBinder activityToken) {
        try {
            Class<?> activityClientClass = XposedHelpers.findClass(
                    "android.app.ActivityClient", null);
            Object activityClient = XposedHelpers.callStaticMethod(
                    activityClientClass, "getInstance");
            XposedHelpers.callMethod(activityClient, "setActivityRecordInputSinkEnabled",
                    activityToken, false);
            log("Disabled camera-host ActivityRecordInputSink");
        } catch (Throwable error) {
            // Older Android releases do not expose this controller method. The host still uses
            // a 1x1 window with alpha 0, which is the documented cross-version fallback.
            log("Camera-host input-sink controller unavailable", error);
        }
    }

    private static void reportWeakFaceSuccess(Object monitor, boolean dismissBouncer) {
        try {
            int userId = resolveSelectedUserId(monitor);
            if (!SystemUiCompat.isWeakBiometricAllowed(monitor, userId)) {
                showKeyguardFailure();
                log("Success ignored because strong authentication or policy now blocks face");
                return;
            }
            if (dismissBouncer) {
                synchronized (LOCK) {
                    passiveAuthenticatedAt = 0L;
                }
                clearStoredWeakAuthentication(monitor);
                if (!SystemUiCompat.dispatchFaceAuthenticated(monitor, userId)) {
                    showKeyguardFailure();
                    log("Credential-bouncer adapter rejected the face result");
                    return;
                }
                publishCurrentHeartbeat();
                log("Reported weak 2D face success from credential bouncer for user "
                        + userId + "; dismissing immediately");
                return;
            }
            // Store a weak, one-shot biometric success without dispatching Android's
            // onFaceAuthenticated callback, because that callback dismisses many AOSP
            // keyguards immediately. KeyguardUpdateMonitor clears this native cache when the
            // device goes to sleep; its normal upward-swipe path consumes it to skip bouncer.
            if (!storeWeakAuthentication(monitor, userId)) {
                showKeyguardFailure();
                log("Passive authentication adapter rejected the face result");
                return;
            }
            synchronized (LOCK) {
                passiveAuthenticatedAt = android.os.SystemClock.elapsedRealtime();
            }
            setNativeDeviceEntryIcon(true);
            refreshLockScreenTimeout();
            publishCurrentHeartbeat();
            log("Reported passive weak 2D face success for user " + userId
                    + "; waiting for upward swipe");
        } catch (Throwable error) {
            log("Unable to report weak 2D face success", error);
        }
    }

    private static void installBouncerStateHook(ClassLoader classLoader) {
        Class<?> managerClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager",
                classLoader);
        if (managerClass == null) {
            log("StatusBarKeyguardViewManager was not found");
            return;
        }
        XposedBridge.hookAllConstructors(managerClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                synchronized (LOCK) {
                    keyguardViewManagerReference = new WeakReference<>(param.thisObject);
                }
            }
        });
        log("Installed credential-bouncer state hook");
    }

    private static boolean isCredentialBouncerShowing() {
        Object manager;
        synchronized (LOCK) {
            manager = keyguardViewManagerReference.get();
        }
        if (manager == null) {
            log("Credential-bouncer manager is unavailable; keeping swipe-to-unlock");
            return false;
        }
        try {
            boolean showing = (boolean) XposedHelpers.callMethod(manager,
                    "isBouncerShowing");
            log("Credential bouncer showing=" + showing);
            return showing;
        } catch (Throwable error) {
            log("Unable to read credential-bouncer state; keeping swipe-to-unlock", error);
            return false;
        }
    }

    private static void installDeviceEntryIconHook(ClassLoader classLoader) {
        Class<?> iconClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.keyguard.ui.view.DeviceEntryIconView",
                classLoader);
        if (iconClass == null) {
            log("Native device-entry icon class was not found");
            return;
        }
        XposedBridge.hookAllConstructors(iconClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                synchronized (LOCK) {
                    deviceEntryIconViewReference = new WeakReference<>(param.thisObject);
                }
            }
        });
        log("Installed native device-entry icon hook");
    }

    private static void setNativeDeviceEntryIcon(boolean unlocked) {
        Object container;
        synchronized (LOCK) {
            container = deviceEntryIconViewReference.get();
        }
        if (container == null) {
            log("Native device-entry icon is unavailable");
            return;
        }
        try {
            ClassLoader classLoader = container.getClass().getClassLoader();
            Class<?> iconTypeClass = XposedHelpers.findClass(
                    "com.android.systemui.keyguard.ui.view.DeviceEntryIconView$IconType",
                    classLoader);
            Object iconType = XposedHelpers.getStaticObjectField(
                    iconTypeClass, unlocked ? "UNLOCK" : "LOCK");
            int[] iconState = (int[]) XposedHelpers.callStaticMethod(
                    container.getClass(), "getIconState", iconType, false);
            Object foregroundIcon = XposedHelpers.getObjectField(container, "iconView");
            XposedHelpers.callMethod(foregroundIcon, "setImageState", iconState, true);

            int descriptionId = (int) XposedHelpers.callMethod(
                    iconType, "getContentDescriptionResId");
            View containerView = (View) container;
            containerView.setContentDescription(
                    containerView.getContext().getString(descriptionId));

            Class<?> hintTypeClass = XposedHelpers.findClass(
                    "com.android.systemui.keyguard.ui.view.DeviceEntryIconView$AccessibilityHintType",
                    classLoader);
            Object hintType = XposedHelpers.getStaticObjectField(
                    hintTypeClass, unlocked ? "ENTER" : "BOUNCER");
            XposedHelpers.setObjectField(container, "accessibilityHintType", hintType);
            containerView.sendAccessibilityEvent(2048);
            log("Native lock icon state=" + (unlocked ? "unlocked" : "locked"));
        } catch (Throwable error) {
            log("Unable to update native lock icon", error);
        }
    }

    private static void restorePassiveAuthenticationIfPending(Object monitor) {
        long authenticatedAt;
        synchronized (LOCK) {
            authenticatedAt = passiveAuthenticatedAt;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (authenticatedAt == 0L || now - authenticatedAt >= 60_000L) {
            return;
        }
        try {
            int userId = resolveSelectedUserId(monitor);
            if (storeWeakAuthentication(monitor, userId)) {
                log("Restored pending passive authentication after SystemUI cache clear");
            } else {
                synchronized (LOCK) {
                    passiveAuthenticatedAt = 0L;
                }
                setNativeDeviceEntryIcon(false);
                log("Unable to restore passive authentication; device remains locked");
            }
        } catch (Throwable error) {
            log("Unable to restore pending passive authentication", error);
        }
    }

    private static boolean storeWeakAuthentication(Object monitor, int userId) {
        return SystemUiCompat.storeWeakAuthentication(monitor, userId);
    }

    private static void clearStoredWeakAuthentication(Object monitor) {
        try {
            int userId = resolveSelectedUserId(monitor);
            SystemUiCompat.clearStoredWeakAuthentication(monitor, userId);
        } catch (Throwable error) {
            log("Unable to clear stale passive authentication", error);
        }
    }

    private static void resetPassiveState() {
        synchronized (LOCK) {
            passiveAuthenticatedAt = 0L;
            activeToken = null;
            configCheckInFlight = false;
            cameraHostActive = false;
            lastLaunchAt = 0L;
            lastWakeTriggerAt = 0L;
            wakeGeneration++;
        }
        removeKeyguardAnimation();
    }

    private static void invalidateAuthenticationForSleep(Object monitor, int messageWhat) {
        resetPassiveState();
        clearStoredWeakAuthentication(monitor);
        setNativeDeviceEntryIcon(false);
        log("Cleared passive authentication for sleep, message=" + messageWhat);
    }

    private static void installKeyguardOverlayHook(ClassLoader classLoader) {
        String[] candidates = new String[]{
                "com.android.systemui.shade.NotificationShadeWindowView",
                "com.android.systemui.statusbar.phone.NotificationShadeWindowView",
                "com.android.systemui.statusbar.phone.StatusBarWindowView"
        };
        for (String className : candidates) {
            Class<?> rootClass = XposedHelpers.findClassIfExists(className, classLoader);
            if (rootClass == null) {
                continue;
            }
            XposedBridge.hookAllConstructors(rootClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof ViewGroup) {
                        synchronized (LOCK) {
                            keyguardRootReference = new WeakReference<>(
                                    (ViewGroup) param.thisObject);
                        }
                        log("Captured native keyguard root: " + className);
                        publishCurrentHeartbeat();
                    }
                }
            });
            overlayHookInstalled = true;
            log("Installed native keyguard overlay hook: " + className);
            return;
        }
        log("Native keyguard root class was not found");
    }

    private static void installKeyguardOcclusionGuard(ClassLoader classLoader) {
        Class<?> mediatorClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.keyguard.KeyguardViewMediator", classLoader);
        if (mediatorClass == null) {
            log("KeyguardViewMediator was not found; occlusion guard unavailable");
            return;
        }
        boolean setOccludedHooked = hookAllMethodsIfPresent(
                mediatorClass, "setOccluded", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                boolean suppress;
                synchronized (LOCK) {
                    suppress = cameraHostActive;
                }
                if (suppress && param.args.length > 0
                        && Boolean.TRUE.equals(param.args[0])) {
                    // The 1x1 transparent Activity only hosts CameraX. It must not make
                    // SystemUI leave LOCKSCREEN state or hide the native clock/animation.
                    param.setResult(null);
                }
            }
        });
        occlusionGuardInstalled = setOccludedHooked;

        Class<?> runnerClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.keyguard.KeyguardViewMediator$"
                        + "OccludeActivityLaunchRemoteAnimationRunner",
                classLoader);
        if (runnerClass != null) {
            XposedBridge.hookAllMethods(runnerClass, "onAnimationStart",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            boolean suppress;
                            synchronized (LOCK) {
                                suppress = cameraHostActive;
                            }
                            if (!suppress) {
                                return;
                            }
                            // Callback position differs between Android releases. Only suppress
                            // the transition when the finish callback can be identified, or the
                            // window manager could be left waiting indefinitely.
                            Object finishedCallback = SystemUiCompat
                                    .findAnimationFinishedCallback(param.args);
                            if (finishedCallback == null) {
                                return;
                            }
                            XposedHelpers.callMethod(finishedCallback,
                                    "onAnimationFinished");
                            param.setResult(null);
                            log("Suppressed camera-host keyguard occlude animation");
                        }
                    });
        } else {
            log("Occlude animation runner was not found");
        }
        log("Installed camera-host keyguard occlusion guard");
    }

    private static void installKeyguardVisibilityFallbackHooks(ClassLoader classLoader) {
        XC_MethodHook hiddenHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length > 0 && Boolean.FALSE.equals(param.args[0])) {
                    removeKeyguardAnimationNow();
                }
            }
        };
        Class<?> mediatorClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.keyguard.KeyguardViewMediator", classLoader);
        boolean mediatorHook = hookAllMethodsIfPresent(
                mediatorClass, "setShowingLocked", hiddenHook);

        Class<?> stateControllerClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl",
                classLoader);
        boolean stateHook = hookAllMethodsIfPresent(
                stateControllerClass, "notifyKeyguardState", hiddenHook);
        if (mediatorHook || stateHook) {
            log("Installed cross-version keyguard-hidden animation cleanup");
        }
    }

    private static void showKeyguardAnimation() {
        Handler handler = getMainHandler();
        if (handler == null) {
            return;
        }
        handler.post(() -> {
            ViewGroup root;
            synchronized (LOCK) {
                root = keyguardRootReference.get();
            }
            if (root == null) {
                log("Unable to show animation: native keyguard root is unavailable");
                return;
            }
            removeKeyguardAnimationNow();
            int style;
            synchronized (LOCK) {
                style = selectedAnimationStyle;
            }
            UnlockFaceAnimationView animation = new UnlockFaceAnimationView(
                    root.getContext(), style);
            animation.setClickable(false);
            animation.setFocusable(false);
            animation.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            float density = root.getResources().getDisplayMetrics().density;
            int screenWidthDp = root.getResources().getConfiguration().screenWidthDp;
            float targetWidthDp = Math.min(176f,
                    Math.max(144f, screenWidthDp > 0 ? screenWidthDp - 32f : 176f));
            int animationWidth = Math.round(targetWidthDp * density);
            int animationHeight = Math.round(76f * density);
            int statusBarHeight = resolveStatusBarInset(root);
            int topMargin = Math.max(Math.round(90f * density),
                    statusBarHeight + Math.round(42f * density));
            if (root.getHeight() > 0) {
                topMargin = Math.min(topMargin,
                        Math.max(statusBarHeight, root.getHeight()
                                - animationHeight - Math.round(16f * density)));
            }
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    animationWidth, animationHeight,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            params.topMargin = topMargin;
            try {
                root.addView(animation, params);
                animation.bringToFront();
                synchronized (LOCK) {
                    keyguardAnimationView = animation;
                }
                log("Lock-screen animation attached, style=" + style
                        + ", sizeDp=" + Math.round(targetWidthDp) + "x76");
            } catch (Throwable error) {
                log("Native keyguard root rejected the animation layout", error);
            }
        });
    }

    @SuppressWarnings("deprecation")
    private static int resolveStatusBarInset(View root) {
        WindowInsets insets = root.getRootWindowInsets();
        if (insets == null) {
            return 0;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return insets.getInsets(WindowInsets.Type.statusBars()).top;
        }
        return insets.getStableInsetTop();
    }

    private static void applyAnimationStyle(int requestedStyle) {
        int style = requestedStyle == UnlockFaceAnimationView.STYLE_DYNAMIC_ISLAND
                ? UnlockFaceAnimationView.STYLE_DYNAMIC_ISLAND
                : UnlockFaceAnimationView.STYLE_FACE_ID;
        UnlockFaceAnimationView animation;
        boolean changed;
        synchronized (LOCK) {
            changed = selectedAnimationStyle != style;
            selectedAnimationStyle = style;
            animation = keyguardAnimationView;
        }
        if (animation != null) {
            animation.setVisualStyle(style);
        }
        if (changed) {
            log("Lock-screen animation style=" + style);
        }
    }

    private static void showKeyguardSuccess() {
        Handler handler = getMainHandler();
        if (handler != null) {
            handler.post(() -> {
                UnlockFaceAnimationView animation;
                synchronized (LOCK) {
                    animation = keyguardAnimationView;
                }
                if (animation != null) {
                    animation.showSuccess();
                }
            });
        }
    }

    private static void showKeyguardFailure() {
        Handler handler = getMainHandler();
        if (handler != null) {
            handler.post(() -> {
                UnlockFaceAnimationView animation;
                synchronized (LOCK) {
                    animation = keyguardAnimationView;
                }
                if (animation != null) {
                    animation.showFailure("请使用密码");
                    handler.postDelayed(XposedEntry::removeKeyguardAnimation, 700L);
                }
            });
        }
    }

    private static void removeKeyguardAnimation() {
        Handler handler = getMainHandler();
        if (handler != null) {
            handler.post(XposedEntry::removeKeyguardAnimationNow);
        }
    }

    private static void removeKeyguardAnimationNow() {
        UnlockFaceAnimationView animation;
        synchronized (LOCK) {
            animation = keyguardAnimationView;
            keyguardAnimationView = null;
        }
        if (animation != null && animation.getParent() instanceof ViewGroup) {
            ((ViewGroup) animation.getParent()).removeView(animation);
        }
    }

    private static void refreshLockScreenTimeout() {
        Context context;
        synchronized (LOCK) {
            context = systemUiContextReference.get();
        }
        if (context == null) {
            return;
        }
        try {
            PowerManager powerManager = context.getSystemService(PowerManager.class);
            if (powerManager != null) {
                XposedHelpers.callMethod(powerManager, "userActivity",
                        android.os.SystemClock.uptimeMillis(), 0, 0);
            }
        } catch (Throwable error) {
            log("Unable to refresh lock-screen timeout", error);
        }
    }

    private static int resolveSelectedUserId(Object monitor) {
        return SystemUiCompat.resolveSelectedUserId(monitor);
    }

    private static Handler getMainHandler() {
        Handler current = mainHandler;
        if (current != null) {
            return current;
        }
        Looper looper = Looper.getMainLooper();
        if (looper == null) {
            return null;
        }
        synchronized (LOCK) {
            if (mainHandler == null) {
                mainHandler = new Handler(looper);
            }
            return mainHandler;
        }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private static void log(String message, Throwable error) {
        XposedBridge.log(TAG + ": " + message + "\n" + android.util.Log.getStackTraceString(error));
    }
}
