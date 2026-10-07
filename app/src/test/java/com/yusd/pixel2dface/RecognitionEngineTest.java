package com.yusd.pixel2dface;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class RecognitionEngineTest {
    private final RecognitionEngine engine = new RecognitionEngine(model(), 0.28f);
    private long session = engine.beginSession(1_000L);

    @Test public void strongEvidenceConfirmsOnlyOnce() {
        for (int i = 0; i < 3; i++) assertFalse(frame(i * 33L).votes.confirmed);
        RecognitionEngine.Decision decision = frame(99L);
        assertTrue(decision.votes.confirmed);
        assertTrue(decision.votes.fastPath);
        assertEquals(4, decision.openEyeFrames);
        assertEquals(FrameQualityPolicy.Reason.STALE_SESSION, frame(132L).reason);
    }

    @Test public void ordinaryPathKeepsTheOriginalFiveObservationRequirement() {
        for (int i = 0; i < 4; i++) {
            assertFalse(recognize(i * 60L, good(0.75f), texture(4)).votes.confirmed);
        }
        RecognitionEngine.Decision result = recognize(240L, good(0.75f), texture(4));
        assertTrue(result.votes.confirmed);
        assertFalse(result.votes.fastPath);
    }

    @Test public void hardEyeFailureErasesOldVotesAndEyeCount() {
        for (int i = 0; i < 3; i++) frame(i * 33L);
        RecognitionEngine.Decision closed = recognize(99L, good(0.2f), texture(4));
        assertEquals(FrameQualityPolicy.Reason.EYES_CLOSED, closed.reason);
        assertEquals(0, closed.openEyeFrames);
        RecognitionEngine.Decision next = frame(132L);
        assertEquals(1, next.votes.matches);
        assertEquals(1, next.openEyeFrames);
        assertFalse(next.votes.confirmed);
    }

    @Test public void ordinaryIdentityMissMayOccupyOneFreshVotingSlot() {
        recognize(0L, good(0.75f), texture(4));
        recognize(60L, good(0.75f), texture(4));
        RecognitionEngine.Decision miss = recognize(120L, good(0.75f), texture(90));
        assertEquals(FrameQualityPolicy.Reason.IDENTITY_MISMATCH, miss.reason);
        recognize(180L, good(0.75f), texture(4));
        assertTrue(recognize(240L, good(0.75f), texture(4)).votes.confirmed);
    }

    @Test public void corruptDescriptorCannotActAsAnOrdinaryMiss() {
        for (int i = 0; i < 3; i++) frame(i * 33L);
        float[] corrupt = texture(4);
        corrupt[4] = 0.8f; // Chi-square alone accepts this truncated histogram.
        RecognitionEngine.Decision result = recognize(99L, good(0.99f), corrupt);
        assertEquals(FrameQualityPolicy.Reason.INVALID_DESCRIPTOR, result.reason);
        assertEquals(0, result.votes.matches);
        assertEquals(1, frame(132L).votes.matches);
    }

    @Test public void replacementSessionCannotReuseVotesOrBeClearedByOldCallback() {
        long oldSession = session;
        frame(0L);
        frame(33L);
        session = engine.beginSession(2_000L);
        for (int i = 0; i < 3; i++) {
            assertFalse(engine.recognize(session, 2_000L + i * 33L,
                    2_010L + i * 33L, good(0.99f), texture(4), geometry()).votes.confirmed);
        }
        assertEquals(FrameQualityPolicy.Reason.STALE_SESSION, engine.recognize(oldSession,
                1_066L, 2_080L, good(0.99f), texture(4), geometry()).reason);
        assertTrue(engine.recognize(session, 2_099L, 2_109L,
                good(0.99f), texture(4), geometry()).votes.confirmed);
    }

    @Test public void clearDoesNotPermitRepeatedCapturedTimestamp() {
        frame(0L);
        engine.clear();
        assertEquals(FrameQualityPolicy.Reason.INVALID_TIME, frame(0L).reason);
        assertEquals(FrameQualityPolicy.Reason.INVALID_TIME, frame(0L).reason);
        assertEquals(1, frame(33L).votes.matches);
    }

    @Test public void staleFutureAndBeforeSessionFramesNeverVote() {
        assertEquals(FrameQualityPolicy.Reason.INVALID_TIME, engine.recognize(session,
                999L, 1_010L, good(0.99f), texture(4), geometry()).reason);
        assertEquals(FrameQualityPolicy.Reason.INVALID_TIME, engine.recognize(session,
                1_020L, 1_019L, good(0.99f), texture(4), geometry()).reason);
        assertEquals(FrameQualityPolicy.Reason.STALE_FRAME, engine.recognize(session,
                1_030L, 1_781L, good(0.99f), texture(4), geometry()).reason);
        assertEquals(1, engine.recognize(session, 1_063L, 1_800L,
                good(0.99f), texture(4), geometry()).votes.matches);
    }

    @Test public void freshnessBoundaryIsInclusiveAndEvaluationClockCannotGoBackwards() {
        assertEquals(1, engine.recognize(session, 1_000L, 1_750L,
                good(0.99f), texture(4), geometry()).votes.matches);
        assertEquals(FrameQualityPolicy.Reason.INVALID_TIME, engine.recognize(session,
                1_033L, 1_749L, good(0.99f), texture(4), geometry()).reason);
        assertEquals(1, engine.recognize(session, 1_066L, 1_816L,
                good(0.99f), texture(4), geometry()).votes.matches);
    }

    @Test public void sensorGapResetsBothEyeAndIdentityEvidence() {
        frame(0L);
        frame(33L);
        RecognitionEngine.Decision afterGap = frame(384L);
        assertEquals(1, afterGap.openEyeFrames);
        assertEquals(1, afterGap.votes.matches);
        assertFalse(afterGap.votes.confirmed);
    }

    @Test public void endedSessionCannotAcceptLateResults() {
        for (int i = 0; i < 3; i++) frame(i * 33L);
        engine.endSession();
        assertEquals(FrameQualityPolicy.Reason.STALE_SESSION, frame(99L).reason);
    }

    @Test public void invalidSettingsAreRejectedBeforeAnyCapture() {
        assertThrows(IllegalArgumentException.class, () -> new RecognitionEngine(model(), Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> new RecognitionEngine(model(), 0.35f));
        assertThrows(IllegalArgumentException.class, () -> engine.beginSession(-1L));
    }

    @Test public void startupDeliveryDelayDropCanStillProduceFreshStrongEvidence() {
        FrameTimestampNormalizer clock = new FrameTimestampNormalizer();
        long[] sensorTimes = {8_000_000_000L, 8_300_000_000L, 8_333_000_000L, 8_366_000_000L};
        for (int i = 0; i < sensorTimes.length; i++) {
            long arrival = 1_000L + i * 33L;
            long captured = clock.normalize(sensorTimes[i], arrival);
            assertTrue(captured <= arrival);
            RecognitionEngine.Decision result = engine.recognize(session, captured, arrival + 10L,
                    good(0.99f), texture(4), geometry());
            assertEquals(i + 1, result.votes.matches);
            assertEquals(i == 3, result.votes.confirmed);
        }
    }

    private RecognitionEngine.Decision frame(long offset) {
        return recognize(offset, good(0.99f), texture(4));
    }

    private RecognitionEngine.Decision recognize(long offset, FrameQualityPolicy.Sample sample,
            float[] texture) {
        return engine.recognize(session, 1_000L + offset, 1_010L + offset,
                sample, texture, geometry());
    }

    private static FrameQualityPolicy.Sample good(float probability) {
        return new FrameQualityPolicy.Sample(1, probability, probability, 0.18f, 0.18f,
                0f, 0f, 0f, 0.35f, 0.05f, 0.05f, 100f, 30f, 8f);
    }

    private static IdentityModel model() {
        List<float[]> textures = new ArrayList<>();
        List<float[]> geometries = new ArrayList<>();
        for (int i = 0; i < 6; i++) { textures.add(texture(4)); geometries.add(geometry()); }
        return IdentityModel.enroll(textures, geometries);
    }

    private static float[] texture(int bin) {
        float[] result = new float[LbpDescriptor.LENGTH];
        for (int cell = 0; cell < IdentityModel.CELL_COUNT; cell++) {
            result[cell * IdentityModel.BINS + bin] = 1f;
        }
        return result;
    }

    private static float[] geometry() {
        return new float[]{0.72f, 0.40f, 0.22f, 0.42f, 0.30f, 0.21f, 0f};
    }
}
