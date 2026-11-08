package com.yusd.pixel2dface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Properties;

/** The v3 disk format. Decoding is bounded and never repairs a biometric field. */
final class IdentityModelCodec {
    static final int VERSION = 3;
    private static final String CHECKSUM = "identity_checksum";
    private static final String[] MODEL_FIELDS = {
            "template_version", "template_count", "template_0", "calibrated_threshold",
            "cell_weights", "cell_limits", "geometry_centroid", "geometry_scales",
            "geometry_threshold"
    };

    private IdentityModelCodec() { }

    static IdentityModel decode(Properties properties) {
        if (StorePolicy.integer(properties, "template_version", 1) != VERSION
                || StorePolicy.integer(properties, "template_count", 0) != 1) {
            return null;
        }
        try {
            // Existing v3 enrollments have no checksum and retain their original bytes.
            String checksum = properties.getProperty(CHECKSUM);
            if (checksum != null && !MessageDigest.isEqual(
                    checksum.getBytes(StandardCharsets.US_ASCII),
                    checksum(properties).getBytes(StandardCharsets.US_ASCII))) {
                return null;
            }
            return new IdentityModel(
                    vector(properties, "template_0", LbpDescriptor.LENGTH),
                    vector(properties, "cell_weights", IdentityModel.CELL_COUNT),
                    vector(properties, "cell_limits", IdentityModel.CELL_COUNT),
                    vector(properties, "geometry_centroid", FaceGeometry.LENGTH),
                    vector(properties, "geometry_scales", FaceGeometry.LENGTH),
                    StorePolicy.finiteFloat(properties, "calibrated_threshold", Float.NaN),
                    StorePolicy.finiteFloat(properties, "geometry_threshold", Float.NaN));
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    static boolean encodeInto(Properties properties, IdentityModel source) {
        if (source == null) return false;
        final IdentityModel model;
        try {
            // Model arrays are package visible. Validate a defensive snapshot before saving.
            model = new IdentityModel(source.textureCentroid, source.cellWeights,
                    source.cellLimits, source.geometryCentroid, source.geometryScales,
                    source.textureThreshold, source.geometryThreshold);
        } catch (IllegalArgumentException error) {
            return false;
        }
        clear(properties);
        properties.setProperty("template_version", Integer.toString(VERSION));
        properties.setProperty("template_count", "1");
        properties.setProperty("template_0", vector(model.textureCentroid));
        properties.setProperty("calibrated_threshold", Float.toString(model.textureThreshold));
        properties.setProperty("cell_weights", vector(model.cellWeights));
        properties.setProperty("cell_limits", vector(model.cellLimits));
        properties.setProperty("geometry_centroid", vector(model.geometryCentroid));
        properties.setProperty("geometry_scales", vector(model.geometryScales));
        properties.setProperty("geometry_threshold", Float.toString(model.geometryThreshold));
        properties.setProperty(CHECKSUM, checksum(properties));
        return true;
    }

    static void clear(Properties properties) {
        // Never loop over an untrusted count: stale or malformed slots are removed as well.
        properties.keySet().removeIf(key -> key instanceof String
                && (((String) key).startsWith("template_")
                || ((String) key).startsWith("geometry_")));
        properties.remove("calibrated_threshold");
        properties.remove("cell_weights");
        properties.remove("cell_limits");
        properties.remove(CHECKSUM);
    }

    static String vector(float[] values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) buffer.putFloat(value);
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    static float[] vector(Properties properties, String key, int length) {
        String encoded = properties.getProperty(key);
        int byteLength = length * Float.BYTES;
        int encodedLength = ((byteLength + 2) / 3) * 4;
        // Validate before allocation; Android's original NO_WRAP encoder emitted this size.
        if (encoded == null || encoded.length() != encodedLength) {
            throw new IllegalArgumentException("Invalid model field size");
        }
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length != byteLength) {
            throw new IllegalArgumentException("Invalid model field dimensions");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] values = new float[length];
        for (int i = 0; i < length; i++) values[i] = buffer.getFloat();
        return values;
    }

    static String fingerprint(Properties properties) { return checksum(properties); }

    private static String checksum(Properties properties) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : MODEL_FIELDS) {
                digest.update(field.getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) 0);
                String value = properties.getProperty(field, "");
                digest.update(value.getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) 0);
            }
            return Base64.getEncoder().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
