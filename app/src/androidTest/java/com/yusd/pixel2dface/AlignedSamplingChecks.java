package com.yusd.pixel2dface;

import android.os.Bundle;
import android.os.SystemClock;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Arrays;

/** Exercises the production sampler on direct, padded camera-style planes without a camera. */
final class AlignedSamplingChecks {
    private static final int WIDTH = 479;
    private static final int HEIGHT = 359;
    private static final int Y_ROW = 496;
    private static final int ROUNDS = 24;

    static void run(Bundle result) throws Exception {
        AlignedRgbSampler sampler = new AlignedRgbSampler();
        ByteBuffer rgb = MobileFaceEmbedder.allocateInput();
        ByteBuffer y = plane(WIDTH, HEIGHT, Y_ROW, 1, 7);
        ByteBuffer quality = ByteBuffer.allocate(3 * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long[] times = new long[ROUNDS * 8];
        int measured = 0;
        for (int uPixel : new int[]{1, 2}) {
            int chromaWidth = (WIDTH + 1) / 2, chromaHeight = (HEIGHT + 1) / 2;
            int vPixel = 3 - uPixel;
            int uRow = chromaWidth * uPixel + 11, vRow = chromaWidth * vPixel + 5;
            ByteBuffer u = plane(chromaWidth, chromaHeight, uRow, uPixel, 23);
            ByteBuffer v = plane(chromaWidth, chromaHeight, vRow, vPixel, 71);
            FaceAlignment[] fits = new FaceAlignment[4];
            for (int direction = 0; direction < fits.length; direction++) {
                // Fractional scale, shift and in-plane tilt exercise bilinear interpolation.
                float[] points = FaceAlignment.CANONICAL.clone();
                for (int i = 0; i < points.length; i += 2) {
                    float x = points[i], yPoint = points[i + 1];
                    points[i] = 1.37f * x - .16f * yPoint + 63.4f + direction;
                    points[i + 1] = .16f * x + 1.37f * yPoint + 52.4f + uPixel;
                }
                fits[direction] = FaceAlignment.fit(points);
            }
            for (int round = -8; round < ROUNDS; round++) {
                for (int direction = 0; direction < 4; direction++) {
                    long start = SystemClock.elapsedRealtimeNanos();
                    boolean sampled = sampler.sample(y, Y_ROW, 1, u, uRow, uPixel, v, vRow,
                            vPixel, WIDTH, HEIGHT, direction * 90, fits[direction], rgb);
                    long elapsed = SystemClock.elapsedRealtimeNanos() - start;
                    if (!sampled || rgb.remaining() != 112 * 112 * 3 * Float.BYTES)
                        throw new AssertionError("Aligned frame unavailable");
                    if (round >= 0) times[measured++] = elapsed;
                    if (round == 0) {
                        digest.update(rgb.duplicate());
                        quality.clear();
                        quality.putFloat(sampler.brightness).putFloat(sampler.contrast)
                                .putFloat(sampler.sharpness);
                        digest.update(quality.array());
                    }
                }
            }
        }
        Arrays.sort(times);
        result.putInt("samplingFrames", measured);
        result.putLong("samplingMedianUs", (times[measured / 2 - 1] + times[measured / 2]) / 2000);
        result.putLong("samplingP95Us", times[(95 * measured + 99) / 100 - 1] / 1000);
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        result.putString("samplingRgbQualitySha256", hex.toString());
        // Captured with the scalar sampler in release 0.13.23, over all eight fixture layouts.
        if (!hex.toString().equals("a52739007b21cbf99e41e40904bdf2c6c3edbe8bf4279e71549e344b1e68320f"))
            throw new AssertionError("Aligned RGB or quality differs from the reference sampler");
    }

    private static ByteBuffer plane(int width, int height, int stride, int pixel, int seed) {
        int start = 3;
        ByteBuffer bytes = ByteBuffer.allocateDirect(start + (height - 1) * stride
                + (width - 1) * pixel + 1);
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                bytes.put(start + row * stride + col * pixel, (byte) (row * 37 + col * seed
                        + ((row * col) >>> 2)));
            }
        }
        bytes.position(start);
        return bytes;
    }
}
