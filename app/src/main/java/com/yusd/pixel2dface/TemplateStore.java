package com.yusd.pixel2dface;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Base64;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Properties;

public final class TemplateStore {
    private static final String TAG = "Pixel2DFace";
    private static final String STORE_FILE = "face2d.properties";
    private static final String HEARTBEAT_FILE = "hook.heartbeat";
    private static final String COMPATIBILITY_FILE = "hook.compatibility";
    private static final String UNLOCK_SESSION_FILE = "unlock.session";
    private static final String LEGACY_PREFS_FILE = "face2d.xml";
    private static final Object IO_LOCK = new Object();
    private static final long UNLOCK_SESSION_VALID_MS = 15_000L;

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_TEMPLATE_VERSION = "template_version";
    private static final String KEY_TEMPLATE_COUNT = "template_count";
    private static final String KEY_TEMPLATE_PREFIX = "template_";
    private static final String KEY_CALIBRATED_THRESHOLD = "calibrated_threshold";
    private static final String KEY_CELL_WEIGHTS = "cell_weights";
    private static final String KEY_CELL_LIMITS = "cell_limits";
    private static final String KEY_GEOMETRY_CENTROID = "geometry_centroid";
    private static final String KEY_GEOMETRY_SCALES = "geometry_scales";
    private static final String KEY_GEOMETRY_THRESHOLD = "geometry_threshold";
    private static final String KEY_RECOGNITION_PROFILE = "recognition_profile";
    private static final String KEY_ANIMATION_STYLE = "animation_style";
    private static final String KEY_FAILURES = "failures";
    private static final String KEY_LOCKOUT_UNTIL = "lockout_until";
    private static final String KEY_HOOK_HEARTBEAT = "hook_heartbeat";

    public static final int CURRENT_TEMPLATE_VERSION = 3;
    public static final float DEFAULT_THRESHOLD = 0.33f;
    public static final int RECOGNITION_PROFILE_STRICT = 0;
    public static final int RECOGNITION_PROFILE_BALANCED = 1;
    public static final int RECOGNITION_PROFILE_COMFORT = 2;
    public static final int ANIMATION_STYLE_FACE_ID = 0;
    public static final int ANIMATION_STYLE_DYNAMIC_ISLAND = 1;

    private TemplateStore() {
    }

    public static boolean isEnabled(Context context) {
        return getBoolean(read(context), KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        Properties properties = read(context);
        properties.setProperty(KEY_ENABLED, Boolean.toString(enabled));
        write(context, properties);
    }

    public static boolean isEnrolled(Context context) {
        Properties properties = read(context);
        return getInt(properties, KEY_TEMPLATE_VERSION, 1) == CURRENT_TEMPLATE_VERSION
                && getInt(properties, KEY_TEMPLATE_COUNT, 0) > 0;
    }

    public static boolean hasLegacyTemplates(Context context) {
        Properties properties = read(context);
        return getInt(properties, KEY_TEMPLATE_COUNT, 0) > 0
                && getInt(properties, KEY_TEMPLATE_VERSION, 1) != CURRENT_TEMPLATE_VERSION;
    }

    public static float getCalibratedThreshold(Context context) {
        return clamp(getFloat(read(context), KEY_CALIBRATED_THRESHOLD, DEFAULT_THRESHOLD),
                0.28f, 0.35f);
    }

    public static int getRecognitionProfile(Context context) {
        int profile = getInt(read(context), KEY_RECOGNITION_PROFILE,
                RECOGNITION_PROFILE_BALANCED);
        if (profile == RECOGNITION_PROFILE_STRICT
                || profile == RECOGNITION_PROFILE_COMFORT) {
            return profile;
        }
        return RECOGNITION_PROFILE_BALANCED;
    }

    public static void setRecognitionProfile(Context context, int profile) {
        int safeProfile = profile == RECOGNITION_PROFILE_STRICT
                || profile == RECOGNITION_PROFILE_COMFORT
                ? profile : RECOGNITION_PROFILE_BALANCED;
        Properties properties = read(context);
        properties.setProperty(KEY_RECOGNITION_PROFILE, Integer.toString(safeProfile));
        write(context, properties);
    }

    public static float getRecognitionThreshold(Context context) {
        float profileCap;
        switch (getRecognitionProfile(context)) {
            case RECOGNITION_PROFILE_STRICT:
                profileCap = 0.30f;
                break;
            case RECOGNITION_PROFILE_COMFORT:
                profileCap = 0.35f;
                break;
            default:
                profileCap = 0.33f;
                break;
        }
        return Math.min(getCalibratedThreshold(context), profileCap);
    }

    public static int getAnimationStyle(Context context) {
        int style = getInt(read(context), KEY_ANIMATION_STYLE, ANIMATION_STYLE_FACE_ID);
        return style == ANIMATION_STYLE_DYNAMIC_ISLAND
                ? ANIMATION_STYLE_DYNAMIC_ISLAND : ANIMATION_STYLE_FACE_ID;
    }

    public static void setAnimationStyle(Context context, int style) {
        int safeStyle = style == ANIMATION_STYLE_DYNAMIC_ISLAND
                ? ANIMATION_STYLE_DYNAMIC_ISLAND : ANIMATION_STYLE_FACE_ID;
        Properties properties = read(context);
        properties.setProperty(KEY_ANIMATION_STYLE, Integer.toString(safeStyle));
        write(context, properties);
    }

    public static void saveIdentityModel(Context context, IdentityModel model) {
        Properties properties = read(context);
        int oldCount = getInt(properties, KEY_TEMPLATE_COUNT, 0);
        for (int i = 0; i < oldCount; i++) {
            properties.remove(KEY_TEMPLATE_PREFIX + i);
        }
        properties.setProperty(KEY_ENABLED, Boolean.TRUE.toString());
        properties.setProperty(KEY_TEMPLATE_VERSION,
                Integer.toString(CURRENT_TEMPLATE_VERSION));
        properties.setProperty(KEY_TEMPLATE_COUNT, "1");
        properties.setProperty(KEY_CALIBRATED_THRESHOLD,
                Float.toString(clamp(model.textureThreshold, 0.28f, 0.35f)));
        properties.setProperty(KEY_TEMPLATE_PREFIX + "0", encode(model.textureCentroid));
        properties.setProperty(KEY_CELL_WEIGHTS, encode(model.cellWeights));
        properties.setProperty(KEY_CELL_LIMITS, encode(model.cellLimits));
        properties.setProperty(KEY_GEOMETRY_CENTROID, encode(model.geometryCentroid));
        properties.setProperty(KEY_GEOMETRY_SCALES, encode(model.geometryScales));
        properties.setProperty(KEY_GEOMETRY_THRESHOLD,
                Float.toString(clamp(model.geometryThreshold, 1.0f, 2.5f)));
        write(context, properties);
    }

    public static IdentityModel loadIdentityModel(Context context) {
        Properties properties = read(context);
        if (getInt(properties, KEY_TEMPLATE_VERSION, 1) != CURRENT_TEMPLATE_VERSION) {
            return null;
        }
        try {
            float[] textureCentroid = decodeRequired(properties, KEY_TEMPLATE_PREFIX + "0");
            float[] cellWeights = decodeRequired(properties, KEY_CELL_WEIGHTS);
            float[] cellLimits = decodeRequired(properties, KEY_CELL_LIMITS);
            float[] geometryCentroid = decodeRequired(properties, KEY_GEOMETRY_CENTROID);
            float[] geometryScales = decodeRequired(properties, KEY_GEOMETRY_SCALES);
            float textureThreshold = clamp(getFloat(properties, KEY_CALIBRATED_THRESHOLD,
                    DEFAULT_THRESHOLD), 0.28f, 0.35f);
            float geometryThreshold = clamp(getFloat(properties, KEY_GEOMETRY_THRESHOLD, 1.7f),
                    1.0f, 2.5f);
            return new IdentityModel(textureCentroid, cellWeights, cellLimits,
                    geometryCentroid, geometryScales, textureThreshold, geometryThreshold);
        } catch (RuntimeException error) {
            Log.e(TAG, "Invalid identity model", error);
            return null;
        }
    }

    public static void clearTemplates(Context context) {
        Properties properties = read(context);
        int oldCount = getInt(properties, KEY_TEMPLATE_COUNT, 0);
        for (int i = 0; i < oldCount; i++) {
            properties.remove(KEY_TEMPLATE_PREFIX + i);
        }
        properties.remove(KEY_TEMPLATE_COUNT);
        properties.remove(KEY_TEMPLATE_VERSION);
        properties.remove(KEY_CALIBRATED_THRESHOLD);
        properties.remove(KEY_CELL_WEIGHTS);
        properties.remove(KEY_CELL_LIMITS);
        properties.remove(KEY_GEOMETRY_CENTROID);
        properties.remove(KEY_GEOMETRY_SCALES);
        properties.remove(KEY_GEOMETRY_THRESHOLD);
        properties.remove(KEY_FAILURES);
        properties.remove(KEY_LOCKOUT_UNTIL);
        properties.setProperty(KEY_ENABLED, Boolean.FALSE.toString());
        write(context, properties);
    }

    public static long getLockoutUntil(Context context) {
        return getLong(read(context), KEY_LOCKOUT_UNTIL, 0L);
    }

    public static void recordSuccess(Context context) {
        Properties properties = read(context);
        if (getInt(properties, KEY_FAILURES, 0) == 0
                && getLong(properties, KEY_LOCKOUT_UNTIL, 0L) == 0L) {
            return;
        }
        properties.setProperty(KEY_FAILURES, "0");
        properties.setProperty(KEY_LOCKOUT_UNTIL, "0");
        write(context, properties);
    }

    public static void recordFailure(Context context) {
        Properties properties = read(context);
        int failures = getInt(properties, KEY_FAILURES, 0) + 1;
        if (failures >= 5) {
            properties.setProperty(KEY_FAILURES, "0");
            properties.setProperty(KEY_LOCKOUT_UNTIL,
                    Long.toString(System.currentTimeMillis() + 30_000L));
        } else {
            properties.setProperty(KEY_FAILURES, Integer.toString(failures));
        }
        write(context, properties);
    }

    public static boolean recordHookHeartbeat(Context context) {
        return recordHookHeartbeat(context, null);
    }

    public static boolean recordHookHeartbeat(Context context, String compatibility) {
        synchronized (IO_LOCK) {
            boolean heartbeatWritten = writeSmallTextLocked(
                    new File(context.getFilesDir(), HEARTBEAT_FILE),
                    Long.toString(System.currentTimeMillis()), "hook heartbeat");
            if (compatibility != null && !compatibility.trim().isEmpty()) {
                writeSmallTextLocked(new File(context.getFilesDir(), COMPATIBILITY_FILE),
                        compatibility.trim(), "compatibility report");
            }
            return heartbeatWritten;
        }
    }

    public static long getHookHeartbeat(Context context) {
        synchronized (IO_LOCK) {
            File file = new File(context.getFilesDir(), HEARTBEAT_FILE);
            if (file.exists()) {
                try (FileInputStream input = new AtomicFile(file).openRead()) {
                    byte[] value = new byte[32];
                    int length = input.read(value);
                    if (length > 0) {
                        return Long.parseLong(new String(value, 0, length,
                                StandardCharsets.UTF_8).trim());
                    }
                } catch (Exception error) {
                    Log.w(TAG, "Unable to read hook heartbeat", error);
                }
            }
        }
        // Keep compatibility with early beta data where the heartbeat shared the
        // much larger template properties file.
        return getLong(read(context), KEY_HOOK_HEARTBEAT, 0L);
    }

    public static String getHookCompatibility(Context context) {
        synchronized (IO_LOCK) {
            File file = new File(context.getFilesDir(), COMPATIBILITY_FILE);
            if (!file.exists()) {
                return "";
            }
            try (FileInputStream input = new AtomicFile(file).openRead()) {
                byte[] value = new byte[2048];
                int length = input.read(value);
                return length > 0 ? new String(value, 0, length,
                        StandardCharsets.UTF_8).trim() : "";
            } catch (Exception error) {
                Log.w(TAG, "Unable to read compatibility report", error);
                return "";
            }
        }
    }

    public static boolean authorizeUnlockSession(Context context, String token) {
        if (!isValidSessionToken(token)) {
            return false;
        }
        String value = token + "\n" + System.currentTimeMillis() + "\n"
                + android.os.SystemClock.elapsedRealtime();
        synchronized (IO_LOCK) {
            return writeSmallTextLocked(new File(context.getFilesDir(), UNLOCK_SESSION_FILE),
                    value, "unlock session");
        }
    }

    public static boolean consumeAuthorizedUnlockSession(Context context, String token) {
        if (!isValidSessionToken(token)) {
            return false;
        }
        synchronized (IO_LOCK) {
            File file = new File(context.getFilesDir(), UNLOCK_SESSION_FILE);
            AtomicFile atomicFile = new AtomicFile(file);
            if (!file.exists()) {
                return false;
            }
            try (FileInputStream input = atomicFile.openRead()) {
                byte[] value = new byte[512];
                int length = input.read(value);
                String[] parts = length > 0
                        ? new String(value, 0, length, StandardCharsets.UTF_8).split("\\n")
                        : new String[0];
                if (parts.length != 3) {
                    return false;
                }
                long wallAge = System.currentTimeMillis() - Long.parseLong(parts[1]);
                long elapsedAge = android.os.SystemClock.elapsedRealtime()
                        - Long.parseLong(parts[2]);
                boolean sameToken = MessageDigest.isEqual(
                        token.getBytes(StandardCharsets.UTF_8),
                        parts[0].getBytes(StandardCharsets.UTF_8));
                return sameToken && wallAge >= -2_000L && wallAge <= UNLOCK_SESSION_VALID_MS
                        && elapsedAge >= 0L && elapsedAge <= UNLOCK_SESSION_VALID_MS;
            } catch (Exception error) {
                Log.w(TAG, "Unable to consume unlock session", error);
                return false;
            } finally {
                // A launch authorization is one-shot, whether validation succeeded or failed.
                atomicFile.delete();
            }
        }
    }

    private static Properties read(Context context) {
        synchronized (IO_LOCK) {
            File file = new File(context.getFilesDir(), STORE_FILE);
            if (!file.exists()) {
                Properties migrated = migrateLegacy(context);
                if (!migrated.isEmpty()) {
                    writeLocked(file, migrated);
                    Log.i(TAG, "Migrated legacy face templates to atomic storage");
                    return migrated;
                }
            }
            Properties properties = new Properties();
            if (!file.exists()) {
                return properties;
            }
            try (FileInputStream input = new AtomicFile(file).openRead()) {
                properties.load(input);
            } catch (Exception error) {
                Log.e(TAG, "Unable to read face template store", error);
            }
            return properties;
        }
    }

    private static boolean write(Context context, Properties properties) {
        synchronized (IO_LOCK) {
            return writeLocked(new File(context.getFilesDir(), STORE_FILE), properties);
        }
    }

    private static boolean writeLocked(File file, Properties properties) {
        AtomicFile atomicFile = new AtomicFile(file);
        FileOutputStream output = null;
        try {
            output = atomicFile.startWrite();
            properties.store(output, "Pixel 2D Face Unlock");
            atomicFile.finishWrite(output);
            return true;
        } catch (Exception error) {
            if (output != null) {
                atomicFile.failWrite(output);
            }
            Log.e(TAG, "Unable to write face template store", error);
            return false;
        }
    }

    private static boolean writeSmallTextLocked(File file, String value, String label) {
        AtomicFile atomicFile = new AtomicFile(file);
        FileOutputStream output = null;
        try {
            output = atomicFile.startWrite();
            output.write(value.getBytes(StandardCharsets.UTF_8));
            atomicFile.finishWrite(output);
            return true;
        } catch (Exception error) {
            if (output != null) {
                atomicFile.failWrite(output);
            }
            Log.e(TAG, "Unable to write " + label, error);
            return false;
        }
    }

    private static Properties migrateLegacy(Context context) {
        Properties properties = new Properties();
        File legacy = new File(new File(context.getApplicationInfo().dataDir,
                "shared_prefs"), LEGACY_PREFS_FILE);
        if (!legacy.exists()) {
            return properties;
        }
        try (FileInputStream input = new FileInputStream(legacy)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(input, "UTF-8");
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event != XmlPullParser.START_TAG) {
                    continue;
                }
                String tag = parser.getName();
                String name = parser.getAttributeValue(null, "name");
                if (name == null) {
                    continue;
                }
                if ("string".equals(tag)) {
                    properties.setProperty(name, parser.nextText());
                } else if ("boolean".equals(tag) || "int".equals(tag)
                        || "long".equals(tag) || "float".equals(tag)) {
                    String value = parser.getAttributeValue(null, "value");
                    if (value != null) {
                        properties.setProperty(name, value);
                    }
                }
            }
        } catch (Exception error) {
            Log.e(TAG, "Unable to migrate legacy face templates", error);
            properties.clear();
        }
        return properties;
    }

    private static boolean getBoolean(Properties properties, String key, boolean fallback) {
        return Boolean.parseBoolean(properties.getProperty(key, Boolean.toString(fallback)));
    }

    private static boolean isValidSessionToken(String token) {
        return token != null && token.length() >= 32 && token.length() <= 160
                && token.indexOf('\n') < 0 && token.indexOf('\r') < 0;
    }

    private static int getInt(Properties properties, String key, int fallback) {
        try {
            return Integer.parseInt(properties.getProperty(key));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static long getLong(Properties properties, String key, long fallback) {
        try {
            return Long.parseLong(properties.getProperty(key));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static float getFloat(Properties properties, String key, float fallback) {
        try {
            return Float.parseFloat(properties.getProperty(key));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String encode(float[] values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) {
            buffer.putFloat(value);
        }
        return Base64.encodeToString(buffer.array(), Base64.NO_WRAP);
    }

    private static float[] decode(String encoded) {
        byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] values = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < values.length; i++) {
            values[i] = buffer.getFloat();
        }
        return values;
    }

    private static float[] decodeRequired(Properties properties, String key) {
        String encoded = properties.getProperty(key);
        if (encoded == null || encoded.isEmpty()) {
            throw new IllegalArgumentException("Missing model field: " + key);
        }
        return decode(encoded);
    }
}
