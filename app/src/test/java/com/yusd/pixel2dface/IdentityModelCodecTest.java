package com.yusd.pixel2dface;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Base64;
import java.util.Properties;

import org.junit.Test;

public final class IdentityModelCodecTest {
    @Test public void originalV3WithoutChecksumPreservesEveryFeatureAndThreshold() {
        Properties properties = encodedModel();
        properties.remove("identity_checksum");
        IdentityModel decoded = IdentityModelCodec.decode(properties);
        assertNotNull(decoded);
        assertArrayEquals(model().textureCentroid, decoded.textureCentroid, 0f);
        assertArrayEquals(model().cellWeights, decoded.cellWeights, 0f);
        assertArrayEquals(model().cellLimits, decoded.cellLimits, 0f);
        assertArrayEquals(model().geometryCentroid, decoded.geometryCentroid, 0f);
        assertArrayEquals(model().geometryScales, decoded.geometryScales, 0f);
        assertEquals(0.29f, decoded.textureThreshold, 0f);
        assertEquals(1.7f, decoded.geometryThreshold, 0f);
        assertTrue(decoded.compare(model().textureCentroid, model().geometryCentroid, 0.29f).accepted);
    }

    @Test public void checksumRejectsOtherwiseValidModelMutation() {
        Properties properties = encodedModel();
        properties.setProperty("calibrated_threshold", "0.35");
        assertNull(IdentityModelCodec.decode(properties));
    }

    @Test public void unknownVersionRemainsUntouchedAndRequiresNewEnrollment() {
        Properties properties = encodedModel();
        properties.setProperty("template_version", "99");
        Properties before = copy(properties);
        assertNull(IdentityModelCodec.decode(properties));
        assertEquals(before, properties);
    }

    @Test public void oversizedTruncatedNonFiniteAndWrongDimensionsAreRejected() {
        for (String replacement : new String[]{"", "AAAA", "?".repeat(66_904),
                "A".repeat(1_000_000), encodedVector(new float[FaceGeometry.LENGTH + 1])}) {
            Properties properties = encodedModel();
            properties.remove("identity_checksum");
            properties.setProperty("geometry_centroid", replacement);
            assertNull(IdentityModelCodec.decode(properties));
        }
        Properties properties = encodedModel();
        properties.remove("identity_checksum");
        float[] invalid = model().geometryCentroid.clone();
        invalid[0] = Float.NaN;
        properties.setProperty("geometry_centroid", encodedVector(invalid));
        assertNull(IdentityModelCodec.decode(properties));
        for (String threshold : new String[]{"NaN", "Infinity", "-Infinity", "0.36", "garbage"}) {
            properties = encodedModel();
            properties.remove("identity_checksum");
            properties.setProperty("calibrated_threshold", threshold);
            assertNull(IdentityModelCodec.decode(properties));
        }
    }

    @Test public void invalidMutatedModelCannotReplaceExistingEnrollment() {
        Properties properties = encodedModel();
        Properties before = copy(properties);
        IdentityModel invalid = model();
        invalid.geometryScales[0] = Float.POSITIVE_INFINITY;
        assertFalse(IdentityModelCodec.encodeInto(properties, invalid));
        assertEquals(before, properties);
        assertFalse(IdentityModelCodec.encodeInto(properties, null));
        assertEquals(before, properties);
    }

    @Test public void clearRemovesUnboundedAndStaleSlotsPreservingPreferences() {
        Properties properties = encodedModel();
        properties.setProperty("template_count", Integer.toString(Integer.MAX_VALUE));
        properties.setProperty("template_999999999", "old synthetic data");
        properties.setProperty("animation_style", "1");
        properties.setProperty("recognition_profile", "0");
        IdentityModelCodec.clear(properties);
        assertNull(IdentityModelCodec.decode(properties));
        assertFalse(properties.stringPropertyNames().stream().anyMatch(name -> name.startsWith("template_")));
        assertEquals("1", properties.getProperty("animation_style"));
        assertEquals("0", properties.getProperty("recognition_profile"));
    }

    static Properties encodedModel() {
        Properties properties = new Properties();
        assertTrue(IdentityModelCodec.encodeInto(properties, model()));
        return properties;
    }

    static IdentityModel model() {
        float[] texture = new float[LbpDescriptor.LENGTH];
        for (int cell = 0; cell < IdentityModel.CELL_COUNT; cell++) texture[cell * 256 + 4] = 1f;
        float[] weights = new float[IdentityModel.CELL_COUNT];
        float[] limits = new float[IdentityModel.CELL_COUNT];
        Arrays.fill(weights, 1f);
        Arrays.fill(limits, 0.4f);
        return new IdentityModel(texture, weights, limits,
                new float[]{0.72f, 0.40f, 0.22f, 0.42f, 0.30f, 0.21f, 0f},
                new float[]{0.04f, 0.02f, 0.025f, 0.035f, 0.028f, 0.03f, 0.022f},
                0.29f, 1.7f);
    }

    private static String encodedVector(float[] values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) buffer.putFloat(value);
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    private static Properties copy(Properties source) {
        Properties result = new Properties();
        result.putAll(source);
        return result;
    }
}
