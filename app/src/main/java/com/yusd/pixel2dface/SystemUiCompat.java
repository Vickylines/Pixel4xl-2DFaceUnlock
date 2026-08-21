package com.yusd.pixel2dface;

import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.os.Build;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

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
        return Build.VERSION.SDK_INT <= MAX_SUPPORTED_SDK;
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
        if (monitor == null || !isSupportedUser(userId)) {
            return false;
        }
        if (!isFaceAllowedByPolicy(monitor, userId)) {
            return false;
        }
        try {
            return (boolean) XposedHelpers.callMethod(
                    monitor, "isUnlockingWithBiometricAllowed", false);
        } catch (Throwable ignored) {
            // Android 10 uses a no-argument form.
        }
        try {
            return (boolean) XposedHelpers.callMethod(
                    monitor, "isUnlockingWithBiometricAllowed");
        } catch (Throwable ignored) {
            // Some vendor builds expose the check only through StrongAuthTracker.
        }
        try {
            Object tracker = XposedHelpers.getObjectField(monitor, "mStrongAuthTracker");
            try {
                return (boolean) XposedHelpers.callMethod(
                        tracker, "isUnlockingWithBiometricAllowed", false);
            } catch (Throwable ignored) {
                return (boolean) XposedHelpers.callMethod(
                        tracker, "isUnlockingWithBiometricAllowed");
            }
        } catch (Throwable ignored) {
            return false;
        }
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
        if (monitor == null || !isSupportedUser(userId)) {
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
        if (monitor == null || !isSupportedUser(userId)) {
            activeAuthenticationAdapter = "用户解析失败（已保持锁定）";
            return false;
        }
        clearStoredWeakAuthentication(monitor, userId);
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
                    ((SparseBooleanArray) state).put(userId, true);
                    adapter = "legacy-boolean/" + shortField(fieldName);
                } else if (state instanceof SparseArray) {
                    Object authenticated = createBiometricAuthenticated(monitor);
                    if (authenticated == null) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    SparseArray<Object> sparseArray = (SparseArray<Object>) state;
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
        if (monitor == null || !isSupportedUser(userId)) {
            activeAuthenticationAdapter = "用户解析失败（已保持锁定）";
            return false;
        }
        clearStoredWeakAuthentication(monitor, userId);
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
        try {
            XposedHelpers.callMethod(monitor, "onFaceAuthenticated", userId);
            activeAuthenticationField = null;
            activeAuthenticationUserId = -1;
            activeAuthenticationAdapter = "callback-v1/face-async";
            return true;
        } catch (Throwable ignored) {
            activeAuthenticationAdapter = "回调不兼容（已保持锁定）";
            return false;
        }
    }

    static void clearStoredWeakAuthentication(Object monitor, int userId) {
        String fieldName = activeAuthenticationField;
        int storedUserId = activeAuthenticationUserId;
        if (monitor == null || fieldName == null || storedUserId < 0) {
            return;
        }
        try {
            Object state = XposedHelpers.getObjectField(monitor, fieldName);
            deleteFromState(state, storedUserId);
        } catch (Throwable ignored) {
            // The system may already have cleared the one-shot state.
        } finally {
            activeAuthenticationField = null;
            activeAuthenticationUserId = INVALID_USER_ID;
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
            return XposedHelpers.newInstance(authenticatedClass, true, false);
        } catch (Throwable ignored) {
            // Some optimized/vendor builds remove the constructor; allocate and populate fields.
        }
        try {
            Object result = allocateWithoutConstructor(authenticatedClass);
            boolean authenticatedSet = false;
            for (Class<?> current = authenticatedClass; current != null;
                    current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (field.getType() != boolean.class) {
                        continue;
                    }
                    field.setAccessible(true);
                    String name = field.getName().toLowerCase(Locale.ROOT);
                    if (name.contains("strong")) {
                        field.setBoolean(result, false);
                    } else if (name.contains("auth")) {
                        field.setBoolean(result, true);
                        authenticatedSet = true;
                    }
                }
            }
            return authenticatedSet ? result : null;
        } catch (Throwable ignored) {
            return null;
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

    private static void deleteFieldForUser(Object monitor, String fieldName, int userId) {
        try {
            deleteFromState(XposedHelpers.getObjectField(monitor, fieldName), userId);
        } catch (Throwable ignored) {
            // A vendor callback may use a different internal cache; its native sleep path
            // remains responsible for clearing that cache.
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
