package com.yusd.pixel2dface;

import java.util.Properties;

/** Settings and failure policy shared by disk snapshots and transactional updates. */
final class StorePolicy {
    static final int FAILURE_LIMIT = 5;
    static final long LOCKOUT_MS = 30_000L;

    private StorePolicy() { }

    static boolean enabled(Properties properties) {
        return "true".equals(properties.getProperty("enabled"));
    }

    static int profile(Properties properties) {
        int value = integer(properties, "recognition_profile", 1);
        return safeProfile(value);
    }

    static int safeProfile(int value) {
        return value == 0 || value == 2 ? value : 1;
    }

    static int animation(Properties properties) {
        return safeAnimation(integer(properties, "animation_style", 0));
    }

    static int safeAnimation(int value) { return value == 1 ? 1 : 0; }

    static float calibratedThreshold(Properties properties) {
        float value = finiteFloat(properties, "calibrated_threshold", 0.33f);
        return Math.max(0.28f, Math.min(0.35f, value));
    }

    static float recognitionThreshold(Properties properties) {
        int value = profile(properties);
        float cap = value == 0 ? 0.30f : value == 2 ? 0.35f : 0.33f;
        return Math.min(calibratedThreshold(properties), cap);
    }

    static boolean validFailureState(Properties properties) {
        int failures = integer(properties, "failures", 0);
        long until = longValue(properties, "lockout_until", 0L);
        return failures >= 0 && failures < FAILURE_LIMIT && until >= 0L
                && numericOrAbsent(properties, "failures", failures)
                && numericOrAbsent(properties, "lockout_until", until);
    }

    static long lockoutUntil(Properties properties) {
        // A malformed security field must not become an unlocked default state.
        return validFailureState(properties)
                ? longValue(properties, "lockout_until", 0L) : Long.MAX_VALUE;
    }

    static boolean recordFailure(Properties properties, long now) {
        if (!validFailureState(properties)) return false;
        long until = longValue(properties, "lockout_until", 0L);
        if (until > now) return true; // Repeated failures cannot shorten an active lockout.
        int failures = until > 0L ? 1 : integer(properties, "failures", 0) + 1;
        if (failures >= FAILURE_LIMIT) {
            properties.setProperty("failures", "0");
            properties.setProperty("lockout_until", Long.toString(
                    now > Long.MAX_VALUE - LOCKOUT_MS ? Long.MAX_VALUE : now + LOCKOUT_MS));
        } else {
            properties.setProperty("failures", Integer.toString(failures));
            properties.setProperty("lockout_until", "0");
        }
        return true;
    }

    static void resetFailures(Properties properties) {
        properties.setProperty("failures", "0");
        properties.setProperty("lockout_until", "0");
    }

    static int integer(Properties properties, String key, int fallback) {
        try { return Integer.parseInt(properties.getProperty(key)); }
        catch (NumberFormatException error) { return fallback; }
    }

    static long longValue(Properties properties, String key, long fallback) {
        String value = properties.getProperty(key);
        if (value == null) return fallback;
        try { return Long.parseLong(value); }
        catch (NumberFormatException error) { return fallback; }
    }

    static float finiteFloat(Properties properties, String key, float fallback) {
        String encoded = properties.getProperty(key);
        if (encoded == null) return fallback;
        try {
            float value = Float.parseFloat(encoded);
            return Float.isFinite(value) ? value : fallback;
        } catch (NumberFormatException error) { return fallback; }
    }

    private static boolean numericOrAbsent(Properties properties, String key, long value) {
        String encoded = properties.getProperty(key);
        if (encoded == null) return true;
        try { return Long.parseLong(encoded) == value; }
        catch (NumberFormatException error) { return false; }
    }
}
