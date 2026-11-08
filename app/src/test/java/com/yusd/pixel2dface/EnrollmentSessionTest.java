package com.yusd.pixel2dface;

import static org.junit.Assert.*;
import java.util.Arrays;
import org.junit.Test;

public final class EnrollmentSessionTest {
    private final EnrollmentSession enrollment = new EnrollmentSession();

    @Test public void ownsBothTextureAndGeometryDespiteCameraWorkspaceReuse() {
        float[] reusedTexture = texture(4);
        float[] reusedGeometry = geometry();
        for (int i = 0; i < EnrollmentSession.REQUIRED_SAMPLES; i++) {
            fillTexture(reusedTexture, 4);
            reusedGeometry[0] = 0.72f;
            assertTrue(enrollment.add(1_000L + i * 280L, sample(0f, 0f, 0f),
                    reusedTexture, reusedGeometry));
            // Camera extraction replaces its workspace immediately after the callback.
            fillTexture(reusedTexture, 90);
            reusedGeometry[0] = 1.6f;
        }
        IdentityModel model = enrollment.buildModel();
        assertTrue(model.compare(texture(4), geometry(), model.textureThreshold).accepted);
        assertFalse(model.compare(texture(90), geometry(), model.textureThreshold).accepted);
    }

    @Test public void samplingIntervalUsesCaptureEvidenceRegardlessOfDeliveryDelay() {
        assertTrue(add(1_000L));
        // Even if inference took a long time, capture times 100 ms apart are not distinct
        // enrollment poses. There is deliberately no callback/system time parameter.
        assertFalse(add(1_100L));
        assertFalse(add(1_279L));
        assertEquals(1, enrollment.count());
        assertTrue(add(1_280L));
        assertEquals(2, enrollment.count());
    }

    @Test public void rejectedFrameDoesNotMoveTheAcceptedSampleInterval() {
        assertTrue(add(0L));
        assertFalse(enrollment.add(280L, sample(20f, 0f, 0f), texture(4), geometry()));
        assertTrue(add(281L));
    }

    @Test public void enrollmentUsesItsStricterPoseLimitsWithInclusiveBoundaries() {
        assertTrue(enrollment.add(0L, sample(18f, 16f, 18f), texture(4), geometry()));
        assertFalse(enrollment.add(280L, sample(18.01f, 0f, 0f), texture(4), geometry()));
        assertFalse(enrollment.add(560L, sample(0f, 16.01f, 0f), texture(4), geometry()));
        assertFalse(enrollment.add(840L, sample(0f, 0f, 18.01f), texture(4), geometry()));
        assertEquals(1, enrollment.count());
        assertTrue(add(1_120L));
    }

    @Test public void repeatedRegressedAndNegativeTimesCannotBecomeExtraSamples() {
        assertFalse(add(-1L));
        assertTrue(add(1_000L));
        assertFalse(add(1_000L));
        assertFalse(add(999L));
        assertFalse(add(1_279L));
        assertTrue(add(1_280L));
        assertEquals(2, enrollment.count());
    }

    @Test public void cannotBuildOrAcceptMoreEvidenceAfterTenSamples() {
        assertThrows(IllegalStateException.class, enrollment::buildModel);
        for (int i = 0; i < EnrollmentSession.REQUIRED_SAMPLES; i++) {
            assertTrue(add(i * 280L));
            assertEquals(i == EnrollmentSession.REQUIRED_SAMPLES - 1, enrollment.isComplete());
        }
        assertFalse(enrollment.add(2_800L, sample(0f, 0f, 0f), texture(90), geometry()));
        assertEquals(10, enrollment.count());
        assertTrue(enrollment.buildModel().compare(texture(4), geometry(), 0.28f).accepted);
    }

    @Test public void invalidMeasurementsAndFeaturesNeverPolluteTheEnrollmentSet() {
        assertFalse(enrollment.add(0L, null, texture(4), geometry()));
        assertFalse(enrollment.add(280L, sample(Float.NaN, 0f, 0f), texture(4), geometry()));
        float[] corrupt = texture(4);
        corrupt[4] = 0.8f;
        assertFalse(enrollment.add(560L, sample(0f, 0f, 0f), corrupt, geometry()));
        assertFalse(enrollment.add(840L, sample(0f, 0f, 0f), texture(4),
                new float[]{Float.POSITIVE_INFINITY}));
        assertEquals(0, enrollment.count());
        assertTrue(add(1_120L));
    }

    @Test public void clearingReleasesEvidenceAndKeepsReplayProtection() {
        assertTrue(add(1_000L));
        enrollment.clear();
        assertEquals(0, enrollment.count());
        assertFalse(enrollment.isComplete());
        assertThrows(IllegalStateException.class, enrollment::buildModel);
        assertFalse(add(1_000L));
        assertTrue(add(1_280L));
    }

    private boolean add(long capturedAt) {
        return enrollment.add(capturedAt, sample(0f, 0f, 0f), texture(4), geometry());
    }

    private static FrameQualityPolicy.Sample sample(float yaw, float pitch, float roll) {
        return new FrameQualityPolicy.Sample(1, 0.99f, 0.99f, 0.18f, 0.18f,
                yaw, pitch, roll, 0.35f, 0f, 0f, 100f, 30f, 8f);
    }

    private static float[] texture(int bin) {
        float[] result = new float[LbpDescriptor.LENGTH];
        fillTexture(result, bin);
        return result;
    }

    private static void fillTexture(float[] result, int bin) {
        Arrays.fill(result, 0f);
        for (int cell = 0; cell < IdentityModel.CELL_COUNT; cell++) {
            result[cell * IdentityModel.BINS + bin] = 1f;
        }
    }

    private static float[] geometry() {
        return new float[]{0.72f, 0.40f, 0.22f, 0.42f, 0.30f, 0.21f, 0f};
    }
}
