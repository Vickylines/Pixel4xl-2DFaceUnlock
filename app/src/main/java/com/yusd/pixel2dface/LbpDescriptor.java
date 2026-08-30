package com.yusd.pixel2dface;

import android.graphics.Rect;
import android.graphics.PointF;

import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

/**
 * Illumination-normalized local texture descriptor.
 *
 * <p>The hot path samples CameraX's Y plane directly into reusable buffers. This avoids the
 * full-frame Bitmap conversion, rotation, crop and scale allocations that used to happen for
 * every analyzed frame.</p>
 */
public final class LbpDescriptor {
    private static final int SIZE = 112;
    private static final int GRID = 7;
    private static final int BINS = 256;
    public static final int LENGTH = GRID * GRID * BINS;

    private LbpDescriptor() {
    }

    public static final class Workspace {
        private final int[] gray = new int[SIZE * SIZE];
        private final int[] histogram = new int[256];
        private final int[] equalizationMap = new int[256];
        private final int[] cellCounts = new int[GRID * GRID];
        private final float[] descriptor = new float[LENGTH];
        private float brightness;
        private float contrast;
        private float sharpness;

        public float[] compute(ImageProxy image, int rotationDegrees, Rect uprightCrop) {
            return compute(image, rotationDegrees, uprightCrop, null, null);
        }

        /**
         * Normalizes scale and in-plane rotation from the detected eye positions before LBP.
         * This removes most crop jitter, so a tighter identity limit remains usable in ordinary
         * lighting and while the phone is held at a small angle.
         */
        public float[] compute(ImageProxy image, int rotationDegrees, Rect uprightCrop,
                PointF firstEye, PointF secondEye) {
            ImageProxy.PlaneProxy[] planes = image.getPlanes();
            if (planes.length == 0) {
                throw new IllegalArgumentException("Y plane is unavailable");
            }
            ImageProxy.PlaneProxy yPlane = planes[0];
            ByteBuffer buffer = yPlane.getBuffer();
            int baseOffset = buffer.position();
            int rowStride = yPlane.getRowStride();
            int pixelStride = yPlane.getPixelStride();
            int rawWidth = image.getWidth();
            int rawHeight = image.getHeight();
            int rotation = normalizeRotation(rotationDegrees);
            int uprightWidth = rotation == 90 || rotation == 270 ? rawHeight : rawWidth;
            int uprightHeight = rotation == 90 || rotation == 270 ? rawWidth : rawHeight;

            Rect crop = new Rect(
                    Math.max(0, uprightCrop.left),
                    Math.max(0, uprightCrop.top),
                    Math.min(uprightWidth, uprightCrop.right),
                    Math.min(uprightHeight, uprightCrop.bottom));
            if (crop.width() < 2 || crop.height() < 2) {
                throw new IllegalArgumentException("Face crop is empty");
            }

            Alignment alignment = Alignment.create(firstEye, secondEye, crop);

            long sum = 0L;
            long squareSum = 0L;
            for (int y = 0; y < SIZE; y++) {
                int row = y * SIZE;
                for (int x = 0; x < SIZE; x++) {
                    float uprightX;
                    float uprightY;
                    if (alignment != null) {
                        float normalizedX = (x + 0.5f) / SIZE - 0.5f;
                        float normalizedY = (y + 0.5f) / SIZE - 0.38f;
                        uprightX = alignment.centerX
                                + alignment.axisX * normalizedX * alignment.faceScale
                                - alignment.axisY * normalizedY * alignment.faceScale;
                        uprightY = alignment.centerY
                                + alignment.axisY * normalizedX * alignment.faceScale
                                + alignment.axisX * normalizedY * alignment.faceScale;
                    } else {
                        uprightX = crop.left + (x + 0.5f) * crop.width() / SIZE;
                        uprightY = crop.top + (y + 0.5f) * crop.height() / SIZE;
                    }
                    float rawX;
                    float rawY;
                    if (rotation == 90) {
                        rawX = uprightY;
                        rawY = rawHeight - 1f - uprightX;
                    } else if (rotation == 180) {
                        rawX = rawWidth - 1f - uprightX;
                        rawY = rawHeight - 1f - uprightY;
                    } else if (rotation == 270) {
                        rawX = rawWidth - 1f - uprightY;
                        rawY = uprightX;
                    } else {
                        rawX = uprightX;
                        rawY = uprightY;
                    }
                    int value = sampleBilinear(buffer, baseOffset, rowStride, pixelStride,
                            rawWidth, rawHeight, rawX, rawY);
                    gray[row + x] = value;
                    sum += value;
                    squareSum += (long) value * value;
                }
            }

            int pixelCount = gray.length;
            brightness = sum / (float) pixelCount;
            double variance = squareSum / (double) pixelCount
                    - (double) brightness * brightness;
            contrast = (float) Math.sqrt(Math.max(0d, variance));
            long gradient = 0L;
            int gradientCount = 0;
            for (int y = 0; y < SIZE; y++) {
                int row = y * SIZE;
                for (int x = 0; x < SIZE; x++) {
                    int value = gray[row + x];
                    if (x + 1 < SIZE) {
                        gradient += Math.abs(value - gray[row + x + 1]);
                        gradientCount++;
                    }
                    if (y + 1 < SIZE) {
                        gradient += Math.abs(value - gray[row + SIZE + x]);
                        gradientCount++;
                    }
                }
            }
            sharpness = gradient / (float) Math.max(1, gradientCount);

            Arrays.fill(histogram, 0);
            for (int value : gray) {
                histogram[value]++;
            }
            equalize(gray, histogram, equalizationMap);

            Arrays.fill(descriptor, 0f);
            Arrays.fill(cellCounts, 0);
            for (int y = 1; y < SIZE - 1; y++) {
                for (int x = 1; x < SIZE - 1; x++) {
                    int center = gray[y * SIZE + x];
                    int code = 0;
                    code |= bit(gray[(y - 1) * SIZE + (x - 1)], center, 7);
                    code |= bit(gray[(y - 1) * SIZE + x], center, 6);
                    code |= bit(gray[(y - 1) * SIZE + (x + 1)], center, 5);
                    code |= bit(gray[y * SIZE + (x + 1)], center, 4);
                    code |= bit(gray[(y + 1) * SIZE + (x + 1)], center, 3);
                    code |= bit(gray[(y + 1) * SIZE + x], center, 2);
                    code |= bit(gray[(y + 1) * SIZE + (x - 1)], center, 1);
                    code |= bit(gray[y * SIZE + (x - 1)], center, 0);

                    int cellX = Math.min(GRID - 1, x * GRID / SIZE);
                    int cellY = Math.min(GRID - 1, y * GRID / SIZE);
                    int cell = cellY * GRID + cellX;
                    descriptor[cell * BINS + code] += 1f;
                    cellCounts[cell]++;
                }
            }

            for (int cell = 0; cell < cellCounts.length; cell++) {
                float divisor = Math.max(1, cellCounts[cell]);
                int offset = cell * BINS;
                for (int bin = 0; bin < BINS; bin++) {
                    descriptor[offset + bin] /= divisor;
                }
            }
            return descriptor;
        }

        public float getBrightness() {
            return brightness;
        }

        public float getContrast() {
            return contrast;
        }

        public float getSharpness() {
            return sharpness;
        }
    }

    private static final class Alignment {
        final float centerX;
        final float centerY;
        final float axisX;
        final float axisY;
        final float faceScale;

        Alignment(float centerX, float centerY, float axisX, float axisY, float faceScale) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.axisX = axisX;
            this.axisY = axisY;
            this.faceScale = faceScale;
        }

        static Alignment create(PointF first, PointF second, Rect faceBox) {
            if (first == null || second == null) {
                return null;
            }
            PointF left = first.x <= second.x ? first : second;
            PointF right = first.x <= second.x ? second : first;
            float dx = right.x - left.x;
            float dy = right.y - left.y;
            float distance = (float) Math.hypot(dx, dy);
            if (distance < Math.max(18f, faceBox.width() * 0.20f)
                    || distance > faceBox.width() * 0.78f) {
                return null;
            }
            return new Alignment((left.x + right.x) * 0.5f,
                    (left.y + right.y) * 0.5f, dx / distance, dy / distance,
                    distance / 0.36f);
        }
    }

    public static float[] mean(List<float[]> descriptors) {
        float[] result = new float[LENGTH];
        if (descriptors.isEmpty()) {
            return result;
        }
        for (float[] descriptor : descriptors) {
            for (int i = 0; i < result.length; i++) {
                result[i] += descriptor[i];
            }
        }
        float divisor = descriptors.size();
        for (int i = 0; i < result.length; i++) {
            result[i] /= divisor;
        }
        return result;
    }

    private static int sampleBilinear(ByteBuffer buffer, int baseOffset, int rowStride,
            int pixelStride, int width, int height, float x, float y) {
        float safeX = Math.max(0f, Math.min(width - 1f, x));
        float safeY = Math.max(0f, Math.min(height - 1f, y));
        int x0 = (int) safeX;
        int y0 = (int) safeY;
        int x1 = Math.min(width - 1, x0 + 1);
        int y1 = Math.min(height - 1, y0 + 1);
        float fx = safeX - x0;
        float fy = safeY - y0;
        int p00 = buffer.get(baseOffset + y0 * rowStride + x0 * pixelStride) & 0xff;
        int p10 = buffer.get(baseOffset + y0 * rowStride + x1 * pixelStride) & 0xff;
        int p01 = buffer.get(baseOffset + y1 * rowStride + x0 * pixelStride) & 0xff;
        int p11 = buffer.get(baseOffset + y1 * rowStride + x1 * pixelStride) & 0xff;
        float top = p00 + (p10 - p00) * fx;
        float bottom = p01 + (p11 - p01) * fx;
        return Math.max(0, Math.min(255, Math.round(top + (bottom - top) * fy)));
    }

    private static int normalizeRotation(int rotation) {
        int normalized = ((rotation % 360) + 360) % 360;
        if (normalized == 90 || normalized == 180 || normalized == 270) {
            return normalized;
        }
        return 0;
    }

    private static int bit(int neighbor, int center, int shift) {
        return neighbor >= center ? 1 << shift : 0;
    }

    private static void equalize(int[] gray, int[] histogram, int[] map) {
        int total = gray.length;
        int cumulative = 0;
        int firstNonZero = 0;
        while (firstNonZero < histogram.length && histogram[firstNonZero] == 0) {
            firstNonZero++;
        }
        int minimum = firstNonZero < histogram.length ? histogram[firstNonZero] : 0;
        int denominator = Math.max(1, total - minimum);
        for (int i = 0; i < histogram.length; i++) {
            cumulative += histogram[i];
            map[i] = Math.max(0, Math.min(255,
                    Math.round((cumulative - minimum) * 255f / denominator)));
        }
        for (int i = 0; i < gray.length; i++) {
            gray[i] = map[gray[i]];
        }
    }
}
