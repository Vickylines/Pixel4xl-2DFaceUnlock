package com.yusd.pixel2dface;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.tensorflow.lite.Interpreter;

/** Alternating native comparison: identical pixels, weights, operations and thread count. */
final class SingleBatchBenchmark {
    static void run(Context test, Context target, Bundle result) throws Exception {
        ByteBuffer original = asset(test, "mobilefacenet-upstream.tflite");
        ByteBuffer single = asset(target, MobileFaceEmbedder.ASSET);
        ByteBuffer input = ByteBuffer.allocateDirect(112 * 112 * 3 * 4).order(ByteOrder.nativeOrder());
        ByteBuffer duplicate = ByteBuffer.allocateDirect(input.capacity() * 2).order(ByteOrder.nativeOrder());
        float[][] oldOutput = new float[2][192], newOutput = new float[2][192];
        Bitmap source;
        try (InputStream stream = test.getAssets().open("astronaut.png")) {
            source = BitmapFactory.decodeStream(stream);
        }
        Bitmap face = Bitmap.createBitmap(source, 175, 55, 112, 112);
        source.recycle();
        long[][] times = new long[2][128];
        int rawMismatches = 0, normalizedMismatches = 0;
        double maxError = 0;
        try (Interpreter old = new Interpreter(original, new Interpreter.Options().setNumThreads(2));
                Interpreter next = new Interpreter(single, new Interpreter.Options().setNumThreads(2))) {
            for (int round = -8; round < 128; round++) {
                fill(input, round + 8, face);
                duplicate.clear(); duplicate.put(input); input.rewind();
                duplicate.put(input); input.rewind(); duplicate.rewind();
                for (int offset = 0; offset < 2; offset++) {
                    int lane = (round + 8 + offset) % 2;
                    long at = SystemClock.elapsedRealtimeNanos();
                    if (lane == 0) old.run(duplicate, oldOutput);
                    else next.run(input, newOutput);
                    if (round >= 0) times[lane][round] = SystemClock.elapsedRealtimeNanos() - at;
                }
                if (!Arrays.equals(oldOutput[0], newOutput[0])) rawMismatches++;
                if (!Arrays.equals(EmbeddingIdentity.normalize(oldOutput[0]),
                        EmbeddingIdentity.normalize(newOutput[0]))) normalizedMismatches++;
                for (int i = 0; i < 192; i++)
                    maxError = Math.max(maxError, Math.abs(oldOutput[0][i] - newOutput[0][i]));
            }
        } finally { face.recycle(); }
        result.putInt("comparedInputs", 136);
        result.putInt("rawMismatchCount", rawMismatches);
        result.putInt("normalizedMismatchCount", normalizedMismatches);
        result.putDouble("maxAbsoluteError", maxError);
        for (int i = 0; i < 2; i++) {
            Arrays.sort(times[i]);
            String name = i == 0 ? "original" : "singleTrunk";
            result.putLong(name + "MedianUs", times[i][64] / 1000);
            result.putLong(name + "P95Us", times[i][121] / 1000);
        }
        if (rawMismatches != 0 || normalizedMismatches != 0)
            throw new AssertionError("Single batch changes native output");
    }

    private static ByteBuffer asset(Context context, String name) throws Exception {
        byte[] bytes;
        try (InputStream stream = context.getAssets().open(name)) { bytes = stream.readAllBytes(); }
        ByteBuffer model = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
        model.put(bytes).rewind();
        return model;
    }

    private static void fill(ByteBuffer input, int pattern, Bitmap face) {
        input.clear();
        for (int y = 0; y < 112; y++) for (int x = 0; x < 112; x++) {
            int pixel = face.getPixel(x, y);
            for (int c = 0; c < 3; c++) {
                int value = pattern < 68
                        ? (x * (13 + pattern) + y * 7 + c * 41 + pattern) % 256
                        : Math.min(255, Math.max(0, ((pixel >> ((2 - c) * 8)) & 255) + pattern - 102));
                input.putFloat(pattern == 0 ? 0f : (value - 127.5f) / 128f);
            }
        }
        input.flip();
    }
}
