package com.yusd.pixel2dface;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.Properties;

/**
 * App-facing storage facade. Biometric serialization, file transactions, security
 * policy, legacy migration, and transient hook state each have a separate owner.
 */
public final class TemplateStore {
    public static final int CURRENT_TEMPLATE_VERSION = TemplateCodec.VERSION;
    public static final float DEFAULT_THRESHOLD = 0.33f;
    public static final int RECOGNITION_PROFILE_STRICT = 0;
    public static final int RECOGNITION_PROFILE_BALANCED = 1;
    public static final int RECOGNITION_PROFILE_COMFORT = 2;
    public static final int ANIMATION_STYLE_FACE_ID = 0;
    public static final int ANIMATION_STYLE_DYNAMIC_ISLAND = 1;
    private static final String REVISION = "store_revision";

    private TemplateStore() { }

    public static boolean isEnabled(Context context) { return readState(context).enabled; }
    public static boolean isEnrolled(Context context) { return readState(context).enrolled; }

    public static boolean hasLegacyTemplates(Context context) {
        return read(context, false, properties ->
                StorePolicy.integer(properties, "template_count", 0) > 0
                && !TemplateCodec.supports(StorePolicy.integer(properties, "template_version", 1)));
    }

    public static float getCalibratedThreshold(Context context) {
        return read(context, DEFAULT_THRESHOLD, StorePolicy::calibratedThreshold);
    }

    public static float getRecognitionThreshold(Context context) {
        return read(context, DEFAULT_THRESHOLD, StorePolicy::recognitionThreshold);
    }

    public static int getRecognitionProfile(Context context) {
        return read(context, RECOGNITION_PROFILE_BALANCED, StorePolicy::profile);
    }

    public static int getAnimationStyle(Context context) {
        return read(context, ANIMATION_STYLE_FACE_ID, StorePolicy::animation);
    }

    public static void setEnabled(Context context, boolean enabled) { trySetEnabled(context, enabled); }

    public static boolean trySetEnabled(Context context, boolean enabled) {
        return mutate(context, properties -> {
            if (enabled && (TemplateCodec.decode(properties) == null
                    || !StorePolicy.validFailureState(properties))) return false;
            properties.setProperty("enabled", Boolean.toString(enabled));
            return true;
        }, true);
    }

    public static void setRecognitionProfile(Context context, int profile) {
        trySetRecognitionProfile(context, profile);
    }

    public static boolean trySetRecognitionProfile(Context context, int profile) {
        return mutate(context, properties -> {
            properties.setProperty("recognition_profile",
                    Integer.toString(StorePolicy.safeProfile(profile)));
            return true;
        }, true);
    }

    public static void setAnimationStyle(Context context, int style) { trySetAnimationStyle(context, style); }

    public static boolean trySetAnimationStyle(Context context, int style) {
        return mutate(context, properties -> {
            properties.setProperty("animation_style", Integer.toString(StorePolicy.safeAnimation(style)));
            return true;
        }, true);
    }

    public static boolean saveIdentityModel(Context context, IdentityTemplate model) {
        boolean saved = AtomicStoreFiles.locked(context, false, directory -> {
            Properties properties = AtomicStoreFiles.readProperties(context, directory);
            if (!TemplateCodec.encodeInto(properties, model)) return false;
            properties.setProperty("enabled", "true");
            StorePolicy.resetFailures(properties);
            // Explicit re-enrollment may repair invalid security metadata, preserving
            // independent preferences. Ordinary settings edits cannot do this.
            if (!validRevision(properties)) properties.remove(REVISION);
            if (!bumpRevision(properties)) return false;
            HookRuntimeStore.invalidate(directory);
            AtomicStoreFiles.writeProperties(directory, properties);
            return true;
        });
        if (saved) notifyConfigurationChanged(context);
        return saved;
    }

    public static IdentityTemplate loadIdentityModel(Context context) {
        return read(context, null, TemplateCodec::decode);
    }

    public static void clearTemplates(Context context) { tryClearTemplates(context); }

    public static boolean tryClearTemplates(Context context) {
        boolean cleared = AtomicStoreFiles.locked(context, false, directory -> {
            Properties properties = AtomicStoreFiles.readProperties(context, directory);
            TemplateCodec.clear(properties);
            properties.remove("failures");
            properties.remove("lockout_until");
            properties.setProperty("enabled", "false");
            if (!validRevision(properties)) properties.remove(REVISION);
            if (!bumpRevision(properties)) return false;
            // Legacy SharedPreferences can retain a second biometric copy after migration.
            // Delete it before committing the canonical clear so a failed cleanup cannot
            // report failure after a silent configuration change.
            LegacyStoreMigration.delete(context);
            HookRuntimeStore.invalidate(directory);
            AtomicStoreFiles.writeProperties(directory, properties);
            return true;
        });
        if (cleared) notifyConfigurationChanged(context);
        return cleared;
    }

    static StateSnapshot readState(Context context) {
        return read(context, StateSnapshot.unavailable(), StateSnapshot::new);
    }

    static RecognitionSettings loadRecognitionSettings(Context context) {
        RecognitionSettings fallback = new RecognitionSettings(null, DEFAULT_THRESHOLD,
                StateSnapshot.unavailable(), "");
        return read(context, fallback, TemplateStore::recognitionSettings);
    }

    /** Configuration and launch grant come from one read under the same cross-process lock. */
    static UnlockPreparation prepareUnlockSession(Context context, String token) {
        UnlockPreparation fallback = new UnlockPreparation(StateSnapshot.unavailable(), false);
        if (!UnlockSessionPolicy.validToken(token)) return fallback;
        return AtomicStoreFiles.locked(context, fallback, directory -> {
            StateSnapshot state = new StateSnapshot(AtomicStoreFiles.readProperties(context, directory));
            boolean authorized = state.enabled && state.lockoutUntil <= System.currentTimeMillis()
                    && HookRuntimeStore.writeAuthorization(context, directory, token);
            return new UnlockPreparation(state, authorized);
        });
    }

    /** A settings snapshot is returned only after one-use consumption and current policy validation. */
    static RecognitionSettings consumeAuthorizedRecognitionSettings(Context context, String token) {
        if (!UnlockSessionPolicy.validToken(token)) return null;
        return AtomicStoreFiles.locked(context, null, directory -> {
            if (!HookRuntimeStore.consumeAuthorization(context, directory, token)) return null;
            RecognitionSettings settings = recognitionSettings(AtomicStoreFiles.readProperties(context, directory));
            return settings.state.enabled && settings.state.lockoutUntil <= System.currentTimeMillis()
                    ? settings : null;
        });
    }

    private static RecognitionSettings recognitionSettings(Properties properties) {
        IdentityTemplate model = TemplateCodec.decode(properties);
        return new RecognitionSettings(model, StorePolicy.recognitionThreshold(properties),
                new StateSnapshot(properties, model), TemplateCodec.fingerprint(properties));
    }

    static final class UnlockPreparation {
        final StateSnapshot state;
        final boolean authorized;

        UnlockPreparation(StateSnapshot state, boolean authorized) {
            this.state = state;
            this.authorized = authorized;
        }
    }

    static boolean isRecognitionSettingsCurrent(Context context, RecognitionSettings settings) {
        if (settings == null || settings.model == null || !settings.state.storageAvailable) return false;
        return read(context, false, properties -> settingsCurrent(properties, settings));
    }

    static boolean validateAndRecordSuccess(Context context, RecognitionSettings settings) {
        if (settings == null || settings.model == null || !settings.state.storageAvailable) return false;
        return AtomicStoreFiles.locked(context, false, directory -> {
            Properties properties = AtomicStoreFiles.readProperties(context, directory);
            if (!settingsCurrent(properties, settings)) return false;
            Properties original = new Properties();
            original.putAll(properties);
            StorePolicy.resetFailures(properties);
            // Failure counters do not change the identity/configuration revision. SystemUI
            // verifies that same revision once more before accepting the result broadcast.
            if (!original.equals(properties)) AtomicStoreFiles.writeProperties(directory, properties);
            return true;
        });
    }

    private static boolean settingsCurrent(Properties properties, RecognitionSettings settings) {
        StateSnapshot state = new StateSnapshot(properties);
        return state.storageAvailable && state.enabled && state.enrolled
                && state.lockoutUntil <= System.currentTimeMillis()
                && state.revision == settings.state.revision
                && TemplateCodec.fingerprint(properties).equals(settings.fingerprint);
    }

    static final class StateSnapshot {
        final boolean enabled;
        final boolean enrolled;
        final long lockoutUntil;
        final int animationStyle;
        final int recognitionProfile;
        final boolean legacyTemplates;
        final boolean storageAvailable;
        final boolean neuralTemplate;
        final long revision;

        StateSnapshot(Properties properties) { this(properties, TemplateCodec.decode(properties)); }

        private StateSnapshot(Properties properties, IdentityTemplate model) {
            boolean claimsCurrentModel = TemplateCodec.supports(StorePolicy.integer(properties, "template_version", 1))
                    && StorePolicy.integer(properties, "template_count", 0) != 0;
            storageAvailable = StorePolicy.validFailureState(properties) && validRevision(properties)
                    && (!claimsCurrentModel || model != null);
            enrolled = model != null;
            neuralTemplate = model instanceof EmbeddingIdentity;
            enabled = storageAvailable && enrolled && StorePolicy.enabled(properties);
            lockoutUntil = storageAvailable ? StorePolicy.lockoutUntil(properties) : Long.MAX_VALUE;
            animationStyle = StorePolicy.animation(properties);
            recognitionProfile = StorePolicy.profile(properties);
            legacyTemplates = StorePolicy.integer(properties, "template_count", 0) > 0
                    && !TemplateCodec.supports(StorePolicy.integer(properties, "template_version", 1));
            revision = StorePolicy.longValue(properties, REVISION, 0L);
        }

        private StateSnapshot() {
            enabled = false;
            enrolled = false;
            neuralTemplate = false;
            lockoutUntil = Long.MAX_VALUE;
            animationStyle = ANIMATION_STYLE_FACE_ID;
            recognitionProfile = RECOGNITION_PROFILE_BALANCED;
            legacyTemplates = false;
            storageAvailable = false;
            revision = -1L;
        }

        static StateSnapshot unavailable() { return new StateSnapshot(); }
    }

    static final class RecognitionSettings {
        final IdentityTemplate model;
        final float threshold;
        final StateSnapshot state;
        private final String fingerprint;

        RecognitionSettings(IdentityTemplate model, float threshold, StateSnapshot state) {
            this(model, threshold, state, "");
        }

        private RecognitionSettings(IdentityTemplate model, float threshold, StateSnapshot state,
                String fingerprint) {
            this.model = model;
            this.threshold = threshold;
            this.state = state;
            this.fingerprint = fingerprint;
        }
    }

    public static long getLockoutUntil(Context context) { return readState(context).lockoutUntil; }
    public static void recordSuccess(Context context) { tryRecordSuccess(context); }
    public static void recordFailure(Context context) { tryRecordFailure(context); }

    public static boolean tryRecordSuccess(Context context) {
        return AtomicStoreFiles.locked(context, false, directory -> {
            Properties properties = AtomicStoreFiles.readProperties(context, directory);
            if (!validRevision(properties) || !StorePolicy.validFailureState(properties)) return false;
            Properties original = new Properties();
            original.putAll(properties);
            StorePolicy.resetFailures(properties);
            if (!original.equals(properties)) AtomicStoreFiles.writeProperties(directory, properties);
            return true;
        });
    }

    public static boolean tryRecordFailure(Context context) {
        return AtomicStoreFiles.locked(context, false, directory -> {
            Properties properties = AtomicStoreFiles.readProperties(context, directory);
            if (!validRevision(properties)) return false;
            Properties original = new Properties();
            original.putAll(properties);
            long previousLockout = StorePolicy.lockoutUntil(properties);
            if (!StorePolicy.recordFailure(properties, System.currentTimeMillis())) return false;
            if (original.equals(properties)) return true;
            if (StorePolicy.lockoutUntil(properties) != previousLockout
                    && !bumpRevision(properties)) return false;
            AtomicStoreFiles.writeProperties(directory, properties);
            return true;
        });
    }

    public static boolean recordHookHeartbeat(Context context) { return HookRuntimeStore.heartbeat(context, null); }
    public static boolean recordHookHeartbeat(Context context, String compatibility) {
        return HookRuntimeStore.heartbeat(context, compatibility);
    }
    public static long getHookHeartbeat(Context context) { return HookRuntimeStore.heartbeat(context); }
    public static String getHookCompatibility(Context context) { return HookRuntimeStore.compatibility(context); }
    public static boolean authorizeUnlockSession(Context context, String token) {
        return HookRuntimeStore.authorize(context, token);
    }
    public static boolean consumeAuthorizedUnlockSession(Context context, String token) {
        return HookRuntimeStore.consume(context, token);
    }

    static boolean validRevision(Properties properties) {
        String value = properties.getProperty(REVISION);
        if (value == null) return true;
        try { return Long.parseLong(value) >= 0L; }
        catch (NumberFormatException invalid) { return false; }
    }

    private static boolean bumpRevision(Properties properties) {
        if (!validRevision(properties)) return false;
        long previous = StorePolicy.longValue(properties, REVISION, 0L);
        if (previous == Long.MAX_VALUE) return false;
        properties.setProperty(REVISION, Long.toString(previous + 1L));
        return true;
    }

    private interface Reader<T> { T read(Properties properties); }
    private interface Change { boolean apply(Properties properties); }
    private enum Mutation { FAILED, UNCHANGED, COMMITTED }

    private static <T> T read(Context context, T fallback, Reader<T> reader) {
        return AtomicStoreFiles.locked(context, fallback,
                directory -> reader.read(AtomicStoreFiles.readProperties(context, directory)));
    }

    private static boolean mutate(Context context, Change change, boolean revokeGrant) {
        Mutation result = AtomicStoreFiles.locked(context, Mutation.FAILED, directory -> {
            Properties properties = AtomicStoreFiles.readProperties(context, directory);
            Properties original = new Properties();
            original.putAll(properties);
            if (!validRevision(properties) || !change.apply(properties)) return Mutation.FAILED;
            if (original.equals(properties)) return Mutation.UNCHANGED;
            if (!bumpRevision(properties)) return Mutation.FAILED;
            if (revokeGrant) HookRuntimeStore.invalidate(directory);
            AtomicStoreFiles.writeProperties(directory, properties);
            return Mutation.COMMITTED;
        });
        if (result == Mutation.COMMITTED && revokeGrant) notifyConfigurationChanged(context);
        return result != Mutation.FAILED;
    }

    private static void notifyConfigurationChanged(Context context) {
        // This is a revocation signal only. It carries no token, features, or authorization.
        // Dispatch after releasing the file lock so a receiver can safely read fresh state.
        try {
            context.sendBroadcast(new Intent(Constants.ACTION_CONFIGURATION_CHANGED)
                    .setPackage(Constants.SYSTEM_UI_PACKAGE));
        } catch (RuntimeException error) {
            Log.w("Pixel2DFace", "Unable to publish configuration revocation ("
                    + error.getClass().getSimpleName() + ")");
        }
    }
}
