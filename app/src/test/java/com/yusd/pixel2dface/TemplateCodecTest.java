package com.yusd.pixel2dface;

import static org.junit.Assert.*;
import java.util.Properties;
import org.junit.Test;

public final class TemplateCodecTest {
    static Properties neural() {
        Properties p = new Properties();
        assertTrue(TemplateCodec.encodeInto(p, new EmbeddingIdentity(EmbeddingIdentityTest.vector(0), .24f)));
        return p;
    }
    @Test public void versionFourRoundTripAndLegacyCompatibility() {
        Properties p = neural();
        assertTrue(TemplateCodec.decode(p).evaluate(EmbeddingIdentityTest.vector(0), null, .24f).accepted);
        assertTrue(TemplateCodec.supports(3));
        assertTrue(TemplateCodec.supports(4));
        assertFalse(TemplateCodec.supports(5));
        assertTrue(TemplateCodec.encodeInto(p, IdentityModelCodecTest.model()));
        assertFalse(p.containsKey("embedding_model"));
        assertTrue(TemplateCodec.decode(p) instanceof IdentityModel);
        p.remove("identity_checksum");
        assertTrue(TemplateCodec.decode(p) instanceof IdentityModel);
    }
    @Test public void corruptedVersionFourNeverFallsBackToLegacy() {
        for (String field : new String[]{"identity_checksum", "embedding_model", "embedding_centroid",
                "template_count", "calibrated_threshold", "template_version"}) {
            Properties p = neural();
            p.remove(field);
            assertNull(field, TemplateCodec.decode(p));
            p = neural(); p.setProperty(field, "invalid");
            assertNull(field, TemplateCodec.decode(p));
        }
        Properties p = neural(); p.setProperty("embedding_model", "another-model");
        p.setProperty("identity_checksum", TemplateCodec.fingerprint(p));
        assertNull(TemplateCodec.decode(p));
    }
    @Test public void migrationRemovesAllOldBiometricFieldsAndPreservesSettings() {
        Properties p = new Properties();
        IdentityModelCodec.encodeInto(p, IdentityModelCodecTest.model());
        p.setProperty("recognition_profile", "0");
        p.setProperty("template_999", "stale");
        TemplateCodec.encodeInto(p, new EmbeddingIdentity(EmbeddingIdentityTest.vector(0), .24f));
        assertFalse(p.containsKey("template_0"));
        assertFalse(p.containsKey("template_999"));
        assertFalse(p.containsKey("geometry_centroid"));
        assertEquals("0", p.getProperty("recognition_profile"));
        assertEquals(.22f, StorePolicy.recognitionThreshold(p), .0001f);
        TemplateCodec.clear(p);
        assertEquals(1, p.size());
    }
    @Test public void neuralFingerprintBindsModelVectorAndThreshold() {
        Properties p = neural();
        String old = TemplateCodec.fingerprint(p);
        p.setProperty("calibrated_threshold", "0.25");
        assertNotEquals(old, TemplateCodec.fingerprint(p));
        assertNull(TemplateCodec.decode(p));
        p = neural(); p.setProperty("embedding_centroid", IdentityModelCodec.vector(EmbeddingIdentityTest.vector(1)));
        assertNotEquals(old, TemplateCodec.fingerprint(p));
        assertNull(TemplateCodec.decode(p));
    }
}
