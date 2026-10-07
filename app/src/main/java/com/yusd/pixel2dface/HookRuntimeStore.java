package com.yusd.pixel2dface;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

/** Small runtime files stay separate from biometric data and use the same transaction lock. */
final class HookRuntimeStore {
    private static final String HEARTBEAT_FILE = "hook.heartbeat";
    private static final String COMPATIBILITY_FILE = "hook.compatibility";
    private static final String SESSION_FILE = "unlock.session";
    private static final int MAX_COMPATIBILITY_BYTES = 2048;

    private HookRuntimeStore() { }

    static boolean heartbeat(Context context, String compatibility) {
        if (compatibility != null
                && compatibility.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > MAX_COMPATIBILITY_BYTES) return false;
        return AtomicStoreFiles.locked(context, false, directory -> {
            long now = System.currentTimeMillis();
            File file = new File(directory, HEARTBEAT_FILE);
            long previous = heartbeatTimestamp(directory);
            // Use the current directory's durable timestamp rather than process-global
            // state, which could skip publication for another context or recovered file.
            if (compatibility != null || previous <= 0L || now < previous
                    || now - previous >= 30_000L) {
                AtomicStoreFiles.writeText(file, Long.toString(now), 32);
            }
            if (compatibility != null && !compatibility.trim().isEmpty()) {
                AtomicStoreFiles.writeText(new File(directory, COMPATIBILITY_FILE),
                        compatibility.trim(), MAX_COMPATIBILITY_BYTES);
            }
            return true;
        });
    }

    static long heartbeat(Context context) {
        return AtomicStoreFiles.locked(context, 0L, directory -> {
            long value = heartbeatTimestamp(directory);
            if (value > 0L) return value;
            // Early betas stored the heartbeat alongside the template.
            return Math.max(0L, StorePolicy.longValue(
                    AtomicStoreFiles.readProperties(context, directory), "hook_heartbeat", 0L));
        });
    }

    private static long heartbeatTimestamp(File directory) throws IOException {
        String value = AtomicStoreFiles.readText(new File(directory, HEARTBEAT_FILE), 32);
        if (value == null) return 0L;
        try { return Math.max(0L, Long.parseLong(value.trim())); }
        catch (NumberFormatException error) { return 0L; }
    }

    static String compatibility(Context context) {
        return AtomicStoreFiles.locked(context, "", directory -> {
            String value = AtomicStoreFiles.readText(
                    new File(directory, COMPATIBILITY_FILE), MAX_COMPATIBILITY_BYTES);
            return value == null ? "" : value.trim();
        });
    }

    static boolean authorize(Context context, String token) {
        if (!UnlockSessionPolicy.validToken(token)) return false;
        return AtomicStoreFiles.locked(context, false, directory -> {
            if (!mayUnlock(AtomicStoreFiles.readProperties(context, directory))) return false;
            return writeAuthorization(context, directory, token);
        });
    }

    /** Caller holds the store lock and has checked the configuration from that transaction. */
    static boolean writeAuthorization(Context context, File directory, String token) throws IOException {
        if (!UnlockSessionPolicy.validToken(token)) return false;
        int boot = bootCount(context);
        if (boot < 0) return false;
        AtomicStoreFiles.writeText(new File(directory, SESSION_FILE),
                UnlockSessionPolicy.create(token, System.currentTimeMillis(),
                        SystemClock.elapsedRealtime(), boot), UnlockSessionPolicy.MAX_RECORD_BYTES);
        return true;
    }

    static boolean consume(Context context, String token) {
        if (!UnlockSessionPolicy.validToken(token)) return false;
        return AtomicStoreFiles.locked(context, false, directory -> {
            return consumeAuthorization(context, directory, token)
                    && mayUnlock(AtomicStoreFiles.readProperties(context, directory));
        });
    }

    /** Delete before accepting; callers keep configuration loading in the same locked transaction. */
    static boolean consumeAuthorization(Context context, File directory, String token) throws IOException {
        if (!UnlockSessionPolicy.validToken(token)) return false;
        final String record;
        try {
            record = AtomicStoreFiles.readText(new File(directory, SESSION_FILE),
                    UnlockSessionPolicy.MAX_RECORD_BYTES);
        } catch (IOException invalidRecord) {
            invalidate(directory);
            return false;
        }
        UnlockSessionPolicy.Decision decision = UnlockSessionPolicy.inspect(record, token,
                System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootCount(context));
        if (decision == UnlockSessionPolicy.Decision.KEEP_REJECT) return false;
        // Failed durable deletion must never return settings or permit a replay.
        invalidate(directory);
        return decision == UnlockSessionPolicy.Decision.CONSUME;
    }

    static void invalidate(File directory) throws IOException {
        AtomicStoreFiles.delete(new File(directory, SESSION_FILE));
    }

    private static boolean mayUnlock(Properties properties) {
        TemplateStore.StateSnapshot state = new TemplateStore.StateSnapshot(properties);
        return state.enabled && state.lockoutUntil <= System.currentTimeMillis();
    }

    private static int bootCount(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.BOOT_COUNT, -1);
        } catch (RuntimeException unavailable) { return -1; }
    }
}
