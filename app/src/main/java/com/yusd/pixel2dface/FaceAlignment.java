package com.yusd.pixel2dface;

/** Least-squares non-reflecting similarity transform from canonical 112px space to upright camera. */
final class FaceAlignment {
    static final float[] CANONICAL = {38.2946f, 51.6963f, 73.5318f, 51.5014f,
            56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f};
    final float a, b, tx, ty;

    private FaceAlignment(float a, float b, float tx, float ty) {
        this.a = a; this.b = b; this.tx = tx; this.ty = ty;
    }

    static FaceAlignment fit(float[] points) {
        if (points == null || points.length != 10) throw new IllegalArgumentException("Five landmarks required");
        double sx = 0, sy = 0, dx = 0, dy = 0;
        for (int i = 0; i < 10; i += 2) {
            if (!Float.isFinite(points[i]) || !Float.isFinite(points[i + 1])
                    || Math.abs(points[i]) > 32768 || Math.abs(points[i + 1]) > 32768) {
                throw new IllegalArgumentException("Invalid face landmarks");
            }
            sx += CANONICAL[i]; sy += CANONICAL[i + 1];
            dx += points[i]; dy += points[i + 1];
        }
        sx /= 5; sy /= 5; dx /= 5; dy /= 5;
        double numeratorA = 0, numeratorB = 0, denominator = 0;
        for (int i = 0; i < 10; i += 2) {
            double x = CANONICAL[i] - sx, y = CANONICAL[i + 1] - sy;
            double u = points[i] - dx, v = points[i + 1] - dy;
            numeratorA += x * u + y * v;
            numeratorB += x * v - y * u;
            denominator += x * x + y * y;
        }
        double a = numeratorA / denominator, b = numeratorB / denominator;
        double scale = Math.hypot(a, b);
        if (scale < 0.25 || scale > 40 || a <= 0) throw new IllegalArgumentException("Degenerate face alignment");
        FaceAlignment result = new FaceAlignment((float) a, (float) b,
                (float) (dx - a * sx + b * sy), (float) (dy - b * sx - a * sy));
        double error = 0;
        for (int i = 0; i < 10; i += 2) {
            double ex = result.x(CANONICAL[i], CANONICAL[i + 1]) - points[i];
            double ey = result.y(CANONICAL[i], CANONICAL[i + 1]) - points[i + 1];
            error += ex * ex + ey * ey;
        }
        if (Math.sqrt(error / 5) / scale > 6) throw new IllegalArgumentException("Inconsistent face landmarks");
        return result;
    }

    float x(float x, float y) { return a * x - b * y + tx; }
    float y(float x, float y) { return b * x + a * y + ty; }
}
