package com.yusd.pixel2dface;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

/** One opaque surface throughout the presentation. Dimensions are density independent. */
final class IslandSurface {
    static final float TOP = -39.5f;
    static final float EXPANDED = 80f;
    private static final float[] TIME = {0, 48, 112, 178, 245, 315, 372, 470};
    private static final float[] HEIGHT = {0, .07f, .385f, .69f, .91f, 1f, 1.016f, 1f};
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path();

    IslandSurface() {
        fill.setColor(Color.BLACK);
        fill.setShadowLayer(2.4f, 0, 1.1f, 0x38000000);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(.55f);
        edge.setColor(0x27ffffff);
    }

    // Height samples from Apple's iPhone 14 Pro Face ID film. Interpolate the live
    // contour so neither the corners nor their shadows jump at a sampled pose.
    static float opening(float age) {
        if (age <= 0) return 0;
        for (int i = 1; i < TIME.length; i++) {
            if (age > TIME[i]) continue;
            float span = TIME[i] - TIME[i - 1], t = (age - TIME[i - 1]) / span;
            float v = 1 - t;
            return HEIGHT[i - 1] * (1 + 2*t)*v*v + HEIGHT[i]*t*t*(3 - 2*t)
                    + span*t*v*(v*slope(i - 1) - t*slope(i));
        }
        return 1;
    }

    private static float slope(int i) {
        if (i == 0 || i == HEIGHT.length - 1
                || (HEIGHT[i] - HEIGHT[i - 1]) * (HEIGHT[i + 1] - HEIGHT[i]) <= 0) return 0;
        return (HEIGHT[i + 1] - HEIGHT[i - 1]) / (TIME[i + 1] - TIME[i - 1]);
    }

    void draw(Canvas canvas, float width, float height, float centerY, float radius) {
        float x = width * .5f, t = centerY - height * .5f, b = centerY + height * .5f;
        float r = Math.min(radius, Math.min(x, height * .5f));
        float k = .5523f + .12f * FocusMotion.clamp((height * .5f - r) / 12f);
        float d = (1 - k) * r;
        outline.rewind();
        outline.moveTo(-x+r, t);
        outline.lineTo(x-r, t);
        outline.cubicTo(x-d,t, x,t+d, x,t+r);
        outline.lineTo(x,b-r);
        outline.cubicTo(x,b-d, x-d,b, x-r,b);
        outline.lineTo(-x+r,b);
        outline.cubicTo(-x+d,b, -x,b-d, -x,b-r);
        outline.lineTo(-x,t+r);
        outline.cubicTo(-x,t+d, -x+d,t, -x+r,t);
        outline.close();
        canvas.drawPath(outline, fill);
        canvas.drawPath(outline, edge);
    }

    void clipContents(Canvas canvas) { canvas.clipPath(outline); }
}
