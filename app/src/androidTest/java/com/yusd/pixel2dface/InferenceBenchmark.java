package com.yusd.pixel2dface;

import android.content.Context;
import android.graphics.ImageFormat;
import android.os.Bundle;
import android.os.SystemClock;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.tensorflow.lite.Interpreter;

/** On-device comparison against the 0.13.2 loader, using synthetic pixels and no enrollment. */
final class InferenceBenchmark {
    static void run(Context context, Bundle result) throws Exception {
        ByteBuffer input = ByteBuffer.allocateDirect(2 * 112 * 112 * 3 * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        float[][] output = new float[2][EmbeddingIdentity.DIMENSIONS];
        long[][] init = new long[2][10];
        long[][] inference = new long[2][40];
        int mismatches = 0;
        for (int round = 0; round < 10; round++) {
            // Alternate initialization order to avoid favoring the warmer CPU/code path.
            for (int offset = 0; offset < 2; offset++) {
                int lane = (round + offset) % 2;
                long start = SystemClock.elapsedRealtimeNanos();
                if (lane == 0) {
                    ByteBuffer legacy = loadLegacy(context);
                    try (Interpreter old = new Interpreter(legacy,
                            new Interpreter.Options().setNumThreads(2))) {
                        old.allocateTensors();
                        init[lane][round] = SystemClock.elapsedRealtimeNanos() - start;
                    }
                } else {
                    try (MobileFaceEmbedder model = new MobileFaceEmbedder(context)) {
                        init[lane][round] = SystemClock.elapsedRealtimeNanos() - start;
                    }
                }
            }
        }
        ByteBuffer legacy = loadLegacy(context);
        try (Interpreter old = new Interpreter(legacy, new Interpreter.Options().setNumThreads(2));
                MobileFaceEmbedder model = new MobileFaceEmbedder(context)) {
            for (int round = -4; round < 40; round++) {
                fill(input, round + 4);
                ByteBuffer single = input.duplicate();
                single.limit(model.input.capacity());
                model.input.clear();
                model.input.put(single).flip();
                float[] actual = null;
                for (int offset = 0; offset < 2; offset++) {
                    int lane = (round + 4 + offset) % 2;
                    input.rewind();
                    long start = SystemClock.elapsedRealtimeNanos();
                    if (lane == 0) old.run(input, output);
                    else actual = model.embed();
                    if (round >= 0) inference[lane][round] = SystemClock.elapsedRealtimeNanos() - start;
                }
                if (!Arrays.equals(EmbeddingIdentity.normalize(output[0]), actual)) mismatches++;
            }
        }
        result.putInt("comparedInputs", 44);
        result.putInt("outputMismatchCount", mismatches);
        if (mismatches != 0) throw new AssertionError("Loader changed model output");
        String[] names = {"legacy", "mapped"};
        for (int i = 0; i < 2; i++) {
            Arrays.sort(init[i]); Arrays.sort(inference[i]);
            result.putLong(names[i] + "InitMedianUs", init[i][5] / 1000);
            result.putLong(names[i] + "InferenceMedianUs", inference[i][20] / 1000);
        }
        // Native initialization must finish without producing identity evidence; a real
        // frame still passes through the ordinary joined population/contour detector afterward.
        try (SingleFaceDetector detector = FaceDetectorFactory.create(Runnable::run, () -> false)) {
            long start = SystemClock.elapsedRealtime();
            if (!Tasks.await(detector.warmUp(), 10, TimeUnit.SECONDS).isEmpty())
                throw new AssertionError("Blank warm-up unexpectedly found a face");
            result.putLong("detectorWarmUpMs", SystemClock.elapsedRealtime() - start);
            InputImage blank = InputImage.fromByteArray(new byte[640 * 480 * 3 / 2],
                    640, 480, 0, ImageFormat.NV21);
            start = SystemClock.elapsedRealtime();
            if (!Tasks.await(detector.process(blank), 10, TimeUnit.SECONDS).isEmpty())
                throw new AssertionError("Blank camera-sized frame unexpectedly found a face");
            result.putLong("firstWarmDetectorFrameMs", SystemClock.elapsedRealtime() - start);
        }
    }

    /** Reproduce the old stream, verification and direct-buffer copying cost. */
    private static ByteBuffer loadLegacy(Context context) throws Exception {
        byte[] bytes;
        try (InputStream stream = context.getAssets().open(MobileFaceEmbedder.ASSET);
                ByteArrayOutputStream collected = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) != -1) collected.write(chunk, 0, count);
            bytes = collected.toByteArray();
        }
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder encoded = new StringBuilder();
        for (byte value : hash) encoded.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        if (!MobileFaceEmbedder.SHA256.equals(encoded.toString())) throw new AssertionError("Model hash");
        ByteBuffer model = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
        model.put(bytes).rewind();
        return model;
    }

    private static void fill(ByteBuffer input, int pattern) {
        input.clear();
        for (int batch = 0; batch < 2; batch++) {
            for (int y = 0; y < 112; y++) for (int x = 0; x < 112; x++) {
                for (int c = 0; c < 3; c++) input.putFloat(pattern == 0 ? 0f
                        : (((x * (13 + pattern) + y * 7 + c * 41 + pattern) % 256) - 127.5f) / 128f);
            }
        }
        input.flip();
    }
}
