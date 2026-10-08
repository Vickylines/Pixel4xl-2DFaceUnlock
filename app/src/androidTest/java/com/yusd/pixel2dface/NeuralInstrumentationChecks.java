package com.yusd.pixel2dface;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import java.util.Arrays;

/** Native model shape, input sensitivity, lifecycle and timings on-device, without personal images. */
final class NeuralInstrumentationChecks {
    static int run(Context context, Bundle result) throws Exception {
        int checks = 0;
        long initStart = SystemClock.elapsedRealtimeNanos();
        float[] first;
        MobileFaceEmbedder model = new MobileFaceEmbedder(context);
        result.putLong("modelInitMs", (SystemClock.elapsedRealtimeNanos() - initStart) / 1_000_000);
        try {
            fill(model, 0);
            long cold = SystemClock.elapsedRealtimeNanos();
            first = model.embed();
            result.putLong("modelFirstInferenceMs", (SystemClock.elapsedRealtimeNanos() - cold) / 1_000_000);
            require(EmbeddingIdentity.validVector(first), "finite unit embedding"); checks++;
            float[] repeated = model.embed();
            require(EmbeddingIdentity.distance(first, repeated) < 0.00001f, "repeatable inference"); checks++;
            fill(model, 1);
            require(EmbeddingIdentity.distance(first, model.embed()) > .0001f, "input affects output"); checks++;
            long[] times = new long[30];
            for (int i = 0; i < times.length; i++) {
                long start = SystemClock.elapsedRealtimeNanos();
                require(EmbeddingIdentity.validVector(model.embed()), "valid repeated native output"); checks++;
                times[i] = SystemClock.elapsedRealtimeNanos() - start;
            }
            Arrays.sort(times);
            result.putLong("inferenceMedianMs", times[15] / 1_000_000);
            result.putLong("inferenceP95Ms", times[28] / 1_000_000);
        } finally { model.close(); }
        model.close();
        boolean closed = false;
        try { model.embed(); } catch (IllegalStateException expected) { closed = true; }
        require(closed, "closed interpreter rejects inference"); checks++;
        try (MobileFaceEmbedder reopened = new MobileFaceEmbedder(context)) {
            fill(reopened, 0);
            require(EmbeddingIdentity.distance(first, reopened.embed()) < .00001f, "reopen matches same model space"); checks++;
        }
        result.putString("modelId", EmbeddingIdentity.MODEL_ID);
        return checks;
    }
    private static void fill(MobileFaceEmbedder model, int pattern) {
        model.input.clear();
        for (int y = 0; y < 112; y++) for (int x = 0; x < 112; x++) {
            for (int c = 0; c < 3; c++) model.input.putFloat(pattern == 0 ? 0f
                    : (((x * 13 + y * 7 + c * 41) % 256) - 127.5f) / 128f);
        }
        model.input.flip();
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
