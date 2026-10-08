package com.yusd.pixel2dface;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Properties;

/** Version dispatch is explicit: corrupt/unknown neural templates never fall back to LBP. */
final class TemplateCodec {
    static final int VERSION = 4;
    private static final String[] FIELDS = {"template_version", "template_count",
            "embedding_model", "embedding_centroid", "calibrated_threshold"};

    private TemplateCodec() { }

    static boolean supports(int version) { return version == 3 || version == VERSION; }

    static IdentityTemplate decode(Properties properties) {
        int version = StorePolicy.integer(properties, "template_version", -1);
        if (version == 3) return IdentityModelCodec.decode(properties);
        if (version != VERSION || StorePolicy.integer(properties, "template_count", 0) != 1
                || !EmbeddingIdentity.MODEL_ID.equals(properties.getProperty("embedding_model"))) return null;
        String checksum = properties.getProperty("identity_checksum");
        if (checksum == null || !MessageDigest.isEqual(checksum.getBytes(StandardCharsets.US_ASCII),
                fingerprint(properties).getBytes(StandardCharsets.US_ASCII))) return null;
        try {
            return new EmbeddingIdentity(IdentityModelCodec.vector(properties, "embedding_centroid",
                    EmbeddingIdentity.DIMENSIONS), StorePolicy.finiteFloat(properties,
                    "calibrated_threshold", Float.NaN));
        } catch (IllegalArgumentException invalid) { return null; }
    }

    static boolean encodeInto(Properties target, IdentityTemplate source) {
        if (source == null) return false;
        // Prepare independently, so failed validation cannot partially clear a live template.
        Properties prepared = new Properties();
        if (source instanceof IdentityModel) {
            if (!IdentityModelCodec.encodeInto(prepared, (IdentityModel) source)) return false;
        } else if (source instanceof EmbeddingIdentity) {
            EmbeddingIdentity model = (EmbeddingIdentity) source;
            prepared.setProperty("template_version", Integer.toString(VERSION));
            prepared.setProperty("template_count", "1");
            prepared.setProperty("embedding_model", EmbeddingIdentity.MODEL_ID);
            prepared.setProperty("embedding_centroid", IdentityModelCodec.vector(model.snapshot()));
            prepared.setProperty("calibrated_threshold", Float.toString(model.maximumThreshold()));
            prepared.setProperty("identity_checksum", fingerprint(prepared));
        } else return false;
        clear(target);
        target.putAll(prepared);
        return true;
    }

    static void clear(Properties properties) {
        IdentityModelCodec.clear(properties);
        properties.keySet().removeIf(key -> key instanceof String
                && ((String) key).startsWith("embedding_"));
    }

    static String fingerprint(Properties properties) {
        if (StorePolicy.integer(properties, "template_version", -1) == 3) {
            return IdentityModelCodec.fingerprint(properties);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String key : FIELDS) {
                digest.update(key.getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) 0);
                digest.update(properties.getProperty(key, "").getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) 0);
            }
            return Base64.getEncoder().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
