package com.yusd.pixel2dface;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class EmbeddingIdentityTest {
    static float[] vector(int axis) { float[] v = new float[192]; v[axis] = 1; return v; }
    static FrameQualityPolicy.Sample good() {
        return new FrameQualityPolicy.Sample(1, .99f, .99f, .18f, .18f,
                0, 0, 0, .35f, .05f, .05f, 100, 30, 8);
    }
    @Test public void distancesAndDefensiveCopies() {
        float[] source = vector(0);
        EmbeddingIdentity model = new EmbeddingIdentity(source, .22f);
        source[0] = 0;
        model.snapshot()[0] = 0;
        assertEquals(0f, model.evaluate(vector(0), null, .22f).score, .00001f);
        assertTrue(model.evaluate(vector(0), null, .22f).accepted);
        assertFalse(model.evaluate(vector(1), null, .22f).accepted);
        assertEquals(1f, model.evaluate(vector(1), null, .22f).score, .00001f);
        assertFalse(model.evaluate(vector(0), null, .23f).accepted);
        assertFalse(model.evaluate(vector(0), null, Float.NaN).accepted);
    }
    @Test public void rejectsCorruptAndNonNormalizedVectors() {
        assertFalse(EmbeddingIdentity.validVector(new float[192]));
        assertFalse(EmbeddingIdentity.validVector(new float[128]));
        for (float bad : new float[]{Float.NaN, Float.POSITIVE_INFINITY, 2, .5f}) {
            float[] sample = vector(0); sample[0] = bad;
            assertFalse(EmbeddingIdentity.validVector(sample));
            assertThrows(IllegalArgumentException.class, () -> new EmbeddingIdentity(sample, .22f));
        }
        assertThrows(IllegalArgumentException.class, () -> EmbeddingIdentity.normalize(new float[192]));
        float[] raw = vector(0); raw[0] = 30;
        assertTrue(EmbeddingIdentity.validVector(EmbeddingIdentity.normalize(raw)));
    }
    @Test public void enrollmentRejectsMixedIdentitiesAndKeepsIndependentSamples() {
        EmbeddingEnrollment session = new EmbeddingEnrollment();
        float[] input = vector(0);
        assertTrue(session.add(1000, good(), input));
        input[0] = 0;
        assertFalse(session.add(1000, good(), vector(0)));
        assertFalse(session.add(1050, good(), vector(0)));
        assertFalse(session.add(1300, good(), vector(1)));
        for (int i = 1; i < 10; i++) assertTrue(session.add(1300 + i * 300, good(), vector(0)));
        assertTrue(session.isComplete());
        assertTrue(session.buildModel().evaluate(vector(0), null, .18f).accepted);
        List<float[]> mixed = new ArrayList<>();
        for (int i = 0; i < 10; i++) mixed.add(vector(i == 9 ? 1 : 0));
        assertThrows(IllegalArgumentException.class, () -> EmbeddingIdentity.enroll(mixed));
    }
    @Test public void neuralVotesNeedFiveFreshFramesAndNeverUseTheLegacyFastPath() {
        RecognitionEngine engine = new RecognitionEngine(new EmbeddingIdentity(vector(0), .22f), .22f);
        long generation = engine.beginSession(1000);
        for (int i = 0; i < 4; i++) assertFalse(engine.recognize(generation,
                1000 + i * 40, 1010 + i * 40, good(), vector(0), null).votes.confirmed);
        RecognitionEngine.Decision last = engine.recognize(generation, 1160, 1170, good(), vector(0), null);
        assertTrue(last.votes.confirmed);
        assertFalse(last.votes.fastPath);
        assertEquals(.22f, last.frameThreshold, .0001f);
    }
    @Test public void invalidAndStaleNeuralEvidenceErasesContinuity() {
        RecognitionEngine engine = new RecognitionEngine(new EmbeddingIdentity(vector(0), .22f), .22f);
        long generation = engine.beginSession(1000);
        engine.recognize(generation, 1000, 1010, good(), vector(0), null);
        assertEquals(FrameQualityPolicy.Reason.INVALID_DESCRIPTOR,
                engine.recognize(generation, 1040, 1050, good(), new float[192], null).reason);
        assertEquals(1, engine.recognize(generation, 1080, 1090, good(), vector(0), null).votes.matches);
        assertEquals(FrameQualityPolicy.Reason.STALE_FRAME,
                engine.recognize(generation, 1120, 2000, good(), vector(0), null).reason);
        engine.endSession();
        assertEquals(FrameQualityPolicy.Reason.STALE_SESSION,
                engine.recognize(generation, 2010, 2020, good(), vector(0), null).reason);
    }
}
