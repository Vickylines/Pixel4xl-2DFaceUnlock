package com.yusd.pixel2dface;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public final class IdentityModelTest {
    @Test
    public void enrolledIdentityPassesBothSignals() {
        IdentityModel model = enrolledModel();
        IdentityModel.Match match = model.compare(texture(4), geometry(0f),
                model.textureThreshold);
        assertTrue(match.accepted);
        assertTrue(match.consistentCells >= IdentityModel.MIN_CONSISTENT_CELLS);
    }

    @Test
    public void differentTextureIsRejected() {
        IdentityModel model = enrolledModel();
        IdentityModel.Match match = model.compare(texture(91), geometry(0f),
                model.textureThreshold);
        assertFalse(match.accepted);
    }

    @Test
    public void localizedTextureSubstitutionIsRejectedByCellConsensus() {
        IdentityModel model = enrolledModel();
        float[] substituted = texture(4);
        for (int cell = 0; cell < 14; cell++) {
            setCellBin(substituted, cell, 87);
        }
        IdentityModel.Match match = model.compare(substituted, geometry(0f),
                model.textureThreshold);
        assertFalse(match.accepted);
        assertTrue(match.consistentCells < IdentityModel.MIN_CONSISTENT_CELLS);
    }

    @Test
    public void matchingTextureWithDifferentGeometryIsRejected() {
        IdentityModel model = enrolledModel();
        IdentityModel.Match match = model.compare(texture(4), geometry(0.18f),
                model.textureThreshold);
        assertFalse(match.accepted);
    }

    private static IdentityModel enrolledModel() {
        List<float[]> textures = new ArrayList<>();
        List<float[]> geometries = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            textures.add(texture(4));
            geometries.add(geometry((i - 4.5f) * 0.001f));
        }
        return IdentityModel.enroll(textures, geometries);
    }

    @Test public void highConfidenceRequiresEveryAdditionalMargin() {
        assertTrue(new IdentityModel.Match(0.20f, 0.40f, 1.0f, 44, 24, true)
                .isHighConfidence(0.28f, 1.35f));
        assertFalse(new IdentityModel.Match(0.26f, 0.40f, 1.0f, 44, 24, true)
                .isHighConfidence(0.28f, 1.35f));
        assertFalse(new IdentityModel.Match(0.20f, 0.80f, 1.0f, 44, 24, true)
                .isHighConfidence(0.28f, 1.35f));
        assertFalse(new IdentityModel.Match(0.20f, 0.40f, 2.0f, 44, 24, true)
                .isHighConfidence(0.28f, 1.35f));
        assertFalse(new IdentityModel.Match(0.20f, 0.40f, 1.0f, 43, 24, true)
                .isHighConfidence(0.28f, 1.35f));
        assertFalse(new IdentityModel.Match(0.20f, 0.40f, 1.0f, 44, 23, true)
                .isHighConfidence(0.28f, 1.35f));
    }

    @Test public void invalidLiveInputsFailClosed() {
        IdentityModel model = enrolledModel();
        for (float value : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -1f}) {
            float[] invalid = texture(4);
            invalid[4] = value;
            assertFalse(model.compare(invalid, geometry(0f), 0.28f).accepted);
        }
        assertFalse(model.compare(texture(4), geometry(Float.NaN), 0.28f).accepted);
        assertFalse(model.compare(texture(4), geometry(0f), Float.NaN).accepted);
        assertFalse(model.compare(texture(4), geometry(0f), 0.35f).accepted);
    }

    @Test public void corruptedTemplateFailsAtLoad() {
        IdentityModel model = enrolledModel();
        float[] invalid = model.geometryScales.clone();
        invalid[0] = Float.POSITIVE_INFINITY;
        assertThrows(IllegalArgumentException.class, () -> new IdentityModel(
                model.textureCentroid, model.cellWeights, model.cellLimits,
                model.geometryCentroid, invalid, 0.28f, 1.35f));
        assertThrows(IllegalArgumentException.class, () -> new IdentityModel(
                new float[LbpDescriptor.LENGTH], model.cellWeights, model.cellLimits,
                model.geometryCentroid, model.geometryScales, 0.28f, 1.35f));
        assertThrows(IllegalArgumentException.class, () -> new IdentityModel(
                model.textureCentroid, model.cellWeights, model.cellLimits,
                model.geometryCentroid, model.geometryScales, Float.NaN, 1.35f));
    }

    @Test public void callerMutationCannotAlterLoadedModel() {
        IdentityModel source = enrolledModel();
        IdentityModel copy = new IdentityModel(source.textureCentroid, source.cellWeights,
                source.cellLimits, source.geometryCentroid, source.geometryScales,
                source.textureThreshold, source.geometryThreshold);
        source.textureCentroid[4] = 0f;
        source.cellWeights[0] = 0f;
        assertEquals(1f, copy.textureCentroid[4], 0.00001f);
        assertTrue(copy.compare(texture(4), geometry(0f), 0.28f).accepted);
    }

    private static float[] texture(int bin) {
        float[] descriptor = new float[LbpDescriptor.LENGTH];
        for (int cell = 0; cell < IdentityModel.CELL_COUNT; cell++) {
            descriptor[cell * IdentityModel.BINS + bin] = 1f;
        }
        return descriptor;
    }

    private static void setCellBin(float[] descriptor, int cell, int bin) {
        int offset = cell * IdentityModel.BINS;
        for (int i = 0; i < IdentityModel.BINS; i++) {
            descriptor[offset + i] = 0f;
        }
        descriptor[offset + bin] = 1f;
    }

    private static float[] geometry(float delta) {
        return new float[] {
                0.72f + delta, 0.40f, 0.22f, 0.42f,
                0.30f, 0.21f, 0.00f
        };
    }
}
