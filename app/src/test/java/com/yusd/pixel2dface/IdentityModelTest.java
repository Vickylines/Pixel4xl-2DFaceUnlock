package com.yusd.pixel2dface;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
