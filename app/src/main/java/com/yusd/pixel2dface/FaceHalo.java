package com.yusd.pixel2dface;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;

/** A single sweep lights a graduated ring. The same rim closes around the result. */
final class FaceHalo {
    static final long CYCLE_MS = 1850;
    private static final int COUNT = 60;
    private final float[] xs = new float[COUNT], ys = new float[COUNT];
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Shader beam = new LinearGradient(-14,0,14,0,
            new int[]{0x0094ddbd,0x9966d5ad,0xb5c9ffec,0x9966d5ad,0x0094ddbd},null,Shader.TileMode.CLAMP);

    FaceHalo() {
        for (int i = 0; i < COUNT; i++) {
            double angle = 2 * Math.PI * i / COUNT - Math.PI / 2;
            xs[i] = (float) Math.cos(angle);
            ys[i] = (float) Math.sin(angle);
        }
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        ink.setStyle(Paint.Style.STROKE);
    }

    void draw(Canvas c, float phase, float success) {
        float seal = FocusMotion.ease(.03f, .73f, success);
        float front = phase * COUNT;
        for (int i = 0; i < COUNT && seal < 1; i++) {
            float distance = (front - i + COUNT) % COUNT;
            float head = distance < COUNT * .26f ? 1 - FocusMotion.ease(0, COUNT * .26f, distance)
                    : FocusMotion.ease(COUNT - 2.1f, COUNT, distance);
            head *= head;
            float radius = 26f - 6f * seal, length = 2f + 2.8f*head;
            ink.setColor(FocusRibbon.blend(0xff70948c, 0xffcaffed, head));
            ink.setAlpha(Math.round((69 + 186*head)*(1-seal)));
            ink.setStrokeWidth(1.05f + .55f*head);
            c.drawLine(xs[i]*(radius-length), ys[i]*(radius-length),
                    xs[i]*radius, ys[i]*radius, ink);
        }
        if (seal > 0) {
            ink.setColor(0xff8aefb1);
            ink.setAlpha(Math.round(255*seal));
            ink.setStrokeWidth(1.7f);
            c.drawCircle(0,0,20f,ink);
            float written = FocusMotion.ease(.21f,.85f,success);
            if (written > 0) {
                ink.setStrokeWidth(2.85f);
                ResultGlyphs.traceCheck(path, 1.18f, written);
                c.drawPath(path,ink);
            }
        }
    }

    void drawScanLine(Canvas c, float phase, float opacity) {
        float travel = .5f - .5f*(float)Math.cos(phase * 2*Math.PI);
        float width = 7f + 6f*(float)Math.sin(travel*Math.PI);
        float y = -10 + 20*travel;
        ink.setStrokeWidth(.85f);
        ink.setColor(0xffffffff);
        ink.setAlpha(Math.round(255 * opacity));
        ink.setShader(beam);
        c.drawLine(-width,y,width,y,ink);
        ink.setShader(null);
    }
}
