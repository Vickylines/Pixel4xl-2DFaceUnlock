package com.yusd.pixel2dface;

import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.ComponentName;
import android.os.Build;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Array;
import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.List;

import de.robv.android.xposed.XposedHelpers;

/**
 * Runtime compatibility helpers for AOSP-derived SystemUI builds.
 *
 * <p>SystemUI is not a public API. Every operation in this class is capability based and fails
 * closed: an unknown field or method must keep the device locked instead of guessing.</p>
 */
final class SystemUiCompat {
    static final int MAX_SUPPORTED_SDK = 36;
    private static final int INVALID_USER_ID = -10_000;
    private static final int ANDROID_UIDS_PER_USER = 100_000;
    private static final String[] FACE_FIRST_AUTH_FIELDS = {
            "mUserFaceAuthenticated", "mUserFingerprintAuthenticated"
    };
    private static final String[] PIXEL_4_AUTH_FIELDS = {
            "mUserFingerprintAuthenticated", "mUserFaceAuthenticated"
    };

    private static volatile String activeAuthenticationField;
    private static volatile int activeAuthenticationUserId = INVALID_USER_ID;
    private static volatile String activeAuthenticationAdapter = "尚未运行验证";
    private static WeakReference<Object> activeAuthenticationMonitor = new WeakReference<>(null);
    private static WeakReference<Object> activeAuthenticationState = new WeakReference<>(null);
    private static Object activeAuthenticationValue;

    private SystemUiCompat() {
    }

    static boolean isPixelDevice() {
        String manufacturer = safe(Build.MANUFACTURER);
        String brand = safe(Build.BRAND);
        String model = safe(Build.MODEL);
        return ("google".equals(manufacturer) || "google".equals(brand))
                && model.startsWith("pixel");
    }

    static boolean isSupportedAndroidVersion() {
        // minSdk 29 already prevents installation below Android 10.
        return Build.VERSION.SDK_INT >= 29 && Build.VERSION.SDK_INT <= MAX_SUPPORTED_SDK;
    }

    static String platformLabel() {
        return String.format(Locale.ROOT, "%s %s · Android %s / API %d",
                safeDisplay(Build.MANUFACTURER), safeDisplay(Build.MODEL),
                safeDisplay(Build.VERSION.RELEASE), Build.VERSION.SDK_INT);
    }

    static String getAuthenticationAdapterLabel() {
        return activeAuthenticationAdapter;
    }

    static Class<?> findFirstClass(ClassLoader classLoader, String... candidates) {
        for (String candidate : candidates) {
            Class<?> result = XposedHelpers.findClassIfExists(candidate, classLoader);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    static boolean hasMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (name.equals(method.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    static Context resolveContext(Object instance, Object[] constructorArgs) {
        if (constructorArgs != null) {
            for (Object argument : constructorArgs) {
                if (argument instanceof Context) {
                    return (Context) argument;
                }
            }
        }
        if (instance == null) {
            return null;
        }
        String[] preferredFields = {"mContext", "context", "mApplicationContext"};
        for (String fieldName : preferredFields) {
            try {
                Object value = XposedHelpers.getObjectField(instance, fieldName);
                if (value instanceof Context) {
                    return (Context) value;
                }
            } catch (Throwable ignored) {
                // Try type-based discovery below.
            }
        }
        for (Class<?> current = instance.getClass(); current != null;
                current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!Context.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(instance);
                    if (value instanceof Context) {
                        return (Context) value;
                    }
                } catch (Throwable ignored) {
                    // A vendor may block access to individual fields; keep looking.
                }
            }
        }
        return null;
    }

    static int resolveSelectedUserId(Object monitor) {
        if (monitor == null) {
            return INVALID_USER_ID;
        }
        try {
            Object selectedUserInteractor = XposedHelpers.getObjectField(
                    monitor, "mSelectedUserInteractor");
            return (int) XposedHelpers.callMethod(
                    selectedUserInteractor, "getSelectedUserId");
        } catch (Throwable ignored) {
            // Android 15+ path was unavailable; try Android 12-14 UserTracker.
        }
        try {
            Object userTracker = XposedHelpers.getObjectField(monitor, "mUserTracker");
            return (int) XposedHelpers.callMethod(userTracker, "getUserId");
        } catch (Throwable ignored) {
            // Try the static AOSP Android 10-13 current-user accessor.
        }
        try {
            return (int) XposedHelpers.callStaticMethod(monitor.getClass(), "getCurrentUser");
        } catch (Throwable ignored) {
            // Last capability-based fallback; unlike a process-UID guess this follows the
            // foreground Android user in multi-user environments.
        }
        try {
            return (int) XposedHelpers.callStaticMethod(ActivityManager.class, "getCurrentUser");
        } catch (Throwable ignored) {
            return INVALID_USER_ID;
        }
    }

    static boolean isWeakBiometricAllowed(Object monitor, int userId) {
        if (monitor == null || !isSupportedUser(userId)
                || resolveSelectedUserId(monitor) != userId) {
            return false;
        }
        if (!isFaceAllowedByPolicy(monitor, userId)) {
            return false;
        }
        try {
            return (boolean) XposedHelpers.callMethod(
                    monitor, "isUnlockingWithBiometricAllowed", false);
        } catch (Throwable ignored) {
            // Only the Android 10 contract predates the explicit weak/strong argument.
        }
        if (Build.VERSION.SDK_INT == 29) {
            try {
                return (boolean) XposedHelpers.callMethod(monitor, "isUnlockingWithBiometricAllowed");
            } catch (Throwable ignored) {
                // Unknown monitor contracts fail closed. Going directly to StrongAuthTracker
                // would omit the monitor's biometric lockout and native policy checks.
            }
        }
        return false;
    }

    private static boolean isFaceAllowedByPolicy(Object monitor, int userId) {
        try {
            return !(boolean) XposedHelpers.callMethod(monitor, "isFaceDisabled", userId);
        } catch (Throwable ignored) {
            // Some newer/vendor builds remove this bridge; query its two underlying checks.
        }
        boolean simStateKnown = false;
        try {
            if ((boolean) XposedHelpers.callMethod(monitor, "isSimPinSecure")) {
                return false;
            }
            simStateKnown = true;
        } catch (Throwable ignored) {
            // A missing SIM-policy signal is security-sensitive. We still query DPM for a
            // useful diagnostic path below, but never authorize face from incomplete state.
        }
        Context context = resolveContext(monitor, null);
        if (context == null) {
            return false;
        }
        try {
            Object policyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (policyManager == null) {
                return false;
            }
            int disabled;
            try {
                disabled = (int) XposedHelpers.callMethod(policyManager,
                        "getKeyguardDisabledFeatures", null, userId);
            } catch (Throwable ignored) {
                // The no-user form follows this process's Android user. It is safe only
                // because isSupportedUser requires the selected user to equal that user.
                disabled = (int) XposedHelpers.callMethod(policyManager,
                        "getKeyguardDisabledFeatures", (Object) null);
            }
            int faceOrAllBiometrics = DevicePolicyManager.KEYGUARD_DISABLE_FACE
                    | DevicePolicyManager.KEYGUARD_DISABLE_BIOMETRICS;
            return simStateKnown && (disabled & faceOrAllBiometrics) == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean canSkipBouncer(Object monitor, int userId) {
        if (monitor == null || !isSupportedUser(userId)
                || !isWeakBiometricAllowed(monitor, userId)) {
            return false;
        }
        try {
            return (boolean) XposedHelpers.callMethod(
                    monitor, "getUserCanSkipBouncer", userId);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean storeWeakAuthentication(Object monitor, int userId) {
        clearStoredWeakAuthentication(monitor, userId);
        if (monitor == null || !isSupportedUser(userId)
                || !isWeakBiometricAllowed(monitor, userId)) {
            activeAuthenticationAdapter = "用户解析失败（已保持锁定）";
            return false;
        }
        if (canSkipBouncer(monitor, userId)) {
            // Never overwrite native face/fingerprint/trust authentication owned by Android.
            return false;
        }
        for (String fieldName : authenticationFieldCandidates()) {
            Object state;
            try {
                state = XposedHelpers.getObjectField(monitor, fieldName);
            } catch (Throwable ignored) {
                continue;
            }
            if (state == null) {
                continue;
            }
            try {
                String adapter;
                if (state instanceof SparseBooleanArray) {
                    if (Build.VERSION.SDK_INT != 29
                            || ((SparseBooleanArray) state).get(userId, false)) {
                        continue;
                    }
                    ((SparseBooleanArray) state).put(userId, true);
                    adapter = "legacy-boolean/" + shortField(fieldName);
                } else if (state instanceof SparseArray) {
                    Object authenticated = createBiometricAuthenticated(monitor);
                    if (authenticated == null) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    SparseArray<Object> sparseArray = (SparseArray<Object>) state;
                    if (sparseArray.get(userId) != null) {
                        continue;
                    }
                    sparseArray.put(userId, authenticated);
                    adapter = "typed-object/" + shortField(fieldName);
                } else {
                    continue;
                }
                if (!canSkipBouncer(monitor, userId)) {
                    deleteFromState(state, userId);
                    continue;
                }
                activeAuthenticationField = fieldName;
                activeAuthenticationUserId = userId;
                activeAuthenticationMonitor = new WeakReference<>(monitor);
                activeAuthenticationState = new WeakReference<>(state);
                activeAuthenticationValue = state instanceof SparseArray
                        ? ((SparseArray<?>) state).get(userId) : Boolean.TRUE;
                activeAuthenticationAdapter = adapter;
                return true;
            } catch (Throwable ignored) {
                deleteFromState(state, userId);
            }
        }
        activeAuthenticationAdapter = "不兼容（已保持锁定）";
        return false;
    }

    static boolean dispatchFaceAuthenticated(Object monitor, int userId) {
        clearStoredWeakAuthentication(monitor, userId);
        if (monitor == null || !isSupportedUser(userId)
                || !isWeakBiometricAllowed(monitor, userId)) {
            activeAuthenticationAdapter = "用户解析失败（已保持锁定）";
            return false;
        }
        try {
            XposedHelpers.callMethod(monitor, "onFaceAuthenticated", userId, false);
            // Modern SystemUI forwards this callback to the bouncer before its face
            // interactor/background task updates dismiss state. A synchronous
            // getUserCanSkipBouncer() check therefore rejects a valid result on A16.
            // Policy and selected-user checks were already completed immediately before
            // dispatch; let SystemUI's official callback own the final dismissal.
            activeAuthenticationField = null;
            activeAuthenticationUserId = -1;
            activeAuthenticationAdapter = "callback-v2/face-async";
            return true;
        } catch (Throwable ignored) {
            // Android 10 callback does not carry a strong-biometric flag.
        }
        if (Build.VERSION.SDK_INT == 29) {
            try {
                XposedHelpers.callMethod(monitor, "onFaceAuthenticated", userId);
                activeAuthenticationAdapter = "callback-v1/face-async";
                return true;
            } catch (Throwable ignored) {
                // An unrecognized callback must not be treated as successful delivery.
            }
        }
        activeAuthenticationAdapter = "回调不兼容（已保持锁定）";
        return false;
    }

    static void clearStoredWeakAuthentication(Object monitor, int userId) {
        String fieldName = activeAuthenticationField;
        int storedUserId = activeAuthenticationUserId;
        Object owner = activeAuthenticationMonitor.get();
        Object ownedState = activeAuthenticationState.get();
        if (fieldName == null || storedUserId < 0) {
            return;
        }
        try {
            // Clear the captured monitor's exact inserted value, even after user/monitor
            // replacement. Never delete a newer native biometric result by field name alone.
            Object state = owner == null ? null : XposedHelpers.getObjectField(owner, fieldName);
            if (state == ownedState && state instanceof SparseArray
                    && ((SparseArray<?>) state).get(storedUserId) == activeAuthenticationValue) {
                deleteFromState(state, storedUserId);
            } else if (state == ownedState && state instanceof SparseBooleanArray
                    && Build.VERSION.SDK_INT == 29) {
                deleteFromState(state, storedUserId);
            }
        } catch (Throwable ignored) {
            // The system may already have cleared the one-shot state.
        } finally {
            activeAuthenticationField = null;
            activeAuthenticationUserId = INVALID_USER_ID;
            activeAuthenticationMonitor = new WeakReference<>(null);
            activeAuthenticationState = new WeakReference<>(null);
            activeAuthenticationValue = null;
        }
    }

    static Object findAnimationFinishedCallback(Object[] arguments) {
        if (arguments == null) {
            return null;
        }
        for (Object argument : arguments) {
            if (argument != null && hasMethod(argument.getClass(), "onAnimationFinished")) {
                return argument;
            }
        }
        return null;
    }

    static boolean isCameraHostAnimation(Object[] arguments) {
        if (arguments == null) {
            return false;
        }
        for (Object argument : arguments) {
            if (argument == null || !argument.getClass().isArray()
                    || !"android.view.RemoteAnimationTarget".equals(
                    argument.getClass().getComponentType().getName())) {
                continue;
            }
            // The first array contains app targets. A closing launcher may accompany our
            // opening host; another opening/changing app must still run natively.
            int count = Array.getLength(argument);
            if (count == 0) {
                return false;
            }
            int[] modes = new int[count];
            boolean[] hosts = new boolean[count];
            for (int index = 0; index < count; index++) {
                try {
                    Object target = Array.get(argument, index);
                    modes[index] = XposedHelpers.getIntField(target, "mode");
                    if (modes[index] == CameraHostTransitionPolicy.CLOSING) continue;
                    Object task = XposedHelpers.getObjectField(target, "taskInfo");
                    Object activity = XposedHelpers.getObjectField(task, "topActivity");
                    if (!(activity instanceof ComponentName)) return false;
                    ComponentName component = (ComponentName) activity;
                    hosts[index] = CameraHostTransitionPolicy.isHost(component.getPackageName(), component.getClassName());
                } catch (Throwable ignored) {
                    return false;
                }
            }
            return CameraHostTransitionPolicy.ownsOpeningTargets(modes, hosts);
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    static boolean isCameraHostForeground(Context context) {
        try {
            ActivityManager activityManager = context.getSystemService(ActivityManager.class);
            List<ActivityManager.RunningTaskInfo> tasks = activityManager == null
                    ? null : activityManager.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty() || tasks.get(0) == null) return false;
            ComponentName top = tasks.get(0).topActivity;
            return top != null && CameraHostTransitionPolicy.isHost(top.getPackageName(), top.getClassName());
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private static Object createBiometricAuthenticated(Object monitor) {
        Class<?> authenticatedClass = null;
        for (Class<?> nested : monitor.getClass().getDeclaredClasses()) {
            if ("BiometricAuthenticated".equals(nested.getSimpleName())) {
                authenticatedClass = nested;
                break;
            }
        }
        if (authenticatedClass == null) {
            authenticatedClass = XposedHelpers.findClassIfExists(
                    monitor.getClass().getName() + "$BiometricAuthenticated",
                    monitor.getClass().getClassLoader());
        }
        if (authenticatedClass == null) {
            return null;
        }
        try {
            Object result = XposedHelpers.newInstance(authenticatedClass, true, false);
            return isKnownWeakAuthentication(result) ? result : null;
        } catch (Throwable ignored) {
            // Some optimized/vendor builds remove the constructor; allocate and populate fields.
        }
        try {
            Object result = allocateWithoutConstructor(authenticatedClass);
            // Support the inspected AOSP record layout only. Guessing "auth"/"strong"
            // substrings can silently create strong or malformed biometric state on a ROM.
            XposedHelpers.setBooleanField(result, "mAuthenticated", true);
            XposedHelpers.setBooleanField(result, "mIsStrongBiometric", false);
            return isKnownWeakAuthentication(result) ? result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isKnownWeakAuthentication(Object value) {
        try {
            return XposedHelpers.getBooleanField(value, "mAuthenticated")
                    && !XposedHelpers.getBooleanField(value, "mIsStrongBiometric");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object allocateWithoutConstructor(Class<?> targetClass) throws Throwable {
        String[][] candidates = {
                {"sun.misc.Unsafe", "theUnsafe"},
                {"jdk.internal.misc.Unsafe", "THE_ONE"}
        };
        Throwable lastError = null;
        for (String[] candidate : candidates) {
            try {
                Class<?> unsafeClass = Class.forName(candidate[0]);
                Object unsafe = XposedHelpers.getStaticObjectField(
                        unsafeClass, candidate[1]);
                return XposedHelpers.callMethod(unsafe, "allocateInstance", targetClass);
            } catch (Throwable error) {
                lastError = error;
            }
        }
        throw lastError != null ? lastError
                : new IllegalStateException("Unsafe allocator is unavailable");
    }

    private static void deleteFromState(Object state, int userId) {
        try {
            if (state instanceof SparseBooleanArray) {
                ((SparseBooleanArray) state).delete(userId);
            } else if (state instanceof SparseArray) {
                ((SparseArray<?>) state).delete(userId);
            }
        } catch (Throwable ignored) {
            // Fail closed; a later sleep callback also clears SystemUI's biometric caches.
        }
    }

    private static String shortField(String fieldName) {
        return fieldName.contains("Face") ? "face" : "fingerprint";
    }

    private static String[] authenticationFieldCandidates() {
        // Preserve the already device-tested passive path for Pixel 4/4 XL. Other Pixels use
        // the semantically correct face cache first and retain fingerprint only as a fallback.
        String device = safe(Build.DEVICE);
        return "coral".equals(device) || "flame".equals(device)
                ? PIXEL_4_AUTH_FIELDS : FACE_FIRST_AUTH_FIELDS;
    }

    private static boolean isSupportedUser(int userId) {
        // Public SDK stubs do not expose UserHandle.myUserId/getIdentifier. Android assigns
        // each user a fixed 100000-wide UID range; compare against the SystemUI process range.
        int processUserId = android.os.Process.myUid() / ANDROID_UIDS_PER_USER;
        return userId >= 0 && userId == processUserId;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String safeDisplay(String value) {
        return value == null || value.trim().isEmpty() ? "unknown" : value.trim();
    }
}
